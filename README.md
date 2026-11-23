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

- **Camera**: required and requested at first launch.
- **Legacy media saving**: Android 9 and earlier request legacy storage-write access along with camera access so captured media can be saved; the permission is capped at API 28. Android 10+ uses scoped MediaStore.
- **Microphone**: requested only when recording video; recording can continue silently if access is denied.
- **Built-in gallery**: AngelaCam's own captures are listed from a private index without device-library access. To browse other device photos/videos, the gallery explains why access is needed and requests the Android permissions for the built-in gallery. Android 14 and later can grant access to selected media only; the gallery queries only the scope Android grants. If access is denied, AngelaCam captures remain available.
- **Image selection for another app**: `ACTION_GET_CONTENT` and image `ACTION_PICK` requests open Android's system Photo Picker (or the system document-picker fallback). AngelaCam returns only the selected image URI(s) with read access to the requesting app; this flow does not request broad `READ_MEDIA_*` library access.
- **Network and updates**: a manual update check, or optional automatic checks (off by default; at most once every 24 hours), sends a GET request to GitHub Releases for public release metadata. The update request does not attach local photos or videos. APK downloads are opened in the external browser; AngelaCam does not install packages itself.
- **Not requested**: location, contacts, or install-packages permission. The app has no location permission.

## Build

- Open this directory in Android Studio and Run (JDK 17)
- GitHub Actions: pushes to `main` run tests, build and validate a signed release APK, then publish it as `dev-latest`; a versioned release requires a manual workflow dispatch with a matching version tag. Only the publish job receives `contents: write`; release assets include a SHA-256 checksum and build manifest.

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

- **相机**：必需，启动时申请。
- **旧版系统保存媒体**：Android 9 及以下为保存媒体，会随相机权限一起请求旧版存储写入权限；该权限仅适用于 API 28 及以下，Android 10+ 使用 scoped MediaStore。
- **麦克风**：只在录像时按需申请；拒绝后仍可静音录像。
- **自带相册**：本应用拍摄内容通过私有索引列出，不需要读取整库媒体权限。若要在自带相册中浏览设备上的其他照片/视频，应用会说明用途并请求 Android 媒体权限；Android 14 及以上可只授权所选媒体，相册只查询系统实际授予的范围。拒绝后仍可浏览 AngelaCam 自己拍摄的内容。
- **其他应用请求选择图片**：收到 `ACTION_GET_CONTENT` 或图片 `ACTION_PICK` 请求时，应用打开 Android 系统照片选择器（不可用时使用系统文档选择器回退），只把用户选中的图片 URI 及读取权限交还给发起应用；此流程不申请整库 `READ_MEDIA_*` 权限。
- **网络与更新**：用户手动检查更新，或开启自动检查后（默认关闭、至多每 24 小时一次），应用向 GitHub Releases 发送 GET 请求以读取公开版本信息。更新请求不附带本机照片或视频；APK 下载交由外部浏览器打开，AngelaCam 本身不安装 APK。
- **不申请**：位置、通讯录或安装应用权限；应用没有位置权限。

## 构建

- Android Studio 打开本目录，Run 即可（JDK 17）
- GitHub Actions：推送到 `main` 后运行测试、构建并校验正式签名 APK，再发布到 `dev-latest`；正式版本需手动运行工作流并填写与应用版本一致的 tag。只有发布 job 获得 `contents: write`；发布资产包含 SHA-256 校验和与构建清单。
