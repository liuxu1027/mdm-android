use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use uuid::Uuid;

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

#[derive(uniffi::Object)]
pub struct DownloadEngine {
    runtime: Runtime,
    download_dir: PathBuf,
    tasks: Arc<Mutex<HashMap<String, DownloadTask>>>,
    listener: Arc<Mutex<Option<Box<dyn ProgressListener>>>>,
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
        }))
    }

    pub fn set_listener(&self, listener: Option<Box<dyn ProgressListener>>) {
        if let Ok(mut guard) = self.listener.lock() {
            *guard = listener;
        }
        self.notify_all();
    }

    pub fn add_http_download(
        &self,
        url: String,
        filename: Option<String>,
        connections: u32,
    ) -> Result<String, MdmError> {
        if !url.starts_with("http://") && !url.starts_with("https://") {
            return Err(MdmError::InvalidInput {
                reason: "URL must start with http:// or https://".into(),
            });
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
            active_connections: connections.clamp(1, 32),
            error_message: None,
        };

        self.insert_task(task);

        let tasks_ref = Arc::clone(&self.tasks);
        let listener_ref = Arc::clone(&self.listener);
        let task_id = id.clone();

        self.runtime.spawn(async move {
            // TODO: 在此处调用 upstream::mdm_engine 的分段下载器启动真实下载，
            // 并定时更新 tasks_ref 中的 downloaded_bytes / total_bytes / speed_bytes_per_sec。
            let _ = (url, save_path, tasks_ref, listener_ref, task_id);
        });

        Ok(id)
    }

    pub fn add_magnet_download(&self, magnet_uri: String) -> Result<String, MdmError> {
        if !magnet_uri.starts_with("magnet:?") {
            return Err(MdmError::InvalidInput {
                reason: "Magnet link must start with magnet:?".into(),
            });
        }

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

        let tasks_ref = Arc::clone(&self.tasks);
        let listener_ref = Arc::clone(&self.listener);
        let task_id = id.clone();

        self.runtime.spawn(async move {
            // TODO: 在此处调用 upstream::mdm_torrent (librqbit) 添加磁力链接，
            // 并把 peers 数映射到 active_connections，同步进度到 tasks_ref。
            let _ = (magnet_uri, save_path, tasks_ref, listener_ref, task_id);
        });

        Ok(id)
    }

    pub fn add_torrent_file(&self, torrent_path: String) -> Result<String, MdmError> {
        let path = PathBuf::from(&torrent_path);
        if !path.exists() {
            return Err(MdmError::Storage {
                reason: format!("Torrent file not found: {torrent_path}"),
            });
        }

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

        self.runtime.spawn(async move {
            // TODO: 在此处调用 upstream::mdm_torrent 解析本地 .torrent 文件并启动做种/下载。
            let _ = path;
        });

        Ok(id)
    }

    pub fn pause_task(&self, id: String) -> Result<(), MdmError> {
        self.update_state(&id, DownloadState::Paused)
    }

    pub fn resume_task(&self, id: String) -> Result<(), MdmError> {
        self.update_state(&id, DownloadState::Downloading)
    }

    pub fn remove_task(&self, id: String) -> Result<(), MdmError> {
        let removed = self
            .tasks
            .lock()
            .map(|mut map| map.remove(&id))
            .unwrap_or(None);
        if removed.is_none() {
            return Err(MdmError::NotFound { id });
        }
        self.notify_all();
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
        self.notify_all();
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
        self.notify_all();
        Ok(())
    }

    fn notify_all(&self) {
        let snapshot = self.list_tasks();
        if let Ok(guard) = self.listener.lock() {
            if let Some(cb) = guard.as_ref() {
                cb.on_tasks_updated(snapshot);
            }
        }
    }
}

fn extract_magnet_dn(uri: &str) -> Option<String> {
    uri.split('&')
        .find(|part| part.starts_with("dn="))
        .map(|part| part.trim_start_matches("dn=").replace('+', " "))
}
