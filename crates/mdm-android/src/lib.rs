use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Runtime;
use uuid::Uuid;

use mdm_engine::download::{Destination, Options, Request, State as EngineState};
use mdm_torrent::{Config as TorrentConfig, Phase as TorrentPhase, Source, Torrents};

mod hls;

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DownloadKind {
    Http,
    TorrentMagnet,
    TorrentFile,
    Hls,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DownloadState {
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DownloadTask {
    pub id: String,
    pub title: String,
    pub source_url: String,
    pub save_path: String,
    pub kind: DownloadKind,
    pub state: DownloadState,
    pub total_bytes: u64,
    pub downloaded_bytes: u64,
    pub speed_bytes_per_sec: u64,
    pub upload_speed_bytes_per_sec: u64,
    pub active_connections: u32,
    pub error_message: Option<String>,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MdmError {
    #[error("Invalid URL or magnet link: {reason}")]
    InvalidInput { reason: String },
    #[error("Storage error: {reason}")]
    Storage { reason: String },
    #[error("Engine error: {reason}")]
    Engine { reason: String },
    #[error("Task not found: {id}")]
    NotFound { id: String },
}

#[uniffi::export(callback_interface)]
pub trait ProgressListener: Send + Sync {
    fn on_tasks_updated(&self, tasks: Vec<DownloadTask>);
}

type TaskMap = Arc<Mutex<HashMap<String, DownloadTask>>>;
type ListenerSlot = Arc<Mutex<Option<Box<dyn ProgressListener>>>>;

fn notify_all(tasks: &TaskMap, listener: &ListenerSlot) {
    let snapshot = tasks
        .lock()
        .map(|map| map.values().cloned().collect::<Vec<_>>())
        .unwrap_or_default();
    if let Ok(guard) = listener.lock() {
        if let Some(cb) = guard.as_ref() {
            cb.on_tasks_updated(snapshot);
        }
    }
}

fn update_task(tasks: &TaskMap, listener: &ListenerSlot, id: &str, f: impl FnOnce(&mut DownloadTask)) {
    let mut changed = false;
    if let Ok(mut map) = tasks.lock() {
        if let Some(task) = map.get_mut(id) {
            f(task);
            changed = true;
        }
    }
    if changed {
        notify_all(tasks, listener);
    }
}

fn fail_task(tasks: &TaskMap, listener: &ListenerSlot, id: &str, reason: String) {
    update_task(tasks, listener, id, |t| {
        t.state = DownloadState::Failed;
        t.error_message = Some(reason);
        t.speed_bytes_per_sec = 0;
    });
}

/// Lazily start the shared BitTorrent session (or return the running one).
async fn get_torrent_session(
    slot: &Arc<tokio::sync::Mutex<Option<Arc<Torrents>>>>,
    download_dir: &PathBuf,
) -> Result<Arc<Torrents>, String> {
    let mut guard = slot.lock().await;
    if let Some(session) = guard.as_ref() {
        return Ok(Arc::clone(session));
    }
    let state_dir = download_dir.join(".torrent-state");
    let mut cfg = TorrentConfig::new(state_dir);
    // The app's download folder is app-owned: no need for the desktop's
    // authorized-handle storage model; plain filesystem storage is fine.
    cfg.require_authorization = false;
    // No multicast permission on Android: UPnP/LSD discovery stays off.
    // DHT + trackers are enough for most torrents.
    cfg.upnp = false;
    cfg.lsd = false;
    // Torrents::start 本身没有超时；它在手机上可能一直卡住（监听/DHT 初始化），
    // 必须加超时，否则任务永远停在“等待开始”，连失败信息都没有。
    log::info!("正在启动 BT 会话…");
    let session = tokio::time::timeout(Duration::from_secs(30), Torrents::start(cfg))
        .await
        .map_err(|_| "启动 BT 会话超时（30 秒）".to_string())?
        .map_err(|e| format!("启动 BT 会话失败: {e}"))?;
    log::info!("BT 会话已启动");
    let session = Arc::new(session);
    *guard = Some(Arc::clone(&session));
    Ok(session)
}

#[derive(uniffi::Object)]
pub struct DownloadEngine {
    runtime: Runtime,
    download_dir: PathBuf,
    tasks: TaskMap,
    listener: ListenerSlot,
    http_handles: Arc<Mutex<HashMap<String, Arc<mdm_engine::download::Download>>>>,
    torrent_hashes: Arc<Mutex<HashMap<String, String>>>,
    hls_cancels: Arc<Mutex<HashMap<String, Arc<AtomicBool>>>>,
    torrents: Arc<tokio::sync::Mutex<Option<Arc<Torrents>>>>,
    default_connections: Arc<Mutex<u32>>,
    speed_limit_bps: Arc<Mutex<u64>>,
    upload_limit_bps: Arc<Mutex<u64>>,
}

#[uniffi::export]
impl DownloadEngine {
    #[uniffi::constructor]
    pub fn new(download_dir: String) -> Result<Arc<Self>, MdmError> {
        android_logger::init_once(
            android_logger::Config::default().with_max_level(log::LevelFilter::Info),
        );

        let dir = PathBuf::from(&download_dir);
        std::fs::create_dir_all(&dir).map_err(|e| MdmError::Storage {
            reason: e.to_string(),
        })?;

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| MdmError::Engine {
                reason: e.to_string(),
            })?;

        Ok(Arc::new(Self {
            runtime,
            download_dir: dir,
            tasks: Arc::new(Mutex::new(HashMap::new())),
            listener: Arc::new(Mutex::new(None)),
            http_handles: Arc::new(Mutex::new(HashMap::new())),
            torrent_hashes: Arc::new(Mutex::new(HashMap::new())),
            hls_cancels: Arc::new(Mutex::new(HashMap::new())),
            torrents: Arc::new(tokio::sync::Mutex::new(None)),
            default_connections: Arc::new(Mutex::new(8)),
            speed_limit_bps: Arc::new(Mutex::new(0)),
            upload_limit_bps: Arc::new(Mutex::new(0)),
        }))
    }

    pub fn set_listener(&self, listener: Option<Box<dyn ProgressListener>>) {
        if let Ok(mut guard) = self.listener.lock() {
            *guard = listener;
        }
        notify_all(&self.tasks, &self.listener);
    }

    /// New HTTP downloads use this many connections when the caller passes 0.
    pub fn set_default_connections(&self, connections: u32) {
        if let Ok(mut guard) = self.default_connections.lock() {
            *guard = connections.clamp(1, 32);
        }
    }

    /// Global speed limit in bytes/s for every download, 0 = unlimited.
    /// Applies to running HTTP downloads at once; torrents pick it up on next start.
    pub fn set_speed_limit_bps(&self, bps: u64) {
        if let Ok(mut guard) = self.speed_limit_bps.lock() {
            *guard = bps;
        }
        let limit = if bps == 0 { None } else { Some(bps) };
        if let Ok(handles) = self.http_handles.lock() {
            for dl in handles.values() {
                dl.set_speed_limit(limit);
            }
        }
        let slot = Arc::clone(&self.torrents);
        self.runtime.spawn(async move {
            let guard = slot.lock().await;
            if let Some(session) = guard.as_ref() {
                session.set_download_limit(limit);
            }
        });
    }

    /// Global upload limit in bytes/s for torrents (seeding), 0 = unlimited.
    /// Applies to the running BT session at once; also applied when a torrent starts.
    pub fn set_upload_limit_bps(&self, bps: u64) {
        if let Ok(mut guard) = self.upload_limit_bps.lock() {
            *guard = bps;
        }
        let limit = if bps == 0 { None } else { Some(bps) };
        let slot = Arc::clone(&self.torrents);
        self.runtime.spawn(async move {
            let guard = slot.lock().await;
            if let Some(session) = guard.as_ref() {
                session.set_upload_limit(limit);
            }
        });
    }

    pub fn add_http_download(
        &self,
        url: String,
        filename: Option<String>,
        connections: u32,
    ) -> Result<String, MdmError> {
        let parsed = url::Url::parse(&url).map_err(|_| MdmError::InvalidInput {
            reason: "无效的 URL".into(),
        })?;
        match parsed.scheme() {
            "http" | "https" | "ftp" => {}
            other => {
                return Err(MdmError::InvalidInput {
                    reason: format!("不支持的协议: {other}"),
                })
            }
        }

        let id = Uuid::new_v4().to_string();
        let resolved_name = filename.unwrap_or_else(|| {
            url.split('/')
                .last()
                .filter(|s| !s.is_empty())
                .unwrap_or("download.bin")
                .split('?')
                .next()
                .unwrap_or("download.bin")
                .to_string()
        });
        let save_path = self.download_dir.join(&resolved_name);

        let conns = if connections == 0 {
            self.default_connections
                .lock()
                .map(|g| *g)
                .unwrap_or(8)
        } else {
            connections
        };

        let task = DownloadTask {
            id: id.clone(),
            title: resolved_name.clone(),
            source_url: url.clone(),
            save_path: save_path.to_string_lossy().to_string(),
            kind: DownloadKind::Http,
            state: DownloadState::Downloading,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            active_connections: conns.clamp(1, 32),
            error_message: None,
        };

        self.insert_task(task);
        log::info!(
            "[HTTP] 添加任务: {} -> {:?} (连接数 {})",
            resolved_name,
            save_path,
            conns
        );
        self.run_http(id.clone(), parsed, save_path, conns, false, 0);

        Ok(id)
    }

    /// 下载 m3u8 视频：解析播放列表，分片下载并合并成一个 .ts 文件。
    pub fn add_hls_download(
        &self,
        url: String,
        filename: Option<String>,
    ) -> Result<String, MdmError> {
        let parsed = url::Url::parse(&url).map_err(|_| MdmError::InvalidInput {
            reason: "无效的 URL".into(),
        })?;
        match parsed.scheme() {
            "http" | "https" => {}
            other => {
                return Err(MdmError::InvalidInput {
                    reason: format!("不支持的协议: {other}"),
                })
            }
        }

        let id = Uuid::new_v4().to_string();
        let base_name = filename.unwrap_or_else(|| {
            url.split('/')
                .last()
                .filter(|s| !s.is_empty())
                .unwrap_or("video")
                .split('?')
                .next()
                .unwrap_or("video")
                .to_string()
        });
        // 输出一定是 .ts（分片是 MPEG-TS 容器）；去掉原扩展名再加 .ts。
        let stem = base_name
            .rsplit_once('.')
            .map(|(s, _)| s)
            .unwrap_or(&base_name);
        let resolved_name = format!("{stem}.ts");
        let save_path = self.download_dir.join(&resolved_name);

        let task = DownloadTask {
            id: id.clone(),
            title: resolved_name.clone(),
            source_url: url.clone(),
            save_path: save_path.to_string_lossy().to_string(),
            kind: DownloadKind::Hls,
            state: DownloadState::Downloading,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            active_connections: 0,
            error_message: None,
        };

        self.insert_task(task);
        log::info!("[HLS] 添加任务: {} -> {:?}", resolved_name, save_path);
        self.run_hls(id.clone(), parsed, save_path);

        Ok(id)
    }

    pub fn add_magnet_download(&self, magnet_uri: String) -> Result<String, MdmError> {        let src = Source::magnet(&magnet_uri).ok_or_else(|| MdmError::InvalidInput {
            reason: "Magnet link must start with magnet:?".into(),
        })?;

        let id = Uuid::new_v4().to_string();
        let display_name = extract_magnet_dn(&magnet_uri).unwrap_or_else(|| "Magnet Torrent".into());
        let save_path = self.download_dir.join(&display_name);

        let task = DownloadTask {
            id: id.clone(),
            title: display_name.clone(),
            source_url: magnet_uri.clone(),
            save_path: save_path.to_string_lossy().to_string(),
            kind: DownloadKind::TorrentMagnet,
            state: DownloadState::Downloading,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            active_connections: 0,
            error_message: None,
        };

        self.insert_task(task);
        log::info!("[BT] 添加磁力任务: {}", display_name);
        self.run_torrent(id.clone(), src);

        Ok(id)
    }

    pub fn add_torrent_file(&self, torrent_path: String) -> Result<String, MdmError> {
        let path = PathBuf::from(&torrent_path);
        let bytes = std::fs::read(&path).map_err(|_| MdmError::Storage {
            reason: format!("Torrent file not found: {torrent_path}"),
        })?;

        let id = Uuid::new_v4().to_string();
        let title = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("torrent")
            .to_string();

        let task = DownloadTask {
            id: id.clone(),
            title,
            source_url: torrent_path.clone(),
            save_path: self.download_dir.to_string_lossy().to_string(),
            kind: DownloadKind::TorrentFile,
            state: DownloadState::Downloading,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            active_connections: 0,
            error_message: None,
        };

        self.insert_task(task);
        self.run_torrent(id.clone(), Source::File(bytes));

        Ok(id)
    }

    pub fn pause_task(&self, id: String) -> Result<(), MdmError> {
        let task = self
            .tasks
            .lock()
            .map(|map| map.get(&id).cloned())
            .unwrap_or(None)
            .ok_or_else(|| MdmError::NotFound { id: id.clone() })?;

        match task.kind {
            DownloadKind::Http => {
                if let Ok(handles) = self.http_handles.lock() {
                    if let Some(dl) = handles.get(&id) {
                        dl.cancel();
                    }
                }
            }
            DownloadKind::Hls => {
                if let Ok(map) = self.hls_cancels.lock() {
                    if let Some(flag) = map.get(&id) {
                        flag.store(true, Ordering::SeqCst);
                    }
                }
            }
            _ => {
                if let Some(hash) = self
                    .torrent_hashes
                    .lock()
                    .map(|map| map.get(&id).cloned())
                    .unwrap_or(None)
                {
                    let slot = Arc::clone(&self.torrents);
                    let download_dir = self.download_dir.clone();
                    self.runtime.spawn(async move {
                        if let Ok(session) = get_torrent_session(&slot, &download_dir).await {
                            let _ = session.pause(&hash).await;
                        }
                    });
                }
            }
        }
        self.update_state(&id, DownloadState::Paused)
    }

    pub fn resume_task(&self, id: String) -> Result<(), MdmError> {
        let task = self
            .tasks
            .lock()
            .map(|map| map.get(&id).cloned())
            .unwrap_or(None)
            .ok_or_else(|| MdmError::NotFound { id: id.clone() })?;

        match task.kind {
            DownloadKind::Http => {
                let parsed = url::Url::parse(&task.source_url).map_err(|_| MdmError::InvalidInput {
                    reason: "无效的 URL".into(),
                })?;
                let save_path = PathBuf::from(&task.save_path);
                let conns = task.active_connections;
                let shown = task.downloaded_bytes;
                self.update_state(&id, DownloadState::Downloading)?;
                self.run_http(id, parsed, save_path, conns, true, shown);
                Ok(())
            }
            DownloadKind::Hls => {
                let parsed = url::Url::parse(&task.source_url).map_err(|_| MdmError::InvalidInput {
                    reason: "无效的 URL".into(),
                })?;
                let save_path = PathBuf::from(&task.save_path);
                // 分片下载支持断点续传：保留已下的部分和进度文件，
                // 从上次断的地方继续（见 hls::progress_path）。
                self.update_state(&id, DownloadState::Downloading)?;
                self.run_hls(id, parsed, save_path);
                Ok(())
            }
            _ => {
                if let Some(hash) = self
                    .torrent_hashes
                    .lock()
                    .map(|map| map.get(&id).cloned())
                    .unwrap_or(None)
                {
                    let slot = Arc::clone(&self.torrents);
                    let download_dir = self.download_dir.clone();
                    self.runtime.spawn(async move {
                        if let Ok(session) = get_torrent_session(&slot, &download_dir).await {
                            let _ = session.resume(&hash).await;
                        }
                    });
                }
                self.update_state(&id, DownloadState::Downloading)
            }
        }
    }

    pub fn remove_task(&self, id: String) -> Result<(), MdmError> {
        let removed = self
            .tasks
            .lock()
            .map(|mut map| map.remove(&id))
            .unwrap_or(None);
        let task = removed.ok_or_else(|| MdmError::NotFound { id: id.clone() })?;

        match task.kind {
            DownloadKind::Http => {
                if let Ok(mut handles) = self.http_handles.lock() {
                    if let Some(dl) = handles.remove(&id) {
                        dl.cancel();
                    }
                }
                // Best effort: drop the partial file and its resume state.
                let save_path = PathBuf::from(&task.save_path);
                let _ = std::fs::remove_file(&save_path);
                let _ = std::fs::remove_file(mdm_engine::state::state_path(&save_path));
            }
            DownloadKind::Hls => {
                if let Ok(mut map) = self.hls_cancels.lock() {
                    if let Some(flag) = map.remove(&id) {
                        flag.store(true, Ordering::SeqCst);
                    }
                }
                let save_path = PathBuf::from(&task.save_path);
                let _ = std::fs::remove_file(&save_path);
                let _ = std::fs::remove_file(hls::progress_path(&save_path));
            }
            _ => {
                if let Some(hash) = self
                    .torrent_hashes
                    .lock()
                    .map(|mut map| map.remove(&id))
                    .unwrap_or(None)
                {
                    let slot = Arc::clone(&self.torrents);
                    let download_dir = self.download_dir.clone();
                    self.runtime.spawn(async move {
                        if let Ok(session) = get_torrent_session(&slot, &download_dir).await {
                            let _ = session.remove(&hash, false).await;
                        }
                    });
                }
            }
        }
        notify_all(&self.tasks, &self.listener);
        Ok(())
    }

    pub fn list_tasks(&self) -> Vec<DownloadTask> {
        self.tasks
            .lock()
            .map(|map| map.values().cloned().collect())
            .unwrap_or_default()
    }
}

impl DownloadEngine {
    fn insert_task(&self, task: DownloadTask) {
        if let Ok(mut map) = self.tasks.lock() {
            map.insert(task.id.clone(), task);
        }
        notify_all(&self.tasks, &self.listener);
    }

    fn update_state(&self, id: &str, state: DownloadState) -> Result<(), MdmError> {
        let mut found = false;
        if let Ok(mut map) = self.tasks.lock() {
            if let Some(task) = map.get_mut(id) {
                task.state = state;
                found = true;
            }
        }
        if !found {
            return Err(MdmError::NotFound { id: id.to_string() });
        }
        notify_all(&self.tasks, &self.listener);
        Ok(())
    }

    /// Start (or resume) a real HTTP/FTP download with the upstream engine,
    /// polling its snapshot and mirroring progress into the task list.
    fn run_http(
        &self,
        task_id: String,
        url: url::Url,
        save_path: PathBuf,
        connections: u32,
        resume: bool,
        shown: u64,
    ) {
        let mut opts = Options::default();
        opts.max_connections = connections.clamp(1, 32);
        let limit = self.speed_limit_bps.lock().map(|g| *g).unwrap_or(0);
        opts.speed_limit = if limit == 0 { None } else { Some(limit) };

        let req = Request {
            url,
            headers: http::HeaderMap::new(),
            dest: Destination::Free(save_path.clone()),
        };

        let tasks = Arc::clone(&self.tasks);
        let listener = Arc::clone(&self.listener);
        let handles = Arc::clone(&self.http_handles);
        self.runtime.spawn(async move {
            // 关键修复：Download::start / resume_or_start 内部用 tokio::spawn
            // 启动下载任务，必须在 tokio 运行时上下文里调用。在 UniFFI 的
            // JNI 线程上直接调用会 panic（"there is no reactor running"），
            // 任务行就永远卡在 0 B/s。移到 spawn 的异步块里就有了运行时上下文。
            log::info!(
                "[HTTP] 启动引擎: url={} dest={:?} 连接数={} resume={}",
                req.url,
                save_path,
                connections,
                resume
            );
            let dl = Arc::new(if resume {
                mdm_engine::download::Download::resume_or_start(
                    mdm_engine::state::state_path(&save_path),
                    req,
                    opts,
                    None,
                    shown,
                )
            } else {
                mdm_engine::download::Download::start(req, opts)
            });
            log::info!("[HTTP] 引擎已启动，开始轮询快照");
            if let Ok(mut h) = handles.lock() {
                h.insert(task_id.clone(), Arc::clone(&dl));
            }

            let mut polls: u64 = 0;
            let mut last_state = String::new();
            loop {
                let snap = dl.snapshot();
                let speed: u64 = snap.connections.iter().map(|c| c.speed).sum();
                polls += 1;
                let state_name = format!("{:?}", snap.state);
                if state_name != last_state {
                    log::info!(
                        "[HTTP] 状态变化: {} -> {} (已下 {} / 总计 {:?})",
                        last_state,
                        state_name,
                        snap.downloaded,
                        snap.size
                    );
                    last_state = state_name;
                }
                if polls % 10 == 0 {
                    log::info!(
                        "[HTTP] 轮询 #{}: 状态={:?} 已下={} 总计={:?} 速度={} 连接数={} 错误={:?}",
                        polls,
                        snap.state,
                        snap.downloaded,
                        snap.size,
                        speed,
                        snap.connections.len(),
                        snap.error
                    );
                }
                let terminal = match &snap.state {
                    EngineState::Done => Some(DownloadState::Completed),
                    EngineState::Failed => Some(DownloadState::Failed),
                    EngineState::Cancelled => Some(DownloadState::Paused),
                    _ => None,
                };
                let error = snap.error.clone();
                let downloaded = snap.downloaded;
                let total = snap.size.unwrap_or(0);
                let file = snap.file.clone();
                update_task(&tasks, &listener, &task_id, |t| {
                    t.downloaded_bytes = downloaded;
                    t.total_bytes = total;
                    t.speed_bytes_per_sec = speed;
                    if let Some(path) = &file {
                        t.save_path = path.to_string_lossy().to_string();
                    }
                    if let Some(state) = terminal {
                        t.state = state;
                        if state == DownloadState::Failed {
                            t.error_message = error
                                .clone()
                                .or_else(|| Some("下载失败".to_string()));
                        }
                        if state == DownloadState::Completed {
                            t.speed_bytes_per_sec = 0;
                        }
                    }
                });
                if terminal.is_some() {
                    log::info!(
                        "[HTTP] 结束: 状态={:?} 已下={} 总计={} 错误={:?}",
                        snap.state,
                        downloaded,
                        total,
                        error
                    );
                    break;
                }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
            if let Ok(mut h) = handles.lock() {
                h.remove(&task_id);
            }
        });
    }

    /// 下载一个 m3u8 视频：用上游打过补丁的 TLS 客户端拉播放列表和分片，
    /// 进度直接写进任务列表。
    fn run_hls(&self, task_id: String, url: url::Url, save_path: PathBuf) {
        let tasks = Arc::clone(&self.tasks);
        let listener = Arc::clone(&self.listener);
        let cancels = Arc::clone(&self.hls_cancels);
        let cancel = Arc::new(AtomicBool::new(false));
        if let Ok(mut map) = cancels.lock() {
            map.insert(task_id.clone(), Arc::clone(&cancel));
        }
        self.runtime.spawn(async move {
            // 复用上游的 TLS 配置：Android 上走纯 Rust WebPKI 校验
            //（rustls-platform-verifier 在这里永远初始化失败，见 #29）。
            let client = match mdm_engine::tls::configure(reqwest::Client::builder())
                .user_agent("MisterDownloadManager/1.0")
                .build()
            {
                Ok(c) => c,
                Err(e) => {
                    fail_task(&tasks, &listener, &task_id, format!("创建网络客户端失败: {e}"));
                    return;
                }
            };
            // 出错自动重试：分片拉取失败（网关抖动）时先自己重试，
            // 每次都靠断点续传进度文件从断的地方接着下；3 次都不行才标失败。
            // 还没下到任何字节就失败的（链接失效、地址不对）不重试，立即报错。
            const HLS_MAX_ATTEMPTS: u32 = 4; // 首次 + 3 次自动重试
            const HLS_RETRY_DELAY_SECS: u64 = 30;
            let mut attempt = 0u32;
            let result: Result<(), String> = loop {
                attempt += 1;
                let r = hls::download_hls(&client, url.as_str(), &save_path, &cancel, |downloaded, total, speed| {
                    update_task(&tasks, &listener, &task_id, |t| {
                        t.downloaded_bytes = downloaded;
                        t.total_bytes = total;
                        t.speed_bytes_per_sec = speed;
                    });
                })
                .await;
                if r.is_ok() {
                    break r;
                }
                let e = r.unwrap_err();
                if e == hls::ERR_CANCELLED {
                    break Err(e);
                }
                // 已下字节数（回调里写进任务了）：0 字节说明播放列表都没拉到，
                // 多半是链接失效，重试也没用；有进度才值得等一会儿再试。
                let downloaded = tasks
                    .lock()
                    .map(|map| map.get(&task_id).map(|t| t.downloaded_bytes).unwrap_or(0))
                    .unwrap_or(0);
                if downloaded == 0 || attempt >= HLS_MAX_ATTEMPTS {
                    break Err(e);
                }
                log::warn!(
                    "[HLS] 第 {attempt} 次尝试失败（已下 {downloaded} 字节），{HLS_RETRY_DELAY_SECS} 秒后自动重试: {e}"
                );
                for _ in 0..HLS_RETRY_DELAY_SECS {
                    if cancel.load(Ordering::Relaxed) {
                        break;
                    }
                    tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                }
                if cancel.load(Ordering::Relaxed) {
                    break Err(hls::ERR_CANCELLED.to_string());
                }
            };
            match result {
                Ok(()) => {
                    update_task(&tasks, &listener, &task_id, |t| {
                        t.state = DownloadState::Completed;
                        t.speed_bytes_per_sec = 0;
                    });
                    log::info!("[HLS] 完成: {:?}", save_path);
                }
                Err(e) if e == hls::ERR_CANCELLED => {
                    update_task(&tasks, &listener, &task_id, |t| {
                        t.state = DownloadState::Paused;
                        t.speed_bytes_per_sec = 0;
                    });
                }
                Err(e) => {
                    fail_task(&tasks, &listener, &task_id, e);
                }
            }
            if let Ok(mut map) = cancels.lock() {
                map.remove(&task_id);
            }
        });
    }

    /// Fetch a magnet's metadata, add it to the BT session, and poll its stats.
    fn run_torrent(&self, task_id: String, src: Source) {
        let tasks = Arc::clone(&self.tasks);
        let listener = Arc::clone(&self.listener);
        let hashes = Arc::clone(&self.torrent_hashes);
        let slot = Arc::clone(&self.torrents);
        let download_dir = self.download_dir.clone();
        let dl_limit = Arc::clone(&self.speed_limit_bps);
        let ul_limit = Arc::clone(&self.upload_limit_bps);

        self.runtime.spawn(async move {
            let session = match get_torrent_session(&slot, &download_dir).await {
                Ok(s) => s,
                Err(e) => {
                    log::error!("BT 会话启动失败: {e}");
                    fail_task(&tasks, &listener, &task_id, e);
                    return;
                }
            };

            // 刚启动的会话也要套用已存的上下行限速
            //（set_*_limit_bps 只对调用时已存在的会话生效）。
            {
                let dl = dl_limit.lock().map(|g| *g).unwrap_or(0);
                let ul = ul_limit.lock().map(|g| *g).unwrap_or(0);
                session.set_download_limit(if dl == 0 { None } else { Some(dl) });
                session.set_upload_limit(if ul == 0 { None } else { Some(ul) });
            }

            log::info!("正在获取种子信息…");
            let meta = match session
                .metadata(&src, Duration::from_secs(120), None)
                .await
            {
                Ok(m) => m,
                Err(e) => {
                    log::error!("获取种子信息失败: {e}");
                    fail_task(&tasks, &listener, &task_id, format!("获取种子信息失败: {e}"));
                    return;
                }
            };
            log::info!("已获取种子信息: {} ({} 字节)", meta.name, meta.total());
            let folder = meta.folder_in(&download_dir);
            update_task(&tasks, &listener, &task_id, |t| {
                t.title = meta.name.clone();
                t.save_path = folder.to_string_lossy().to_string();
                t.total_bytes = meta.total();
            });

            let hash = match session
                .add(&meta.torrent, &download_dir, None, vec![], false)
                .await
            {
                Ok(h) => h,
                Err(e) => {
                    fail_task(&tasks, &listener, &task_id, format!("添加种子失败: {e}"));
                    return;
                }
            };
            if let Ok(mut map) = hashes.lock() {
                map.insert(task_id.clone(), hash.clone());
            }

            let mut polls: u64 = 0;
            loop {
                match session.stats(&hash) {
                    Ok(stats) => {
                        polls += 1;
                        if polls % 10 == 1 {
                            log::info!(
                                "[BT] 轮询 #{}: 阶段={:?} 已下={} 总计={} 速度={}  peers={} 错误={:?}",
                                polls,
                                stats.phase,
                                stats.done,
                                stats.total,
                                stats.down_speed,
                                stats.peers_live,
                                stats.error
                            );
                        }
                        let terminal = match stats.phase {
                            TorrentPhase::Finished => Some(DownloadState::Completed),
                            TorrentPhase::Error => Some(DownloadState::Failed),
                            TorrentPhase::Paused => Some(DownloadState::Paused),
                            _ => None,
                        };
                        let err = stats.error.clone();
                        update_task(&tasks, &listener, &task_id, |t| {
                            t.downloaded_bytes = stats.done;
                            t.total_bytes = stats.total;
                            t.speed_bytes_per_sec = stats.down_speed as u64;
                            t.upload_speed_bytes_per_sec = stats.up_speed as u64;
                            t.active_connections = stats.peers_live as u32;
                            if let Some(state) = terminal {
                                t.state = state;
                                if state == DownloadState::Failed {
                                    t.error_message =
                                        err.clone().or_else(|| Some("BT 下载失败".to_string()));
                                }
                                if state == DownloadState::Completed {
                                    t.speed_bytes_per_sec = 0;
                                }
                            }
                        });
                        if matches!(terminal, Some(DownloadState::Completed | DownloadState::Failed))
                        {
                            break;
                        }
                        // Task deleted from the list: stop polling.
                        let gone = tasks
                            .lock()
                            .map(|map| !map.contains_key(&task_id))
                            .unwrap_or(true);
                        if gone {
                            break;
                        }
                    }
                    Err(_) => break,
                }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        });
    }
}

fn extract_magnet_dn(uri: &str) -> Option<String> {
    uri.split('&')
        .find(|part| part.starts_with("dn="))
        .map(|part| part.trim_start_matches("dn=").replace('+', " "))
}
