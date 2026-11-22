# RGPlayer

为 **ANBERNIC RG Rotate**（720×720 方屏、Android 12、紫光展锐 T618）从零编写的本地视频播放器。

目标：在掌机上把本地播放体验做到比 MX Player 更顺手 —— 无广告、无联网、全手柄操作、深色界面、纯本地媒体库。

**v2.0.0 起播放内核切换到 libVLC**（VLC for Android 的 libVLC 3.6.5），兼容 RMVB / WMV / HEVC / 多音轨 / 多字幕等格式，替代原来的 ExoPlayer 内核。

## 特性

- **本地媒体库**：MediaStore 扫描，自然排序，三列圆角卡片；单文件夹自动展开；下拉刷新真实重扫
- **封面**：同名图片 / `poster.jpg`，找不到时临时取首帧（跳过纯色、黑帧、彩条）
- **播放内核**：libVLC，硬件解码优先、失败软解回退，支持 HEVC / WMV / RMVB / AV1 等更多格式
- **字幕**：自动加载同名外挂字幕（`.srt` `.ass` `.ssa` `.vtt` `.sub`），白字描边；内嵌多字幕轨道可选；可手动加载字幕文件
- **音轨**：多音轨切换，选择记忆
- **手势**：单击呼出控制；双击播放/暂停；左右拖动进度（实时预览，进度条跟随）；左 1/4 上下调亮度、右 1/4 调音量；双指缩放 1X–4X；边缘横滑退出
- **长按调速**：0.5X–4.0X，松手保留，再次长按从当前速度继续
- **画面**：自动 / 铺满 / 16:9 / 4:3 / 原始；旋转 0°/90°/180°/270°；右下角「还原」一键复位缩放与旋转
- **视频信息**：半透明弹窗，展示真实路径、大小、码率、解码帧/丢帧、音视频字幕轨道、libVLC 版本
- **全手柄**：A 播放暂停、B 退出、返回键开关菜单、十字左右快退快进、十字上下切集、L1/R1 切集（防抖）
- **字体**：系统字体 / 内置字体切换
- **设置**：画面比例、媒体目录、内置字体、隐藏 `.nomedia`、更新、说明、清除缓存

## 下载

在 [Releases](https://github.com/826146857/RGPlayer/releases) 下载最新的 `RGPlayer-<版本>.apk` 安装即可（Android 12 掌机，免 root）。

> v2.0.0 更换了签名密钥，如果你装过 1.x 版本，需要先卸载旧版再安装。

## 构建

环境：JDK 17、Android SDK（compileSdk 35）、Gradle 8.7（仓库自带 wrapper）。

```bash
git clone https://github.com/826146857/RGPlayer.git
cd RGPlayer
echo sdk.dir=/path/to/android-sdk > local.properties
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（仅打包 arm64-v8a，约 69MB，其中 libVLC 约 55MB）。

### Windows 中文路径提示

如果工程路径含中文，Gradle/aapt 可能报错。可先映射盘符再构建：

```powershell
subst R: C:\你的路径
gradlew.bat -p R:\RGPlayer assembleDebug
```

## 项目结构

```
app/src/main/java/com/rg/player/
  MainActivity.java      首页（网格/文件夹、下拉刷新、封面）
  VlcPlayerActivity.java 播放页（libVLC、手势、菜单、字幕/音轨、缩放旋转）
  SettingsActivity.java  设置页（比例、目录、字体、nomedia、更新、说明、清缓存）
  HelpActivity.java      说明页
  MediaLibrary.java      媒体扫描、目录树、隐藏规则、记忆
  ThumbCache.java        进度预览关键帧缓存
  CoverLoader.java       封面解析
  FontApplier.java       字体切换
  MarqueeTextView.java   标题跑马灯
  ...
```

## 技术栈

- Java + libVLC（`org.videolan.android:libvlc-all:3.6.5`，arm64-v8a）
- minSdk 26 / targetSdk 34 / compileSdk 35
- AGP 8.6.1、Gradle 8.7

## 版本记录

### v2.0.0
- 播放内核切换为 libVLC（替换 Media3/ExoPlayer），兼容性大幅提升：HEVC、WMV、RMVB、多音轨、多字幕等
- 缩放（1–4X）、旋转（0/90/180/270°）、还原
- 自动加载同名外挂字幕、内嵌字幕/音轨切换、字幕关闭
- 视频信息弹窗：真实路径、大小、输入码率、解码/丢帧统计、轨道列表
- 保留原有交互：控制层、手势、长按调速、亮度音量、断点续播、自动下一集、全手柄
- 移除 Media3 依赖，APK 69.1MB

### v1.0.0
- 加入 GitHub 更新检查：检测 → 下载到 `Download/RGPlayer-<版本>.apk` → 安装

### v0.48.3
- 首个开源版本（不含在线更新）

## 说明

- 本项目使用 VLC 的 libVLC（LGPLv2.1+）作为动态链接库；应用整体以 GPLv3 发布
- 内置字体取自作者自用系统字体，仅作可选内置；可在设置中切回系统字体
- 本项目与 MX Player 无任何关系，也不包含其代码

## License

GPLv3，详见 [LICENSE](LICENSE)。
