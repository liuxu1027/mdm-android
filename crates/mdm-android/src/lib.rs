use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
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
    Youtube,
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
    /// HLS 已拉取的分片数 / 总分片数（其它类型恒为 0）：
    /// 任务行用它显示“已拉取 X/Y 分片”。运行时数据，不进 TaskStore。
    pub completed_segments: u32,
    pub total_segments: u32,
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

fn update_task(
    tasks: &TaskMap,
    listener: &ListenerSlot,
    id: &str,
    f: impl FnOnce(&mut DownloadTask),
) {
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

struct TaskSlot {
    active: Arc<AtomicUsize>,
}

impl Drop for TaskSlot {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::SeqCst);
    }
}

async fn acquire_task_slot(
    tasks: &TaskMap,
    listener: &ListenerSlot,
    id: &str,
    maximum: &AtomicUsize,
    active: &Arc<AtomicUsize>,
) -> Option<TaskSlot> {
    loop {
        let queued = tasks
            .lock()
            .ok()
            .and_then(|map| map.get(id).map(|task| task.state == DownloadState::Queued))
            .unwrap_or(false);
        if !queued {
            return None;
        }
        let limit = maximum.load(Ordering::SeqCst);
        let current = active.load(Ordering::SeqCst);
        if current < limit
            && active
                .compare_exchange(current, current + 1, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
        {
            let started = tasks
                .lock()
                .ok()
                .and_then(|mut map| {
                    map.get_mut(id).map(|task| {
                        if task.state == DownloadState::Queued {
                            task.state = DownloadState::Downloading;
                            true
                        } else {
                            false
                        }
                    })
                })
                .unwrap_or(false);
            if started {
                notify_all(tasks, listener);
                return Some(TaskSlot {
                    active: Arc::clone(active),
                });
            }
            active.fetch_sub(1, Ordering::SeqCst);
            return None;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

/// 磁力任务轮询循环的存活标记：resume_task 靠它判断“有没有循环在跑、
/// 能接住 Queued 状态”。循环结束（完成/失败/任务删除/统计出错）时析构摘掉。
struct TorrentLoopGuard {
    loops: Arc<Mutex<HashSet<String>>>,
    id: String,
}

impl Drop for TorrentLoopGuard {
    fn drop(&mut self) {
        if let Ok(mut set) = self.loops.lock() {
            set.remove(&self.id);
        }
    }
}

/// Lazily start the shared BitTorrent session (or return the running one).
async fn get_torrent_session(
    slot: &Arc<tokio::sync::Mutex<Option<Arc<Torrents>>>>,
    download_dir: &PathBuf,
    tasks: &TaskMap,
    hashes: &Arc<Mutex<HashMap<String, String>>>,
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
    // 会话刚建好就对账一次：清掉以前删除失败攒下的孤儿 torrent。
    reconcile_torrent_orphans(&session, tasks, hashes).await;
    Ok(session)
}

/// 会话刚建好时对账：把会话里有、但任务列表里没有的 torrent 清掉。
/// 这些是以前删除失败攒下的孤儿（删任务时只清了 TaskStore/UI，没清会话）。
/// 有任何一个已列出任务的 hash 解析不出来时跳过整轮对账，避免误删。
async fn reconcile_torrent_orphans(
    session: &Arc<Torrents>,
    tasks: &TaskMap,
    hashes: &Arc<Mutex<HashMap<String, String>>>,
) {
    let mut known: HashSet<String> = HashSet::new();
    if let Ok(map) = hashes.lock() {
        known.extend(map.values().map(|h| h.to_ascii_lowercase()));
    }
    let mut underivable = false;
    if let Ok(map) = tasks.lock() {
        for task in map.values() {
            let hash_opt = match task.kind {
                DownloadKind::TorrentMagnet => magnet_info_hash(&task.source_url),
                DownloadKind::TorrentFile => torrent_file_info_hash(&task.source_url),
                _ => continue,
            };
            match hash_opt {
                Some(h) => {
                    known.insert(h);
                }
                None => {
                    underivable = true;
                }
            }
        }
    }
    if underivable {
        log::warn!("[BT] 对账跳过：有已列出任务的 info-hash 解析不出来，怕误删孤儿");
        return;
    }
    for hash in session.hashes() {
        if !known.contains(&hash.to_ascii_lowercase()) {
            log::info!("[BT] 对账：清掉孤儿 torrent {hash}");
            if let Err(e) = session.remove(&hash, false).await {
                log::warn!("[BT] 对账清理 {hash} 失败: {e}");
            }
        }
    }
}

/// 从 magnet 链接里取 info-hash（小写 hex）。btih: 可能是 40 位 hex 或 32 位 base32。
fn magnet_info_hash(uri: &str) -> Option<String> {
    // 第一段是 "magnet:?xt=urn:btih:..."，先把 "magnet:?" 去掉再按 & 切。
    let query = uri.strip_prefix("magnet:?").unwrap_or(uri);
    let xt = query
        .split('&')
        .find_map(|part| part.strip_prefix("xt=urn:btih:"))?;
    let xt = xt.trim();
    if xt.len() == 40 && xt.chars().all(|c| c.is_ascii_hexdigit()) {
        return Some(xt.to_ascii_lowercase());
    }
    if xt.len() == 32 {
        let bytes = decode_base32(xt)?;
        if bytes.len() == 20 {
            return Some(bytes.iter().map(|b| format!("{b:02x}")).collect());
        }
    }
    None
}

/// RFC 4648 base32 解码（只认大写字母表；magnet 的 btih base32 是 32 字符 = 20 字节）。
fn decode_base32(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(20);
    let mut buf: u64 = 0;
    let mut bits: u32 = 0;
    for c in s.chars() {
        let v = match c {
            'A'..='Z' => c as u64 - 'A' as u64,
            '2'..='7' => c as u64 - '2' as u64 + 26,
            _ => return None,
        };
        buf = (buf << 5) | v;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    if bits >= 5 || (buf & ((1u64 << bits) - 1)) != 0 {
        return None;
    }
    Some(out)
}

/// 从 .torrent 文件里取 info-hash：用上游 magnet_link 做本地解析，不用联网。
fn torrent_file_info_hash(path: &str) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    let link = mdm_torrent::magnet_link(&bytes)?;
    magnet_info_hash(&link)
}

/// m3u8 出错自动重试的设置（对标 1DM 的重试设置）。
#[derive(Clone, Copy)]
struct RetrySettings {
    enabled: bool,
    max_attempts: u32, // 重试次数，0 = 无限
    delay_secs: u64,   // 每次重试前的等待秒数
    always: bool,      // 即使 0 字节失败（链接失效等）也重试
}

impl Default for RetrySettings {
    fn default() -> Self {
        Self {
            enabled: true,
            max_attempts: 999999,
            delay_secs: 5,
            always: false,
        }
    }
}

/// 文件名去重：目标文件已存在时，在扩展名前加 4 个随机小写字母
/// （比如 index.ts → index_kxqd.ts），避免后下的把先下完的顶掉。
/// 随机字母来自 UUID，不用再加依赖。
fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let mut path = dir.join(name);
    if !path.exists() {
        return path;
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s.to_string(), format!(".{e}")),
        _ => (name.to_string(), String::new()),
    };
    loop {
        let letters: String = Uuid::new_v4()
            .as_bytes()
            .iter()
            .take(4)
            .map(|b| (b'a' + (b % 26)) as char)
            .collect();
        path = dir.join(format!("{stem}_{letters}{ext}"));
        if !path.exists() {
            return path;
        }
    }
}

/// 用户填的文件名：去掉路径分隔符（否则会跑出下载目录），空的按 None 处理。
fn clean_filename(filename: Option<String>) -> Option<String> {
    filename.and_then(|f| {
        let cleaned: String = f.chars().filter(|c| !matches!(c, '/' | '\\')).collect();
        let cleaned = cleaned.trim().to_string();
        if cleaned.is_empty() {
            None
        } else {
            Some(cleaned)
        }
    })
}

#[derive(uniffi::Object)]
pub struct DownloadEngine {
    runtime: Runtime,
    download_dir: PathBuf,
    tasks: TaskMap,
    listener: ListenerSlot,
    http_handles: Arc<Mutex<HashMap<String, Arc<mdm_engine::download::Download>>>>,
    torrent_hashes: Arc<Mutex<HashMap<String, String>>>,
    /// 正在跑轮询循环的磁力任务 id（见 TorrentLoopGuard）：resume_task 靠它
    /// 判断“有没有循环在跑、能接住 Queued 状态”。
    torrent_loops: Arc<Mutex<HashSet<String>>>,
    hls_cancels: Arc<Mutex<HashMap<String, Arc<AtomicBool>>>>,
    torrents: Arc<tokio::sync::Mutex<Option<Arc<Torrents>>>>,
    default_connections: Arc<Mutex<u32>>,
    speed_limit_bps: Arc<Mutex<u64>>,
    upload_limit_bps: Arc<Mutex<u64>>,
    retry_settings: Arc<Mutex<RetrySettings>>,
    remove_ads: Arc<Mutex<bool>>,
    hls_parallel_segments: Arc<Mutex<u32>>,
    max_concurrent_tasks: Arc<AtomicUsize>,
    active_tasks: Arc<AtomicUsize>,
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
            torrent_loops: Arc::new(Mutex::new(HashSet::new())),
            hls_cancels: Arc::new(Mutex::new(HashMap::new())),
            torrents: Arc::new(tokio::sync::Mutex::new(None)),
            default_connections: Arc::new(Mutex::new(8)),
            speed_limit_bps: Arc::new(Mutex::new(0)),
            upload_limit_bps: Arc::new(Mutex::new(0)),
            retry_settings: Arc::new(Mutex::new(RetrySettings::default())),
            remove_ads: Arc::new(Mutex::new(false)),
            hls_parallel_segments: Arc::new(Mutex::new(8)),
            max_concurrent_tasks: Arc::new(AtomicUsize::new(3)),
            active_tasks: Arc::new(AtomicUsize::new(0)),
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

    /// m3u8 出错自动重试设置：enabled 开关；max_attempts 重试次数，0 = 无限；
    /// delay_secs 每次重试前的等待秒数；always 为 false 时，还没下到任何字节
    /// 就失败的（链接失效、地址不对）不重试。正在下载的任务下次重试时生效。
    pub fn set_retry_settings(
        &self,
        enabled: bool,
        max_attempts: u32,
        delay_secs: u64,
        always: bool,
    ) {
        if let Ok(mut guard) = self.retry_settings.lock() {
            *guard = RetrySettings {
                enabled,
                max_attempts,
                delay_secs,
                always,
            };
        }
    }

    /// m3u8 去广告：下载时同一列表拉两次，两次不一样的分片就是动态插进去
    /// 的广告，跳过不下。默认关。正在下载的任务下次开始时生效。
    pub fn set_remove_ads(&self, enabled: bool) {
        if let Ok(mut guard) = self.remove_ads.lock() {
            *guard = enabled;
        }
    }

    /// m3u8 同时下载的分片数（默认 8，设置里可改 4-16）。正在下载的任务
    /// 下次开始时生效。
    pub fn set_hls_parallel_segments(&self, value: u32) {
        if let Ok(mut guard) = self.hls_parallel_segments.lock() {
            *guard = value.clamp(1, 32);
        }
    }

    /// Maximum number of native downloads allowed to start at once; zero pauses admission.
    pub fn set_max_concurrent_tasks(&self, value: u32) {
        self.max_concurrent_tasks
            .store(value.min(8) as usize, Ordering::SeqCst);
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
                });
            }
        }

        let id = Uuid::new_v4().to_string();
        let resolved_name = clean_filename(filename).unwrap_or_else(|| {
            url.split('/')
                .last()
                .filter(|s| !s.is_empty())
                .unwrap_or("download.bin")
                .split('?')
                .next()
                .unwrap_or("download.bin")
                .to_string()
        });
        // 同名文件已存在时加随机字母，避免后下的把先下完的顶掉。
        let save_path = unique_path(&self.download_dir, &resolved_name);
        let display_name = save_path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| resolved_name.clone());

        let conns = if connections == 0 {
            self.default_connections.lock().map(|g| *g).unwrap_or(8)
        } else {
            connections
        };

        let task = DownloadTask {
            id: id.clone(),
            title: display_name,
            source_url: url.clone(),
            save_path: save_path.to_string_lossy().to_string(),
            kind: DownloadKind::Http,
            state: DownloadState::Queued,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            upload_speed_bytes_per_sec: 0,
            active_connections: conns.clamp(1, 32),
            completed_segments: 0,
            total_segments: 0,
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
                });
            }
        }

        let id = Uuid::new_v4().to_string();
        let base_name = clean_filename(filename).unwrap_or_else(|| {
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
        // 同名文件已存在时加随机字母，避免后下的把先下完的顶掉。
        let save_path = unique_path(&self.download_dir, &resolved_name);
        let display_name = save_path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| resolved_name.clone());

        let task = DownloadTask {
            id: id.clone(),
            title: display_name,
            source_url: url.clone(),
            save_path: save_path.to_string_lossy().to_string(),
            kind: DownloadKind::Hls,
            state: DownloadState::Queued,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            upload_speed_bytes_per_sec: 0,
            active_connections: 0,
            completed_segments: 0,
            total_segments: 0,
            error_message: None,
        };

        self.insert_task(task);
        log::info!("[HLS] 添加任务: {} -> {:?}", resolved_name, save_path);
        self.run_hls(id.clone(), parsed, save_path);

        Ok(id)
    }

    pub fn add_magnet_download(&self, magnet_uri: String) -> Result<String, MdmError> {
        let src = Source::magnet(&magnet_uri).ok_or_else(|| MdmError::InvalidInput {
            reason: "Magnet link must start with magnet:?".into(),
        })?;

        let id = Uuid::new_v4().to_string();
        let display_name =
            extract_magnet_dn(&magnet_uri).unwrap_or_else(|| "Magnet Torrent".into());
        let save_path = self.download_dir.join(&display_name);

        let task = DownloadTask {
            id: id.clone(),
            title: display_name.clone(),
            source_url: magnet_uri.clone(),
            save_path: save_path.to_string_lossy().to_string(),
            kind: DownloadKind::TorrentMagnet,
            state: DownloadState::Queued,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            upload_speed_bytes_per_sec: 0,
            active_connections: 0,
            completed_segments: 0,
            total_segments: 0,
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
            state: DownloadState::Queued,
            total_bytes: 0,
            downloaded_bytes: 0,
            speed_bytes_per_sec: 0,
            upload_speed_bytes_per_sec: 0,
            active_connections: 0,
            completed_segments: 0,
            total_segments: 0,
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
                    let tasks = Arc::clone(&self.tasks);
                    let hashes = Arc::clone(&self.torrent_hashes);
                    self.runtime.spawn(async move {
                        if let Ok(session) =
                            get_torrent_session(&slot, &download_dir, &tasks, &hashes).await
                        {
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
                let parsed =
                    url::Url::parse(&task.source_url).map_err(|_| MdmError::InvalidInput {
                        reason: "无效的 URL".into(),
                    })?;
                let save_path = PathBuf::from(&task.save_path);
                let conns = task.active_connections;
                let shown = task.downloaded_bytes;
                self.update_state(&id, DownloadState::Queued)?;
                self.run_http(id, parsed, save_path, conns, true, shown);
                Ok(())
            }
            DownloadKind::Hls => {
                let parsed =
                    url::Url::parse(&task.source_url).map_err(|_| MdmError::InvalidInput {
                        reason: "无效的 URL".into(),
                    })?;
                let save_path = PathBuf::from(&task.save_path);
                // 分片下载支持断点续传：保留已下的部分和进度文件，
                // 从上次断的地方继续（见 hls::progress_path）。
                self.update_state(&id, DownloadState::Queued)?;
                self.run_hls(id, parsed, save_path);
                Ok(())
            }
            _ => {
                let hash_opt = self
                    .torrent_hashes
                    .lock()
                    .map(|map| map.get(&id).cloned())
                    .unwrap_or(None);
                // hash 在且轮询循环还活着：只改状态，循环里会调 session.resume。
                // 循环已死（重启后恢复的任务没有循环在跑）：不能只改状态，
                // 否则任务会永远卡在 Queued 没人恢复它，得按原链接重新加入会话。
                let loop_alive = self
                    .torrent_loops
                    .lock()
                    .map(|set| set.contains(&id))
                    .unwrap_or(false);
                if hash_opt.is_some() && loop_alive {
                    self.update_state(&id, DownloadState::Queued)?;
                    return Ok(());
                }
                // hash 丢了（重启后恢复），或循环已死：按存的链接重新加入会话，
                // librqbit 会校验已下载的部分接着下。
                let source_url = task.source_url.clone();
                let kind = task.kind;
                let src_opt = match kind {
                    DownloadKind::TorrentMagnet => Source::magnet(&source_url),
                    DownloadKind::TorrentFile => {
                        std::fs::read(&source_url).ok().map(Source::File)
                    }
                    _ => None,
                };
                match src_opt {
                    Some(src) => {
                        self.update_state(&id, DownloadState::Queued)?;
                        self.run_torrent(id, src);
                        return Ok(());
                    }
                    None => {
                        return Err(MdmError::InvalidInput {
                            reason: "无法恢复种子任务：链接无效".into(),
                        });
                    }
                }
            }
        }
    }

    /// 从列表移除任务。delete_files=true 时同时删掉磁盘上的文件
    /// （HTTP 的断点续传状态、HLS 的进度文件也一起删）；false 只从列表移除，文件留着。
    pub fn remove_task(&self, id: String, delete_files: bool) -> Result<(), MdmError> {
        // 先只看任务不删：会话清理失败时返回错误，任务留在列表里可重试。
        // 以前是先删列表再 fire-and-forget 清会话，失败了界面上也显示“已删”，
        // 但 torrent 还在会话里跑，下次启动又恢复回来。
        let task = self
            .tasks
            .lock()
            .map(|map| map.get(&id).cloned())
            .unwrap_or(None)
            .ok_or_else(|| MdmError::NotFound { id: id.clone() })?;

        match task.kind {
            DownloadKind::Http => {
                if let Ok(mut handles) = self.http_handles.lock() {
                    if let Some(dl) = handles.remove(&id) {
                        dl.cancel();
                    }
                }
                if delete_files {
                    // Best effort: drop the partial file and its resume state.
                    let save_path = PathBuf::from(&task.save_path);
                    let _ = std::fs::remove_file(&save_path);
                    let _ = std::fs::remove_file(mdm_engine::state::state_path(&save_path));
                }
            }
            DownloadKind::Hls => {
                if let Ok(mut map) = self.hls_cancels.lock() {
                    if let Some(flag) = map.remove(&id) {
                        flag.store(true, Ordering::SeqCst);
                    }
                }
                if delete_files {
                    let save_path = PathBuf::from(&task.save_path);
                    let _ = std::fs::remove_file(&save_path);
                    let _ = std::fs::remove_file(hls::progress_path(&save_path));
                }
            }
            _ => {
                // 磁力/种子任务：必须把 torrent 从 BT 会话里同步移除，否则删的只是
                // 列表和库行，librqbit 下次启动会从 .torrent-state/SESSION/session.json
                // 里把它恢复回来，后台继续下载（主界面还看不到）。
                // hash 优先从内存 map 取；重启后 map 是空的，就从 magnet 链接的
                // btih: 或种子文件里解析。
                let hash_opt = self
                    .torrent_hashes
                    .lock()
                    .map(|mut map| map.remove(&id))
                    .unwrap_or(None)
                    .or_else(|| magnet_info_hash(&task.source_url))
                    .or_else(|| torrent_file_info_hash(&task.source_url));
                match hash_opt {
                    Some(hash) => {
                        // 同步等 session.remove 完成再返回，失败就报错——不要
                        // fire-and-forget，否则删和没删在界面上看起来一模一样。
                        self.remove_torrent_from_session(&hash, delete_files)?;
                        // session.delete 应该已经清掉了持久化文件；再显式删一次兜底。
                        // 注意：只在删任务时清这两个文件；还在列表里的任务照样存着，
                        // 磁力断点续传就靠它俩。
                        let session_dir =
                            self.download_dir.join(".torrent-state").join("session");
                        let lower = hash.to_ascii_lowercase();
                        let _ = std::fs::remove_file(
                            session_dir.join(format!("{lower}.torrent")),
                        );
                        let _ = std::fs::remove_file(
                            session_dir.join(format!("{lower}.bitv")),
                        );
                    }
                    None => {
                        // 解析不出 hash：这次删不掉会话里的 torrent，
                        // 下次会话启动时的对账会把它当孤儿清掉。
                        log::warn!(
                            "[BT] 删除任务 {id}：解析不出 info-hash，会话里的 torrent 将在下次启动对账时清理"
                        );
                    }
                }
            }
        }
        // 清理成功后才从列表里拿掉任务。
        if let Ok(mut map) = self.tasks.lock() {
            map.remove(&id);
        }
        notify_all(&self.tasks, &self.listener);
        Ok(())
    }

    /// Rename a completed HTTP/HLS download and persist its new path/title.
    /// Active downloads and torrent directories are intentionally left unchanged.
    pub fn rename_task(&self, id: String, new_title: String) -> Result<(), MdmError> {
        let requested = clean_filename(Some(new_title))
            .filter(|name| name != "." && name != ".." && !name.chars().any(char::is_control))
            .ok_or_else(|| MdmError::InvalidInput {
                reason: "文件名无效".into(),
            })?;
        let mut tasks = self.tasks.lock().map_err(|_| MdmError::Engine {
            reason: "任务列表暂不可用".into(),
        })?;
        let task = tasks
            .get(&id)
            .cloned()
            .ok_or_else(|| MdmError::NotFound { id: id.clone() })?;
        if task.state != DownloadState::Completed {
            return Err(MdmError::Engine {
                reason: "请等待下载完成后再重命名".into(),
            });
        }
        if !matches!(task.kind, DownloadKind::Http | DownloadKind::Hls) {
            return Err(MdmError::Engine {
                reason: "当前仅支持重命名已完成的 HTTP/HLS 文件".into(),
            });
        }

        let source = PathBuf::from(&task.save_path);
        if source.parent() != Some(self.download_dir.as_path()) || !source.is_file() {
            return Err(MdmError::Storage {
                reason: "找不到下载文件，无法重命名".into(),
            });
        }
        let destination = source.with_file_name(&requested);
        if source == destination {
            return Ok(());
        }
        if destination.exists() {
            return Err(MdmError::Storage {
                reason: "同名文件已存在，请换一个名称".into(),
            });
        }
        std::fs::rename(&source, &destination).map_err(|e| MdmError::Storage {
            reason: format!("重命名失败: {e}"),
        })?;

        // 文件已完成，不再需要旧名称对应的断点/分片状态文件。
        match task.kind {
            DownloadKind::Http => {
                let _ = std::fs::remove_file(mdm_engine::state::state_path(&source));
            }
            DownloadKind::Hls => {
                let _ = std::fs::remove_file(hls::progress_path(&source));
            }
            _ => {}
        }
        if let Some(updated) = tasks.get_mut(&id) {
            updated.title = requested;
            updated.save_path = destination.to_string_lossy().to_string();
        }
        drop(tasks);
        notify_all(&self.tasks, &self.listener);
        Ok(())
    }

    pub fn list_tasks(&self) -> Vec<DownloadTask> {
        self.tasks
            .lock()
            .map(|map| map.values().cloned().collect())
            .unwrap_or_default()
    }

    /// 启动时恢复一条已保存的任务记录：只插回列表，不启动下载。
    /// 正在下载中的任务恢复为已暂停，由用户点“继续”再下；
    /// 已完成 / 失败的保持原状态。
    pub fn restore_task(&self, task: DownloadTask) -> Result<(), MdmError> {
        let mut task = task;
        if matches!(
            task.state,
            DownloadState::Downloading | DownloadState::Queued
        ) {
            task.state = DownloadState::Paused;
        }
        task.speed_bytes_per_sec = 0;
        task.upload_speed_bytes_per_sec = 0;
        task.active_connections = 0;
        // 磁力/种子任务：把 hash 写回内存 map，否则重启后暂停/删除都找不到它
        // （删除时 session.remove 不执行，就是删了又回来的根因之一）。
        // 注意只写 map，不启动会话；会话启动时由 get_torrent_session 里的对账处理。
        let hash_opt = match task.kind {
            DownloadKind::TorrentMagnet => magnet_info_hash(&task.source_url),
            DownloadKind::TorrentFile => torrent_file_info_hash(&task.source_url),
            _ => None,
        };
        if let Some(hash) = hash_opt {
            if let Ok(mut map) = self.torrent_hashes.lock() {
                map.insert(task.id.clone(), hash);
            }
        }
        self.insert_task(task);
        Ok(())
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
                // 离开 Failed 状态时清掉旧的错误信息：之前这里只改 state，
                // 全仓没有任何地方清 error_message，所以任务点了“继续”、
                // 甚至后来下完了，行上还挂着上次失败的红字。
                // 暂停时清掉也合理：已暂停的任务不应挂着旧错误。
                task.error_message = None;
                found = true;
            }
        }
        if !found {
            return Err(MdmError::NotFound { id: id.to_string() });
        }
        notify_all(&self.tasks, &self.listener);
        Ok(())
    }

    /// 把一个 torrent 从 BT 会话里同步移除（session.remove 会更新 session.json
    /// 持久化），失败时返回错误，调用方不要吞掉。
    /// 只在 Kotlin/JNI 线程上调（remove_task 的调用方），不要在运行时里调：
    /// 里面用了 block_on，在运行时 worker 线程上调会 panic。
    fn remove_torrent_from_session(&self, hash: &str, delete_files: bool) -> Result<(), MdmError> {
        match self.torrents.try_lock() {
            Ok(guard) if guard.is_none() => {
                // 会话没启动：拿着 guard 删持久化文件（别的线程这时启动不了会话）。
                // 等 guard 放掉、会话下次启动时读到 .torrent 文件没了，
                // 上游 prepare_saved 会把 session.json 里这条也清掉，恢复不了。
                // 注意这里不启动会话：只为删一个 torrent 而启动整个会话，
                // 反而会把 session.json 里的孤儿全恢复回来开始下载。
                let session_dir = self.download_dir.join(".torrent-state").join("session");
                let lower = hash.to_ascii_lowercase();
                let _ = std::fs::remove_file(session_dir.join(format!("{lower}.torrent")));
                let _ = std::fs::remove_file(session_dir.join(format!("{lower}.bitv")));
                Ok(())
            }
            _ => {
                // 会话在跑（或正在启动）：同步等 session.remove 完成再返回。
                let slot = Arc::clone(&self.torrents);
                let download_dir = self.download_dir.clone();
                let tasks = Arc::clone(&self.tasks);
                let hashes = Arc::clone(&self.torrent_hashes);
                let hash = hash.to_string();
                self.runtime.block_on(async move {
                    let session = get_torrent_session(&slot, &download_dir, &tasks, &hashes)
                        .await
                        .map_err(|e| MdmError::Engine { reason: e })?;
                    session.remove(&hash, delete_files).await.map_err(|e| {
                        MdmError::Engine {
                            reason: format!("从 BT 会话移除失败: {e}"),
                        }
                    })
                })
            }
        }
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
        let max_concurrent_tasks = Arc::clone(&self.max_concurrent_tasks);
        let active_tasks = Arc::clone(&self.active_tasks);
        self.runtime.spawn(async move {
            let Some(_task_slot) = acquire_task_slot(
                &tasks,
                &listener,
                &task_id,
                &max_concurrent_tasks,
                &active_tasks,
            )
            .await
            else {
                return;
            };
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
                            t.error_message =
                                error.clone().or_else(|| Some("下载失败".to_string()));
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
        let retry_settings = Arc::clone(&self.retry_settings);
        let remove_ads = Arc::clone(&self.remove_ads);
        let hls_parallel_segments = Arc::clone(&self.hls_parallel_segments);
        let max_concurrent_tasks = Arc::clone(&self.max_concurrent_tasks);
        let active_tasks = Arc::clone(&self.active_tasks);
        let cancel = Arc::new(AtomicBool::new(false));
        if let Ok(mut map) = cancels.lock() {
            map.insert(task_id.clone(), Arc::clone(&cancel));
        }
        self.runtime.spawn(async move {
            let Some(_task_slot) = acquire_task_slot(
                &tasks,
                &listener,
                &task_id,
                &max_concurrent_tasks,
                &active_tasks,
            )
            .await
            else {
                if let Ok(mut map) = cancels.lock() {
                    map.remove(&task_id);
                }
                return;
            };
            // 复用上游的 TLS 配置：Android 上走纯 Rust WebPKI 校验
            //（rustls-platform-verifier 在这里永远初始化失败，见 #29）。
            let client = match mdm_engine::tls::configure(reqwest::Client::builder())
                .user_agent("MisterDownloadManager/1.0")
                .build()
            {
                Ok(c) => c,
                Err(e) => {
                    fail_task(
                        &tasks,
                        &listener,
                        &task_id,
                        format!("创建网络客户端失败: {e}"),
                    );
                    return;
                }
            };
            // 出错自动重试（设置里可改，默认开）：分片拉取失败（网关抖动）时先自己重试，
            // 每次都靠断点续传进度文件从断的地方接着下；次数用完才标失败。
            // “始终重试”关着时，还没下到任何字节就失败的（链接失效、地址不对）不重试。
            // 去广告开关和并发数读一次，整次下载（包括重试）都用这个值。
            let remove_ads = remove_ads.lock().map(|g| *g).unwrap_or(false);
            let parallel_segments = hls_parallel_segments.lock().map(|g| *g).unwrap_or(8) as usize;
            let mut attempt = 0u32;
            let result: Result<(), String> = loop {
                attempt += 1;
                let r = hls::download_hls(
                    &client,
                    url.as_str(),
                    &save_path,
                    &cancel,
                    |downloaded, total, speed, completed_segments, total_segments| {
                        update_task(&tasks, &listener, &task_id, |t| {
                            t.downloaded_bytes = downloaded;
                            t.total_bytes = total;
                            t.speed_bytes_per_sec = speed;
                            t.completed_segments = completed_segments;
                            t.total_segments = total_segments;
                        });
                    },
                    remove_ads,
                    parallel_segments,
                )
                .await;
                if r.is_ok() {
                    break r;
                }
                let e = r.unwrap_err();
                if e == hls::ERR_CANCELLED {
                    break Err(e);
                }
                let rs = retry_settings.lock().map(|g| *g).unwrap_or_default();
                if !rs.enabled {
                    break Err(e);
                }
                // 已下字节数（回调里写进任务了）：0 字节说明播放列表都没拉到，
                // 多半是链接失效；除非开了“始终重试”，否则不重试。
                let downloaded = tasks
                    .lock()
                    .map(|map| map.get(&task_id).map(|t| t.downloaded_bytes).unwrap_or(0))
                    .unwrap_or(0);
                if downloaded == 0 && !rs.always {
                    break Err(e);
                }
                // attempt 是已试的次数（含首次）：重试次数 = attempt - 1；
                // max_attempts 为 0 表示无限。
                if rs.max_attempts != 0 && attempt > rs.max_attempts {
                    break Err(e);
                }
                log::warn!(
                    "[HLS] 第 {attempt} 次尝试失败（已下 {downloaded} 字节），{} 秒后自动重试: {e}",
                    rs.delay_secs
                );
                for _ in 0..rs.delay_secs {
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
        let loops = Arc::clone(&self.torrent_loops);
        let slot = Arc::clone(&self.torrents);
        let download_dir = self.download_dir.clone();
        let dl_limit = Arc::clone(&self.speed_limit_bps);
        let ul_limit = Arc::clone(&self.upload_limit_bps);
        let max_concurrent_tasks = Arc::clone(&self.max_concurrent_tasks);
        let active_tasks = Arc::clone(&self.active_tasks);

        self.runtime.spawn(async move {
            // 轮询循环存活标记：resume_task 靠它判断“有没有循环在跑、能接住 Queued”。
            // 循环结束（完成/失败/任务删除/统计出错）时 guard 析构，自动摘掉标记。
            let _loop_guard = TorrentLoopGuard {
                loops,
                id: task_id.clone(),
            };
            let Some(task_slot) = acquire_task_slot(
                &tasks,
                &listener,
                &task_id,
                &max_concurrent_tasks,
                &active_tasks,
            )
            .await
            else {
                return;
            };
            let mut task_slot = Some(task_slot);
            let session = match get_torrent_session(&slot, &download_dir, &tasks, &hashes).await {
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
            let paused_before_poll = tasks
                .lock()
                .ok()
                .and_then(|map| map.get(&task_id).map(|task| task.state == DownloadState::Paused))
                .unwrap_or(true);
            if paused_before_poll {
                let _ = session.pause(&hash).await;
                drop(task_slot.take());
            }

            let mut polls: u64 = 0;
            loop {
                let state = tasks
                    .lock()
                    .ok()
                    .and_then(|map| map.get(&task_id).map(|task| task.state));
                let Some(state) = state else { break };
                if state == DownloadState::Queued && task_slot.is_none() {
                    let Some(next_slot) = acquire_task_slot(
                        &tasks,
                        &listener,
                        &task_id,
                        &max_concurrent_tasks,
                        &active_tasks,
                    )
                    .await
                    else {
                        continue;
                    };
                    if let Err(error) = session.resume(&hash).await {
                        fail_task(&tasks, &listener, &task_id, format!("恢复 BT 下载失败: {error}"));
                        break;
                    }
                    task_slot = Some(next_slot);
                }
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
                                if !(state == DownloadState::Paused && t.state == DownloadState::Queued) {
                                    t.state = state;
                                }
                                if state == DownloadState::Failed {
                                    t.error_message =
                                        err.clone().or_else(|| Some("BT 下载失败".to_string()));
                                }
                                if state == DownloadState::Completed {
                                    t.speed_bytes_per_sec = 0;
                                }
                            }
                        });
                        if terminal == Some(DownloadState::Paused) {
                            drop(task_slot.take());
                        }
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
