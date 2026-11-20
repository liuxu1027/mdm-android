# Mister Download Manager — Android Starter (HTTP/HTTPS + BT/Magnet)

基于 [anefzaoui/misterdownloadmanager](https://github.com/anefzaoui/misterdownloadmanager) 的 Android 移植工程骨架，采用 **Rust (`mdm-engine` + `mdm-torrent`) + UniFFI + Kotlin Jetpack Compose** 架构。

> 本项目遵循上游 **GPL-3.0** 开源协议。

## 目录结构

- `crates/mdm-android/`：Rust FFI 桥接层（UniFFI 导出 HTTP/HTTPS 与 BT/磁力下载接口）
- `android/`：Android 原生工程（Jetpack Compose UI、ForegroundService 后台保活、Share Sheet 与 `magnet:` 链接接管）
- `scripts/apply_upstream_patches.py`：自动为上游仓库应用 [Issue #411](https://github.com/anefzaoui/misterdownloadmanager/issues/411) 中的 Android 交叉编译补丁
- `scripts/build_android.sh`：本地编译 `.so` 与生成 Kotlin UniFFI 绑定脚本
- `.github/workflows/android.yml`：GitHub Actions 云端自动构建 `.apk` 工作流

## 方式一：使用 GitHub Actions 云端自动编译 APK（推荐，无需本地配 NDK/Rust）

1. 在 GitHub 上新建一个仓库，将本工程所有文件推送上去，并把上游仓库作为 submodule 引入：
   ```bash
   git init
   git submodule add https://github.com/anefzaoui/misterdownloadmanager.git upstream
   git add .
   git commit -m "Initial Android starter for Mister Download Manager"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<你的仓库名>.git
   git push -u origin main
   ```
2. 进入 GitHub 仓库的 **Actions** 页面，等待 `Build Android APK` 工作流运行完成。
3. 在构建详情页底部的 **Artifacts** 中下载 `mdm-android-debug-apk` 即可得到可安装的 `.apk`。

> 注：如果仓库尚未包含 Gradle Wrapper（`android/gradlew`），GitHub Actions 工作流会自动安装 Gradle 并执行构建。

## 方式二：本地编译

前置条件：Rust (`rustup target add aarch64-linux-android`)、`cargo-ndk`、Android NDK r26+、LLVM/libclang、JDK 17 与 Android SDK。

```bash
git submodule add https://github.com/anefzaoui/misterdownloadmanager.git upstream
python3 scripts/apply_upstream_patches.py
./scripts/build_android.sh
cd android && gradle assembleDebug
```

## 待对接说明

由于本工程骨架生成时未包含上游 `upstream` 源码，在 `crates/mdm-android/src/lib.rs` 中已为你搭好完整的 **UniFFI 接口、Tokio 运行时、任务状态管理与进度回调骨架**，并标注了接入 `mdm_engine` 与 `mdm_torrent` 内部 API 的位置；拉取 `upstream` 后按注释接入对应方法即可完成全功能联调。
