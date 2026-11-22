# RGPlayer

为 **ANBERNIC RG Rotate**（720×720 方屏、Android 12、紫光展锐 T618）从零编写的本地视频播放器。

目标：在掌机上把本地播放体验做到比 MX Player 更顺手 —— 无广告、无联网、全手柄操作、深色界面、纯本地媒体库。

## 特性

- **本地媒体库**：MediaStore 扫描，自然排序，三列圆角卡片；单文件夹自动展开；下拉刷新真实重扫
- **封面**：同名图片 / `poster.jpg`，找不到时临时取首帧（跳过纯色、黑帧、彩条）
- **播放**：ExoPlayer + FFmpeg 扩展解码（AC3 / E-AC3 / DTS 等）
- **字幕**：自动加载同名字幕文件（`.srt` `.ass` `.ssa` `.vtt` `.sub`），白色描边无黑底；首帧前不闪现；跟随切换记忆
- **音轨**：手选并记忆；字幕与音轨分开展示当前项
- **手势**：单击呼出控制；双击播放/暂停；左右拖动进度（实时预览，进度条跟随）；左 1/4 上下调亮度、右 1/4 调音量；双指缩放 0.5X–4X + 旋转 + 自由平移；边缘横滑退出
- **长按调速**：0.5X–4.0X，松手保留，再次长按从当前速度继续；退出播放页恢复 1.0X
- **画面**：原画 / 沉浸裁剪；缩放旋转平移后可一键「还原」
- **视频信息**：半透明弹窗，含容器、编解码器、分辨率、帧率、码率、色彩、硬解/软解/FFmpeg、字幕轨道、渲染器
- **全手柄**：A 播放暂停、B 退出、返回键开关菜单、十字左右快退快进、十字上下切集、L1/R1 切集（防抖）
- **字体**：可切换系统字体 / 内置字体
- **设置**：画面比例、媒体目录、内置字体、隐藏 `.nomedia`、更新、说明、清除缓存

## 下载

在 [Releases](https://github.com/826146857/RGPlayer/releases) 下载最新的 `RGPlayer-<版本>.apk` 安装即可（Android 12 掌机，免 root）。

## 构建

环境：JDK 17、Android SDK（compileSdk 35）、Gradle 8.7（仓库自带 wrapper）。

```bash
git clone https://github.com/826146857/RGPlayer.git
cd RGPlayer
# 写入 Android SDK 路径
echo sdk.dir=/path/to/android-sdk > local.properties   # Windows: sdk.dir=C\:\\Android\\Sdk
./gradlew assembleDebug                                # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

### Windows 中文路径提示

如果工程路径含中文，Gradle/aapt 可能报错。可先映射盘符再构建：

```powershell
subst R: C:\你的路径
gradlew.bat -p R:\RGPlayer assembleDebug
```

## 项目结构

```
app/src/main/java/com/rg/player/   应用代码（见 HANDOFF.md 的代码地图）
app/src/main/res/                  布局、颜色、字体、图标
androidx/media3/decoder/ffmpeg/    响度分析（复用媒体库包名）
docs/使用说明.md                    面向用户的说明
HANDOFF.md                         接手提示词（新会话先读）
```

## 技术栈

- Java + AndroidX Media3 / ExoPlayer 1.5.0
- `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1`
- minSdk 26 / targetSdk 34 / compileSdk 35
- AGP 8.6.1、Gradle 8.7

## 版本记录

### v0.48.3
- 首个开源版本（不含在线更新）
- 首页三列卡片、下拉刷新、封面、字幕/音轨、手势、长按调速、缩放旋转平移与还原、视频信息、字体切换、设置页

### v1.0.0
- 加入 GitHub 更新检查：点击「更新」→ 检测最新 Release → 有新版本按钮变「下载」→ 下载到 `Download/RGPlayer-<版本>.apk` → 完成后「安装」
- 更新源：`https://api.github.com/repos/826146857/RGPlayer/releases/latest`
- 下载优先走 GitHub API 资源地址，失败时自动切换镜像（ghproxy / gh-proxy）
- 签名与 Release 一致（仓库内 `keystore/rgplayer.keystore`）

## 说明

- 播放器使用 Google Media3 与 Jellyfin 的 FFmpeg 解码扩展，遵循其各自许可证
- 内置字体取自作者自用系统字体，仅作可选内置；可在设置中切回系统字体
- 本项目与 MX Player 无任何关系，也不包含其代码

## License

MIT
