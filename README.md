# 黄果短剧 Android

> 非官方 Android 客户端 · 原生播放器 + 轻量 WebView 界面
> 当前版本 **v0.11.3**（versionCode 90）

黄果短剧把短剧的浏览、搜索、收藏、观看整合在一个应用里：界面用 WebView 渲染以保持迭代速度，播放、缓存、封面处理等重活全部交给原生层。应用只读取第三方站点的公开目录与视频地址，不提供任何内容源。

> 本项目仅供个人学习与技术研究，内容版权归原站点所有。应用内含成人内容，使用前需确认你已达到所在地法定年龄。

## 界面预览

| 首页 | 推荐流 |
|---|---|
| ![首页](docs/screenshots/home.png) | ![推荐流](docs/screenshots/feed.png) |

## 功能特性

### 找剧

- 首页焦点轮播、精选推荐、最近上新；剧场与 AI 专区支持分类浏览与连续加载
- 按剧名搜索，或输入 `#标签名` 找同类作品
- 专题、排行榜（评分 / 热度）、标签聚合页
- 收藏与观看历史本地留存，登录后可与云端同步

### 播放

- **推荐流**：竖滑切换剧集，带预热实例，切集无黑屏
- **倍速**：0.75× / 1.0× / 1.25× / 1.5× / 2.0×，推荐流与播放页共用一套档位
- **全屏**：推荐流内直接横屏全屏，退出恢复竖屏
- 进度拖动（横滑精细调节）、暂停标记、集数切换、续播到上次位置
- 竖屏资源保持竖屏，横屏资源自动横屏

### 稳定性与性能

- **三级网络回退**：原生 → Cronet（真实 Chrome TLS 指纹）→ WebView，整条链路有 25 秒总预算，不会无限挂起
- **站点自动发现**：启动时解析最新可用地址并测速，失效后自动更换
- **封面管线**：下载后下采样到 360px（列表）/ 720px（大图）并转 WebP，单张从 3.5 MB 降到 ~30 KB
- **缓存可视化**：视频分段、封面、目录三类占用在设置里实时可见，一键清理

## 技术架构

```
WebView (HTML/CSS/JS)  ── JS Bridge ──  MainActivity
      │                                    │
      ├── 路由/渲染/状态                    ├── SiteRepository（Jsoup 解析）
      │                                    ├── 网络三级回退
      │                                    ├── 封面缓存（下采样 + WebP）
      │                                    └── 账号同步
      │
      └── 推荐流（原生层叠加）
             ├── Media3 / ExoPlayer ×3（主 + 双预热）
             ├── 分段缓存（LRU，上限 min(2GB, 可用空间/3)）
             └── 预热播放器小缓冲（5s / 4MB），接管后恢复正常缓冲
```

关键实现点：

- **播放缓存**：`SimpleCache` + `LeastRecentlyUsedCacheEvictor`，缓存命中时切集与重进几乎零等待
- **封面缓存**：`BitmapFactory` 采样 → `createScaledBitmap` → WebP(q82) 落盘；缓存键包含目标宽度，改规格不会读到旧图
- **容错**：缓存目录被系统清空后自动重建；磁盘写满时释放视频缓存重试，仍失败则安静降级为文字占位
- **超时体系**：每个 bridge 请求有独立超时（8–60s），页面先渲染再取数，网络异常不会让界面卡在上一页

## 构建

环境要求：

- JDK 17+
- Android SDK 34（compileSdk 34 / minSdk 24）
- Gradle 8.x（已附带 wrapper）

```bash
git clone https://github.com/HFPlayAI/HuangguoAPP.git
cd HuangguoAPP
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

命令行构建时指定 SDK 路径：

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
./gradlew :app:assembleDebug
```

## 下载

前往 [Releases](https://github.com/HFPlayAI/HuangguoAPP/releases) 下载最新 APK，直接安装即可（需要允许「安装未知来源应用」）。

## 版本记录

### v0.11.3

- 修复缓存分区写满后系统清空缓存目录、封面永久失效的问题
- 封面目录自动重建，磁盘写满时让出视频缓存并重试
- 封面加载失败自动重试，不再需要重启应用

### v0.11.2

- 收敛网络三级回退预算（单请求最坏从 4 分钟降到 25 秒）
- 去掉同一主机的重复原生尝试
- 封面请求独立线程池，不再挤占页面请求

### v0.11.0

- 推荐流新增暂停标记、全屏按钮、倍速菜单
- 倍速档位统一为 0.75× / 1.0× / 1.25× / 1.5× / 2.0×
- 修复倍速面板点击无效、推荐页路由死锁

### v0.10.0

- 封面缓存重构：下采样 + WebP 转码 + 流式输出，体积下降约 97%

## 目录结构

```
app/src/main/java/com/huangguo/mobile/   原生层
  ├── MainActivity.java                  WebView 宿主与 JS Bridge
  ├── FeedView.java                      推荐流（播放器 / 交互 / 埋点）
  ├── PlayerActivity.java                独立播放页
  ├── SiteRepository.java                站点解析与网络回退
  ├── VideoPlaybackCache.java            视频分段缓存
  └── WebViewFetcher.java                浏览器通道兜底
app/src/main/assets/                     界面层（app.js / app.css / sites 配置）
qa/                                      真机回归用例说明
```

## 免责声明

本项目为个人学习用途的非官方客户端，与任何内容提供方无关。所有剧集、封面、视频均来自第三方站点，本仓库不存储也不分发任何内容。请遵守所在地法律法规，支持正版。
