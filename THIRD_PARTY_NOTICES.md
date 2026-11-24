# 第三方组件说明

本项目仍遵循 GPL-3.0。Android 构建新增以下组件：

- [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1（[Maven Central 发布物](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1.aar)），GPL-3.0：Android 兼容封装，捆绑 yt-dlp 可执行文件与 Python 运行时。本应用当前仅使用其 `library` 模块。
- [yt-dlp](https://github.com/yt-dlp/yt-dlp)：视频解析与下载程序。其仓库及发布包包含不同许可组件，详细许可见 yt-dlp 仓库的 `LICENSE` 与 `THIRD_PARTY_LICENSES.txt`。

本应用未集成该封装库的可选 FFmpeg 或 aria2c 模块。
