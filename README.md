# Mister Download Manager for Android（非官方移植版）

这是 [anefzaoui/misterdownloadmanager](https://github.com/anefzaoui/misterdownloadmanager) 的**非官方 Android 移植版**，与原作者无关，也不是官方发布的安卓版。

下载引擎（Rust `mdm-engine` + `mdm-torrent`）来自上游；Android 界面（Kotlin Jetpack Compose）、UniFFI 桥接、后台保活等安卓适配是新写的；HLS 下载、断点续传、重试设置等是移植版后加的功能。

本项目遵循 GPL-3.0 开源协议，见 [LICENSE](LICENSE)。

## 目录结构

- `crates/mdm-android/`：Rust FFI 桥接层（UniFFI 导出 HTTP/HTTPS 与 BT/磁力下载接口）
- `android/`：Android 原生工程（Jetpack Compose UI、ForegroundService 后台保活、Share Sheet 与 `magnet:` 链接接管）
