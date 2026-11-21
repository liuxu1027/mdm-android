use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Runtime;
use uuid::Uuid;

use mdm_engine::download::{Destination, Options, Request, State as EngineState};
use mdm_torrent::{Config as TorrentConfig, Phase as TorrentPhase, Source, Torrents};

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DownloadKind {
    Http,
    TorrentMagnet,
    TorrentFile,
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
    torrents: Arc<tokio::sync::Mutex<Option<Arc<Torrents>>>>,
    default_connections: Arc<Mutex<u32>>,
    speed_limit_bps: Arc<Mutex<u64>>,
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
            torrents: Arc::new(tokio::sync::Mutex::new(None)),
            default_connections: Arc::new(Mutex::new(8)),
            speed_limit_bps: Arc::new(Mutex::new(0)),
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
            title: resolved_name,
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
        self.run_http(id.clone(), parsed, save_path, conns, false, 0);

        Ok(id)
    }

    pub fn add_magnet_download(&self, magnet_uri: String) -> Result<String, MdmError> {
        let src = Source::magnet(&magnet_uri).ok_or_else(|| MdmError::InvalidInput {
            reason: "Magnet link must start with magnet:?".into(),
        })?;

        let id = Uuid::new_v4().to_string();
        let display_name = extract_magnet_dn(&magnet_uri).unwrap_or_else(|| "Magnet Torrent".into());
        let save_path = self.download_dir.join(&display_name);

        let task = DownloadTask {
            id: id.clone(),
            title: display_name,
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
            if let Ok(mut h) = handles.lock() {
                h.insert(task_id.clone(), Arc::clone(&dl));
            }

            loop {
                let snap = dl.snapshot();
                let speed: u64 = snap.connections.iter().map(|c| c.speed).sum();
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
                    break;
                }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
            if let Ok(mut h) = handles.lock() {
                h.remove(&task_id);
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

        self.runtime.spawn(async move {
            let session = match get_torrent_session(&slot, &download_dir).await {
                Ok(s) => s,
                Err(e) => {
                    log::error!("BT 会话启动失败: {e}");
                    fail_task(&tasks, &listener, &task_id, e);
                    return;
                }
            };

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

            loop {
                match session.stats(&hash) {
                    Ok(stats) => {
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
