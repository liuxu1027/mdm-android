# Mister Download Manager for Android（非官方移植版）

这是 [anefzaoui/misterdownloadmanager](https://github.com/anefzaoui/misterdownloadmanager) 的**非官方 Android 移植版**，与原作者无关，也不是官方发布的安卓版。

下载引擎（Rust `mdm-engine` + `mdm-torrent`）来自上游；Android 界面（Kotlin Jetpack Compose）、UniFFI 桥接、后台保活等安卓适配是新写的；HLS 下载、断点续传、重试设置等是移植版后加的功能。

本项目遵循 GPL-3.0 开源协议，见 [LICENSE](LICENSE)。

## YouTube 视频下载

添加 YouTube 视频或短链接后，由 `youtubedl-android` 中的 yt-dlp 与 Python 运行时解析并下载；任务保留在 MDM 列表中，支持暂停、继续和重启后恢复。设置中可手动检查 yt-dlp 更新，固定使用 Stable，不会自动切换到 Nightly。该运行时会增加 APK 体积；本项目仅打包 arm64-v8a，未加入可选的 FFmpeg/aria2c 组件。第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 目录结构

- `crates/mdm-android/`：Rust FFI 桥接层（UniFFI 导出 HTTP/HTTPS 与 BT/磁力下载接口）
- `android/`：Android 原生工程（Jetpack Compose UI、ForegroundService 后台保活、Share Sheet 与 `magnet:` 链接接管）
