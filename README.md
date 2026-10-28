# AngelaCam

A clean, privacy-first native Android camera app. Kotlin + CameraX.

## Features

- Photo: flash (auto/on/off), self-timer (3s/10s), tap to focus/exposure with focus ring, pinch zoom, EV compensation, grid overlays, volume-key shutter
- Video: torch, recording timer, screen stays on while recording
- Photo editor: crop (preset ratios), rotate/straighten, flips, 6 filters, adjustments (exposure, brightness, contrast, highlights, shadows, temperature, tint, saturation), one-tap auto enhance, hold-to-compare with the original; saves at full resolution (up to 4096px)
- Built-in gallery: grid browsing, full-screen viewer with slideshow, delete, share, details, set as wallpaper
- Opens images/videos from other apps (system "Open with" and share targets)
- Update checks against GitHub Releases — checking only; downloads open in your browser

## Privacy (least-privilege by design)

- **Camera**: required, requested at first launch
- **Microphone**: requested only the first time you record a video; if denied, recording continues silently
- **Internet**: used solely to check GitHub for updates — when you tap "Check for updates", or daily if you opt in (off by default). Nothing is ever uploaded; photos and videos never leave the device
- **Never requested**: location, storage read, contacts, or install-packages permissions
- **No GPS in EXIF**: photos carry no location information
- **The built-in gallery only contains what this app captured**, via an internal index — it never scans your photo library

## Build

- Open this directory in Android Studio and Run (JDK 17)
- GitHub Actions: pushes to `main` build a debug APK and publish it to the `dev-latest` prerelease

---

# AngelaCam（中文）

简洁、隐私优先的 Android 原生相机 App，Kotlin + CameraX。

## 功能

- 拍照：闪光灯（自动/开/关）、延时自拍（3s/10s）、点按对焦/测光（带对焦框动画）、双指缩放、曝光补偿（EV）、构图辅助线、音量键快门
- 录像：补光灯、录制计时、录制时保持亮屏
- 照片编辑器：裁剪（多比例）、旋转/拉正、镜像、6 种滤镜、调节（曝光/亮度/对比度/高光/阴影/色温/色调/饱和度）、一键自动增强、按住对比原图；保存按全分辨率重走管线（上限 4096）
- 自带相册：网格浏览、大图/视频查看、幻灯片、删除、分享、详细信息、设为壁纸
- 可作为系统「打开方式」与分享目标，查看/编辑其他应用的图片与视频
- 检查更新：只向 GitHub 查询是否有新版本，下载跳转浏览器完成，应用自身不装包

## 隐私（最小权限原则）

- **相机**：必需，启动时申请
- **麦克风**：只在用户第一次按下录像键时按需申请；拒绝则静音录制，不阻断使用
- **网络**：仅用于检查更新——用户点「检查更新」时，或开启自动检查后每天至多一次（默认关闭）；不上传任何数据，照片/视频永不出设备
- **不申请**：位置、存储读取、通讯录、安装未知应用等权限
- **不写 GPS**：照片 EXIF 不包含位置信息
- **自带相册只收录本应用拍摄的内容**：通过内部记录索引，不扫描用户相册

## 构建

- Android Studio 打开本目录，Run 即可（JDK 17）
- GitHub Actions：每次推送到 `main` 自动构建 debug APK，并发布到 `dev-latest` 预发布
