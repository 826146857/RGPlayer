# RGPlayer 接手提示词（新会话 / 新电脑先读这份）

> 你是接手 RGPlayer 项目的编码助手。读完本文件再动手，可以立即继续开发，不需要重新摸索。
> 本文最后更新：v2.0.0（libVLC 内核）。

## 1. 项目是什么

RGPlayer 是一款为 **ANBERNIC RG Rotate** 掌机（720×720 方形屏、Android 12、紫光展锐 T618、单扬声器）从零写的本地视频播放器，目标是本地播放体验全面超越 MX Player，且**不依赖任何 MX 组件**。

- 应用包名：`com.rg.player`
- 语言/技术：Java + **libVLC**（`org.videolan.android:libvlc-all:3.6.5`）
- v2.0.0 起已删除 Media3/ExoPlayer 内核
- minSdk 26，targetSdk 34，compileSdk 35
- 构建：AGP 8.6.1 + Gradle 8.7 + JDK 17
- 只打包 `arm64-v8a`，APK 约 69MB（libVLC 占大头）
- 许可证：GPLv3（libVLC 为 LGPLv2.1+，动态链接）

## 2. 工作区与关键路径（Windows）

- 工程根目录：`<workspace>\anbernic\work\player\rgplayer`
- 一键构建：`rgplayer\build.ps1`（内部 `gradle.bat -p <project> --no-daemon assembleDebug`）
- 产物：`app\build\outputs\apk\debug\app-debug.apk`
- 发布目录：`anbernic\work\player\dist`（`RGPlayer-<version>.apk` + `.sha256`）
- 开源仓库镜像：`<workspace>\anbernic\work\player\github\RGPlayer`
- 截图：`anbernic\work\player\shots`
- 设备 serial：`ROTATE03580718`（adb install -r）
- 构建前会 `subst R:` 映射工作区，规避中文路径

## 3. 代码地图

```
app/src/main/java/com/rg/player/
  MainActivity.java       首页（网格/文件夹、下拉刷新、封面、缓存预热）
  VlcPlayerActivity.java  播放页（libVLC）：控制层、手势、菜单、字幕/音轨、缩放旋转、续播、自动下一集
  SettingsActivity.java   设置（比例、媒体目录、内置字体、nomedia、GitHub 更新、说明、清缓存）
  HelpActivity.java       说明页（封面 / 手柄与手势）
  MediaLibrary.java       MediaStore 扫描、目录树、.nomedia/媒体目录过滤、进度与字幕记忆
  DirNode.java            目录树节点
  VideoItem.java          视频条目
  VideoAdapter.java       RecyclerView 适配器 + 时长格式化
  ThumbCache.java         进度预览关键帧条带（内存 + JPEG 缓存）
  CoverLoader.java        封面：同名图 / poster.jpg / 首帧兜底
  Settings.java           SharedPreferences（"rgsettings" / "rgplayer"）
  FontApplier.java        系统字体 / 内置字体切换
  MarqueeTextView.java    标题跑马灯
  NaturalComparator.java  自然排序
  PlaylistHolder.java     Activity 间播放列表情景
```

资源：
- `res/layout/activity_vlc.xml`（播放页：vlc_video / touch_catcher / overlay / btn_reset / preview_panel / menu_layer）
- `res/layout/activity_main.xml`、`activity_settings.xml`、`activity_help.xml`、`item_video.xml`、`item_folder.xml`、`item_help.xml`
- `res/font/builtin.ttf`（31MB 打包字体）
- `res/xml/file_paths.xml`（更新器安装 APK 用 FileProvider）

## 4. 已实现功能

首页：
- MediaStore 扫描、自然排序、单文件夹展开、三列卡片、下拉刷新（真实重扫）
- 封面：同名 jpg/jpeg/png/webp → poster.jpg → 首帧兜底

播放页（VLC）：
- 控制层默认隐藏，单击呼出；双击播放/暂停；3.2s 自动隐藏
- 拖动进度带关键帧预览、进度条跟随
- 左 1/4 亮度、右 1/4 音量；边缘 28dp 横滑退出
- 双指缩放 1–4X（`MediaPlayer.setScale`）；菜单旋转 0/90/180/270°，自动切软解走 transform 滤镜
- 右下角「还原」按钮（缩放/旋转非默认且控制层可见时显示）
- 长按画面左右滑调速 0.5X–4.0X，松手保留
- 菜单：画面比例（自动/铺满/16:9/4:3/原始）、播放速度、旋转、字幕、音轨、视频信息
- 字幕：同名外挂自动加载；内嵌轨道/关闭；手动加载字幕文件；白字描边
- 音轨：多轨切换
- 视频信息：路径/大小/码率/解码与丢帧统计/轨道列表/libVLC 版本（Activity 内 overlay，非 Dialog）
- 断点续播、自动下一集（EndReached 不可靠，用超时长 + 卡住检测兜底）
- 全手柄：A 播放暂停、B 退出、返回键开关菜单、十字左右快退快进、十字上下切集、L1/R1 切集（700ms 防抖）

设置：
- 默认画面比例、媒体目录（SAF）、内置字体开关、隐藏 .nomedia、更新（GitHub Release 检查/下载/安装）、说明、清除缓存

