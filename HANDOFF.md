# RGPlayer 接手提示词（新会话 / 新电脑先读这份）

> 你是接手 RGPlayer 项目的编码助手。读完本文件再动手，可以立即继续开发，不需要重新摸索。
> 本文最后更新：v1.0.0（首个带在线更新的正式版）。

## 1. 项目是什么

RGPlayer 是一款为 **ANBERNIC RG Rotate** 掌机（720×720 方形屏、Android 12、Unisoc T618、单扬声器）从零写的本地视频播放器，目标是把本地播放体验做到全面超越 MX Player，且**不依赖任何 MX 组件**。

- 应用包名：`com.rg.player`
- 语言/技术：Java + AndroidX Media3 / ExoPlayer 1.5.0
- 解码：`org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1`，解决 AC3 / E-AC3 / DTS 等系统不支持的音频
- 最低/目标 SDK：minSdk 26，targetSdk 34，compileSdk 35
- 构建：AGP 8.6.1 + Gradle 8.7 + JDK 17（JAVA_HOME 需指到 JDK17）

## 2. 工作区与关键路径（Windows）

- 工程根目录：`<workspace>\anbernic\work\player\rgplayer`
- 一键构建脚本：`rgplayer\build.ps1`（内部 `gradle.bat -p <project> --no-daemon assembleDebug`）
- 产物：`app\build\outputs\apk\debug\app-debug.apk`
- 发布目录（不参与 git）：`anbernic\work\player\dist`，命名 `RGPlayer-<version>.apk` + 同名 `.sha256`
- 截图目录：`anbernic\work\player\shots`
- 设备：USB 连接，serial 形如 `ROTATE03580718`；`adb install -r` 安装
- 避免中文路径导致 Gradle/aapt 报错的方法：`subst R: <workspace>` 后用 `R:` 路径构建；本仓库的 build.ps1 已处理路径映射。

## 3. 代码地图

```
app/src/main/java/com/rg/player/
  MainActivity.java      首页（只做网格/文件夹列表、下拉刷新、封面加载）
  PlayerActivity.java    播放页（ExoPlayer、手势、菜单、字幕/音轨、缩放旋转、进度预览）
  SettingsActivity.java  设置页（比例、媒体目录、字体、nomedia、更新、说明、清缓存）
  HelpActivity.java      说明页（两篇：封面 / 手柄与手势）
  MediaLibrary.java      MediaStore 扫描 → 目录树、.nomedia 过滤、媒体目录过滤、字幕/进度记忆
  DirNode.java           目录树节点
  VideoItem.java         视频条目（uri/name/duration/relPath）
  VideoAdapter.java      列表 RecyclerView 适配器 + 时长格式化
  ThumbCache.java        进度条预览用的关键帧条带缓存（内存+磁盘 JPEG）
  CoverLoader.java       封面：同名图片 / poster.jpg / 首帧兜底（跳过纯色、黑帧、彩条）
  Settings.java          SharedPreferences（"rgsettings"）
  FontApplier.java       字体切换（系统字体 / 内置打包字体）
  MarqueeTextView.java   标题跑马灯（头接尾循环、固定间隔）
  NaturalComparator.java 自然排序
  PlaylistHolder.java    Activity 间传递播放列表（避免大对象走 Intent）
androidx/media3/decoder/ffmpeg/LoudnessAnalyzer.java  响度分析（90 分位 → 目标 -18dBFS）
```

资源：
- `res/layout/activity_main.xml`（首页 + 下拉刷新区 `pull_zone`）
- `res/layout/activity_player.xml`（播放器：`player_view`、`touch_catcher`、`overlay`、`btn_reset`、`menu_layer`）
- `res/layout/activity_settings.xml`、`activity_help.xml`、`item_video.xml`、`item_folder.xml`、`item_help.xml`
- `res/font/builtin.ttf`（打包字体，约 31MB，取自用户 Magisk 系统字体 400.ttf）

## 4. 已实现的功能（按当前版本）

首页：
- MediaStore 扫描视频，自然排序；单文件夹自动直接进（不显示外层文件夹）
- 3 列圆角卡片（118dp 封面），显示时长角标
- 下拉刷新：灰色下拉区、"松手刷新"、"刷新中…"，真实重新查询 MediaStore（已验证新增/删除文件生效）
- 封面优先级：同名 .jpg/.jpeg/.png/.webp → 目录 poster.jpg → 首帧（内存临时）
- 空列表提示、缓存预热 ticker

