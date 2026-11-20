# Mister Download Manager — Android Starter (HTTP/HTTPS + BT/Magnet)

基于 [anefzaoui/misterdownloadmanager](https://github.com/anefzaoui/misterdownloadmanager) 的 Android 移植工程骨架，采用 **Rust (`mdm-engine` + `mdm-torrent`) + UniFFI + Kotlin Jetpack Compose** 架构。

> 本项目遵循上游 **GPL-3.0** 开源协议。

## 目录结构

- `crates/mdm-android/`：Rust FFI 桥接层（UniFFI 导出 HTTP/HTTPS 与 BT/磁力下载接口）
- `android/`：Android 原生工程（Jetpack Compose UI、ForegroundService 后台保活、Share Sheet 与 `magnet:` 链接接管）