## 5. 关键实现细节与坑（VLC 版）

1. **content:// 地址**：libVLC 打不开 MediaStore 的 content URI。先查 `_data` 真实路径；失败则 `ContentResolver.openFileDescriptor` 并用 `Media(LibVLC, FileDescriptor)`，PFD 要保持打开到播放结束。
2. **SurfaceView 不能缩放**：VLCVideoLayout 内部是 SurfaceView，视图 transform 无效；缩放必须用 `MediaPlayer.setScale(f)`（0 = 自适应）。旋转/平移没有等价接口。
3. **旋转必须软解**：VLC 的 transform 滤镜走 CPU，硬解会绕过；旋转时 `media.setHWDecoderEnabled(false,false)`，并加 `:transform-type=90/180/270` + LibVLC 全局 `--video-filter=transform`。
4. **旋转/还原需要重启媒体**：用 `pendingSeekMs` 记录当前位置，`play(index,false)` 重新 setMedia，Playing 后 seek 回去。
5. **EndReached 经常不触发**：状态会卡在 Playing 且时间超过时长；用 ticker 每 600ms 检测「pos >= dur-250 且 !isPlaying」或「pos 2 秒不变且接近末尾」来触发下一集。
6. **菜单动作 bug**：`dismissMenu()` 会把 menuPick 置空，点击行必须先捕获 `IntConsumer action = menuPick` 再 dismiss，否则所有菜单点了没反应（v2.0.0 已修，勿回退）。
7. **视频信息不要用 Dialog**：SurfaceView 会盖住/影响 Dialog；用 Activity 内 overlay（半透明黑 + 圆角卡片 + ScrollView）并点击关闭。
8. **外挂字幕**：同名文件 `.srt/.ass/.ssa/.vtt/.sub` 通过 media option `:sub-file=<path>` 传入；字幕样式用 `:sub-text-scale=1.4`、`:sub-margin=24`。
9. **字幕/音轨 id**：VLC 用 `-1` 表示 Disable；UI 上显示为「关闭字幕」。
10. **手势层**：touch_catcher 在 vlc_video 之上、overlay 之下；overlay 不可点击时事件可穿透，所以控制层显示时手势仍可用。
11. **设置单击生效**：行不要设 focusableInTouchMode，否则第一次点击只聚焦。
12. **字体切换不要 recreate**：原地遍历控件应用 Typeface。
13. **下拉刷新**：RecyclerView.addOnItemTouchListener 拦截，注意 consumed 返回值避免吞掉卡片点击。
14. **构建 temp 目录**：Windows 下 Gradle 需设置 TMP/TEMP 和 `-Djava.io.tmpdir` 到工作区，否则 `mergeDebugJavaResource` 可能 AccessDenied。
15. **签名**：`app/build.gradle` 使用 `../../github/RGPlayer/keystore/rgplayer.keystore`（密码 rgplayer），保证本地构建与 Release 一致；换签名会导致无法覆盖安装。

## 6. 构建 / 发布

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US'
powershell -NoProfile -ExecutionPolicy Bypass -File "anbernic\work\player\rgplayer\build.ps1"
adb install -r "anbernic\work\player\rgplayer\app\build\outputs\apk\debug\app-debug.apk"
```

版本号在 `app/build.gradle`。
发布：复制 APK 到 `dist\RGPlayer-<version>.apk`，生成 `.sha256`，更新 README 版本记录。

### GitHub 发布（重要）

本机 git 到 github.com 的 443 经常超时，但 `api.github.com` 可用。发布流程用 GitHub REST API：

1. 用 token 认证（`Authorization: Bearer <token>`）
2. 通过 Git Data API 创建 blob/tree/commit 并 PATCH `refs/heads/main` 提交源码（支持 sha=null 删除文件）
3. `POST /repos/826146857/RGPlayer/releases` 创建 release（JSON 必须 UTF-8，否则中文变问号）
4. `POST https://uploads.github.com/.../releases/<id>/assets?name=...` 上传 APK

更新器 API：`https://api.github.com/repos/826146857/RGPlayer/releases/latest`
下载优先用 release asset 的 API URL（`Accept: application/octet-stream`），失败再用 browser_download_url 和 ghproxy/gh-proxy 镜像。

## 7. 当前状态与待办

- 当前版本：**v2.0.0**，VLC 内核，默认播放页即 VlcPlayerActivity
- 已完成：播放、控制层、手势（拖动预览/亮度/音量/长按调速/双指缩放/边缘退出）、菜单（比例/倍速/旋转/字幕/音轨/信息）、断点续播、自动下一集、全手柄
- 回归：HEVC 360p、WMV 实测可播；E06/E18（E-AC3）正常
- 可继续优化：VLC 的 ASS/PGS 字幕样式、旋转时软解性能、菜单滚动、字幕延迟调节、字幕字体跟随设置

## 8. 用户偏好

- 中文交流，少废话，改动要有实测截图/日志
- 讨厌弹窗与遮挡画面的 UI，喜欢半透明、贴边、紧凑
- 要求像素级对齐、颜色统一
- 不要 PiP、A-B、队列页、热力条
- 默认隐藏控制条，单击呼出
- 版本号严格递增，README 记录更新