播放页：
- ExoPlayer + FFmpeg 扩展解码（AC3/EAC3/DTS）
- 控制层默认隐藏；单击呼出；3.2s 自动隐藏
- 菜单（右下角）：画面比例 / 播放速度（0.5X–4.0X，大写 X）/ 字幕 / 音轨 / 视频信息；无标题栏
- 字幕：自动加载同名字幕文件（.srt/.ass/.ssa/.vtt/.sub），白色 + 黑描边、无黑底；底距 8%；首帧渲染前透明避免闪现；字幕不随缩放旋转平移
- 字幕/音轨可分别查看当前并切换；"关闭字幕"与"加载字幕文件…"；选择记忆按视频持久化
- 视频信息：半透明黑色弹窗（可透出画面），含容器、视频/音频 codec、分辨率、帧率、码率、色彩、硬解/软解/FFmpeg、字幕轨道、渲染器、播放状态；点任意处关闭
- 倍速：长按画面左右滑 0.5X–4.0X（松手保留，再次长按从当前速度起）；退出播放页恢复 1.0X；自动/手动切集保留倍速与画面比例
- 手势：单击显隐控制；双击暂停/播放；左右滑拖动进度（显示预览与控制层，进度条跟随）；上下滑仅左 1/4 亮度、右 1/4 音量（中间无效）；双指缩放 0.5X–4X（画布式线性，无跳变）+ 旋转 + 自由平移；边缘 28dp 内横滑直接退出
- 长按播放键 = 菜单；返回键 = 打开/关闭菜单；Start/Menu/Select 不绑定；B 退出；十字左右快退/快进 10s；十字上下上一部/下一部；L1/R1 切集（700ms 防抖）
- 「↺ 还原」按钮：缩放/旋转/平移后，控制层显示时出现在进度条上方右下角，点半透明胶囊可复位 100%/0°/居中，控制层隐藏时一起隐藏
- 无 PiP、无 A-B 循环、无队列页、无排版切换按钮、无热力条（曾实现后按用户要求删除）

设置：
- 默认画面比例（原画/沉浸）
- 媒体目录（SAF 选择；长按恢复全部）
- 内置字体开关（开=打包字体，关=系统字体；默认开；原地应用不闪屏）
- 隐藏 .nomedia 目录开关（说明：目录含 .nomedia 的视频不扫描）
- 更新（v1.0 起：GitHub 检查 → 下载到 Download/RGPlayer-<版本>.apk → 安装）
- 说明、清除缓存（无内容时提示「缓存干净得能当镜子照了 ✨」）
- 底部显示版本号；一屏显示、无滚动

## 5. 关键实现细节与坑

1. **Gradle 中文路径**：会因 aapt/apktool 编码失败，务必 `subst` 到 ASCII 盘符再构建。
2. **PlayerView 表面类型**：必须 `app:surface_type="texture_view"`，否则视频缩放不生效（SurfaceView 不响应视图变换）。
3. **缩放/旋转/平移**：变换施加在 PlayerView 的 `AspectRatioFrameLayout` 子视图上；字幕做反向补偿（1/scale、-rotation、-translation）保持屏幕固定；不要用 resize_mode 自动切换（会跳变）。
4. **字幕首帧闪现**：play() 时 subtitleView alpha=0，`onRenderedFirstFrame` 再淡入。
5. **外挂字幕自动加载**：`MediaLibrary.subtitle()` 记忆优先，其次同名 `.srt/.ass/.ssa/.vtt/.sub`。
6. **手势层**：`touch_catcher` 覆盖在 player_view 上、overlay 之下；overlay 不可点击时事件穿透，所以控制层显示时手势仍可用。
7. **边缘返回**：`edgeGesture` 命中左右 28dp 且横滑 >48dp 直接 finish（不依赖系统手势）。
8. **索引/换集**：`switchBy()` 700ms 防抖；退出最后一集直接 finish。
9. **下拉刷新**：`RecyclerView.addOnItemTouchListener` 拦截，不能依赖孩子 onTouch；返回 consumed 的时机要防止吞掉卡片点击。
10. **设置页单击生效**：行不要设 `focusableInTouchMode`，否则第一次点击只聚焦不触发。
11. **说明页/设置页编码**：历史上有文件被 PowerShell 读写弄坏过；本仓库文件均为 UTF-8。改文件优先用 write/edit，不要用 `Set-Content` 默认编码。
12. **adb 手势坐标**：`input motionevent` 坐标约为应用坐标 2×，注入边缘/双指手势时注意。
13. **更新器**：GitHub API 必须带 `User-Agent`；下载文件写入系统 Download（MediaStore，API29+），命名 `RGPlayer-<版本>.apk`；安装前检查 `canRequestPackageInstalls`。

## 6. 构建 / 发布流程

```powershell
# 在 workspace 根目录
$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US'
powershell -NoProfile -ExecutionPolicy Bypass -File "anbernic\work\player\rgplayer\build.ps1"
adb install -r "anbernic\work\player\rgplayer\app\build\outputs\apk\debug\app-debug.apk"
```

版本号在 `app/build.gradle` 的 `versionCode` / `versionName`。
发布：复制 APK 到 `dist\RGPlayer-<version>.apk`，生成 `.sha256`，更新 README 版本记录。
GitHub Release：tag `v<version>`，上传 APK 作为 asset，更新器读取 `releases/latest` 的第一个 asset。

## 7. 当前状态与待办

- 当前版本：**v1.0.0**
- 更新器 API：`https://api.github.com/repos/826146857/RGPlayer/releases/latest`
- 仓库自带签名 keystore：`keystore/rgplayer.keystore`（密码 rgplayer），保证 Release 与本地构建签名一致；换签名会导致无法覆盖安装
- GitHub：https://github.com/826146857/RGPlayer
- 字体文件较大（31MB），如仓库过大可考虑改为单独发布 / LFS，但要同步修改 `FontApplier`
- 可考虑：字幕字体随设置字体、更多手势、播放列表页（用户目前明确不要）

## 8. 用户偏好（重要）

- 中文交流；喜欢直接、少废话，改动要有实测截图/日志
- 讨厌弹窗和遮挡画面的 UI，喜欢半透明、贴边、紧凑
- 要求像素级对齐、颜色统一、细节一致
- 不要 PiP、A-B、队列页、热力条
- 默认隐藏控制条，单击呼出
- 更新日志写在 README，版本号严格递增
