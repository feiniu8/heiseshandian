# 黑色闪电 · Black Lightning

一个轻量的 GitHub 客户端：按名称搜索项目、按平台分类、加速镜像、断点续传下载，全部界面由单个 WebView 承载，**不依赖 Gradle、AndroidX 或任何第三方库**。

> 本项目只有一个 Activity + 一个 HTML 页面，适合想了解「不用 Android Studio 也能构建 APK」的开发者阅读。

## 功能

- **搜索引擎**：按项目名称搜索（`in:name`），结果按系统分类展示
- **按系统分类**：Android / iOS / Windows / macOS / Linux / Web / 跨平台，依据语言、Topics 与描述自动归类
- **项目详情**：Star、Fork、语言、协议、更新时间、默认分支
- **版本下载**：读取 Releases，列出全部资源（apk/zip/exe…），支持断点续传、暂停 / 继续 / 取消 / 删除 / 安装
- **下载管理**：正在下载 / 已下载 / 已删除三个分组
- **收藏**：项目可收藏 / 取消收藏；取消后进入收藏历史，可重新收藏或删除记录
- **智能搜索**：搜索框支持项目名称、`owner/repo`、项目地址、文件直链；贴项目地址会直接列出可下载文件
- **下载确认**：任何下载先弹确认框，显示文件名、下载通道与地址，确认后才进队列
- **加速源**：内置 20+ 公共 GitHub 镜像，可自动测速优选，也可手动指定；可选填 GitHub 令牌把接口限额从 60 次/时提升到 5000 次/时
- **直达与直链**：一个智能输入框，自动识别项目地址、`owner/repo`、以及任意文件直链
- **站内浏览**：仓库 / Releases 页面在应用内 WebView 打开，可随时用右上角 ↗ 转到系统浏览器
- **软件更新**：启动时每天自动检查一次、也可手动检查；检查地址为 `https://api.github.com/repos/feiniu8/heiseshandian/releases/latest`，更新包同样走加速源下载，发布页可在软件内网页版打开，下载完成后到「下载」页点安装覆盖
- **我（v1.1.1 新增）**：右下角「我」标签页，登录后不是照搬 GitHub 网页，而是一份可整理的个人项目清单，详见下一节

## 「我」页（v1.1.1）

右下角第 6 个标签页。登录后展示的是**整理过的个人项目清单**，不是 GitHub 网页的搬运。

**登录**

1. **OAuth 优先（设备码流程）**：点「用 GitHub 账号登录」→ 显示 8 位口令 → 在 GitHub 授权页输入 → 自动完成登录。需要在 `assets/index.html` 里填一次 OAuth App 的 `client_id`（GitHub → Settings → Developer settings → OAuth Apps → New OAuth App，Authorization callback URL 随便填一个 https 地址即可，设备码流程不用回调）：
   ```js
   var OAUTH_CLIENT_ID='你的client_id';   // 与 DEFAULT_TOKEN 一样，发布包里填死
   ```
   申请 scope：`repo read:user user:email gist`。没有内置 client_id 时按钮会提示改走令牌，也可以在本机粘贴 client_id 临时启用。
2. **令牌兜底**：粘贴 Personal Access Token（classic 勾 `repo`、`read:user`、`gist`）。令牌只写本机 `localStorage`，与「加速源」页共用。
3. **不登录**：可输入任意用户名匿名浏览其公开仓库（不能写）。

**整理（全部只存本机，不碰 GitHub）**：备注、自定义分类（覆盖自动归类）、隐藏 / 含隐藏、五种排序（含自定义上下调序）。「我的 / 组织 / 全部」切换与分类 chips。

**真实写操作**（在「更多操作 ⋯」里，会直接改 GitHub）：改仓库描述、改主页链接、归档 / 解除归档、删除整个仓库（必须手输完整 `owner/repo` 才发请求）、新建仓库。

**文件与上传**：仓库文件浏览器（进目录、看文本、编辑后提交 commit、删文件）；上传支持粘贴文本或选本机文件（原生 `Native.pickLocalFile()` → `window.onPickFile()`，上限 3 MB），同名文件自动带 `sha` 覆盖提交。

> 写操作一律直连 `api.github.com`（加速镜像不代理该域名），所以必须有带 `repo` 权限的登录态；未登录时写接口在本地就被拦下，不会发出请求。

## 测试

```bash
node tests/test_me_page.js      # 39 项：登录态渲染 / 本地整理不发请求 / 写接口 method+path+body+鉴权头 / 删除二次确认 / 上传 sha 逻辑
```

用 Node 的 `vm` + DOM/fetch 桩跑，不需要真机、不发真实请求。

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

本次 1.1.1 随包附带的自签证书（仅供本机安装 / 覆盖升级，不要用于正式发布）：

```bash
keytool -genkeypair -keystore blitz.keystore -alias blitz -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass blacklightning -keypass blacklightning \
  -dname "CN=Black Lightning, OU=heiseidian, O=heiseidian, L=Anshan, ST=Liaoning, C=CN"
./build.sh --sign blitz.keystore blitz blacklightning blacklightning
```

> 注意两点：① `versionCode` / `versionName` 以 `AndroidManifest.xml` 里的值为准，只改 `build.sh` 的默认值不生效；② 若在对象存储 / FUSE 挂载盘（如 ossfs）里工作，`aapt2 compile` 会报 `failed to finish writing data`，把工程拷到本地磁盘（如 `/tmp`）再构建即可。

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
├── tests/test_me_page.js   # 「我」页冒烟测试（node 直接跑）
├── blitz.keystore          # 自签证书（口令 blacklightning / 别名 blitz）
└── build.sh
```

`assets/index.html` 里与「我」页相关的几个全局量：`OAUTH_CLIENT_ID`（发布包里填 OAuth App 的 client_id）、`OAUTH_SCOPES`、`MS`（该页状态）、`ghSend()`（带令牌的写接口，与只读的 `ghFetch()` 分开）。

## 实现说明

- 页面通过虚拟域 `https://appassets.androidplatform.net/index.html` 加载，由 `shouldInterceptRequest` 从 `assets/` 读出并加上 `Access-Control-Allow-Origin: *`，因此页面可以 `fetch` GitHub API。
- 没有使用 `DownloadManager.pauseDownload/resumeDownload`（@hide API 无法编译），下载引擎全部自实现，任务状态持久化到应用私有目录的 `tasks.json`。
- 加速镜像只用于文件下载与网页，**不代理 `api.github.com`**：公共镜像普遍不支持该端点，代理会一直挂起。
- 匿名调用 GitHub API 有 60 次/小时限额，被限流时详情页会回退到「下载源码 ZIP / 打开仓库 / 打开 Releases」。

## 许可证

MIT，见 [LICENSE](LICENSE)。
