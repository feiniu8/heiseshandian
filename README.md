# 黑色闪电 · Black Lightning

一个轻量的 GitHub 客户端：按名称搜索项目、按平台分类、加速镜像、断点续传下载，全部界面由单个 WebView 承载，**不依赖 Gradle、AndroidX 或任何第三方库**。

> 本项目只有一个 Activity + 一个 HTML 页面，适合想了解「不用 Android Studio 也能构建 APK」的开发者阅读。

## 功能

- **搜索引擎**：按项目名称搜索（`in:name`），结果按系统分类展示
- **按系统分类**：Android / iOS / Windows / macOS / Linux / Web / 跨平台，依据语言、Topics 与描述自动归类
- **项目详情**：Star、Fork、语言、协议、更新时间、默认分支
- **版本下载**：读取 Releases，列出全部资源（apk/zip/exe…），支持断点续传、暂停 / 继续 / 取消 / 删除 / 安装
- **下载管理**：正在下载 / 已下载 / 已删除三个分组
- **加速源**：内置 20+ 公共 GitHub 镜像，可自动测速优选，也可手动指定；可选填 GitHub 令牌把接口限额从 60 次/时提升到 5000 次/时
- **直达与直链**：一个智能输入框，自动识别项目地址、`owner/repo`、以及任意文件直链
- **站内浏览**：仓库 / Releases 页面在应用内 WebView 打开，可随时用右上角 ↗ 转到系统浏览器

## 构建

环境要求：JDK 8+、Android SDK（build-tools 34.0.0、platform android-34）。无需 Gradle。

```bash
export ANDROID_SDK=/path/to/android-sdk     # 含 build-tools/34.0.0 与 platforms/android-34
export JAVA_HOME=/path/to/jdk
./build.sh                                   # 产出 build/black-lightning-unsigned.apk
```

签名（可选，自己生成密钥）：

```bash
./build.sh --sign /path/to/release.jks blitz yourpass yourpass
# 产出 build/black-lightning-signed.apk
```

构建链要点（脚本里已处理）：

1. `aapt2 compile --dir res -o build/res.zip`
2. `aapt2 link` 链接资源与 `assets/`，同时写入 versionCode / versionName
3. `javac` 编译 Java（`-bootclasspath android.jar`）
4. `d8` 生成 dex（输出目录必须事先存在）
5. 把 `classes.dex` 以 **STORED** 方式塞进 APK，`resources.arsc` 同样保持不压缩
6. `zipalign -p 4` 对齐，`apksigner` 签名（v1/v2/v3 全开）

## 目录结构

```
.
├── AndroidManifest.xml
├── assets/
│   ├── index.html          # 全部 UI / CSS / JS（单文件）
│   └── logo.jpg            # 首页左上角配图（占位图，可自行替换）
├── java/com/heiseidian/lightning/
│   ├── MainActivity.java   # WebView 宿主、assets 拦截、站内浏览器
│   ├── Bridge.java         # JS ↔ 原生桥（Native.*）
│   ├── Downloader.java     # RandomAccessFile + Range 断点续传
│   ├── DownloadService.java# 前台服务与通知
│   └── FilesProvider.java  # FileProvider，供安装 APK
├── res/                    # 图标与字符串
└── build.sh
```

## 实现说明

- 页面通过虚拟域 `https://appassets.androidplatform.net/index.html` 加载，由 `shouldInterceptRequest` 从 `assets/` 读出并加上 `Access-Control-Allow-Origin: *`，因此页面可以 `fetch` GitHub API。
- 没有使用 `DownloadManager.pauseDownload/resumeDownload`（@hide API 无法编译），下载引擎全部自实现，任务状态持久化到应用私有目录的 `tasks.json`。
- 加速镜像只用于文件下载与网页，**不代理 `api.github.com`**：公共镜像普遍不支持该端点，代理会一直挂起。
- 匿名调用 GitHub API 有 60 次/小时限额，被限流时详情页会回退到「下载源码 ZIP / 打开仓库 / 打开 Releases」。

## 许可证

MIT，见 [LICENSE](LICENSE)。
