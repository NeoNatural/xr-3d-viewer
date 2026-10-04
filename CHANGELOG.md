# Changelog

## v0.6 — 2026-10-04

## 更新内容 / Changes

- **ZipDepth 视频 3D / ZipDepth video 3D**：视频默认使用 ZipDepth；Quest 3 使用 512×288 FP16 GPU 模型，设置中仍可选择 MiDaS。Video now defaults to ZipDepth, using the 512×288 FP16 GPU model on Quest 3. MiDaS remains available in settings.
- **双缓冲深度管线 / Double-buffered depth pipeline**：独立的 GL 阶段线程负责采样回读和深度上传，与模型推理重叠执行；GPU 内核缓存减少再次打开视频时的初始化开销。A dedicated GL stage thread overlaps capture readback and depth upload with model inference, while cached GPU kernels reduce subsequent startup work.
- **时间平滑和切镜重置 / Temporal smoothing and scene cuts**：深度及归一化范围按实际时间平滑，检测到切镜时重置对应帧的历史。Depth and normalization-range smoothing use elapsed time; scene cuts reset the history on the corresponding frame.
- **按时间调度 / Time-based depth updates**：默认每秒更新 20 张深度图，可设置 5–45 次/秒；旧的“每 N 帧推理”设置自动迁移。持续超出视频帧预算时降低深度更新频率，并在负载恢复后按策略逐步恢复。Depth updates default to 20 maps/s, adjustable from 5 to 45, with one-time migration of the old frame cadence. A sustained frame-budget miss lowers the depth target; recovery follows the governor's hold and backoff rules.
- **图片与现有播放功能 / Existing media paths**：图片继续使用 Depth Anything V2。保留现有 SMB、本地播放、媒体导航和界面；本次未合入上游其他房间、音频或界面更新。Still images continue to use Depth Anything V2. Existing SMB/local playback, media navigation and UI are preserved; this release selectively ports upstream video-depth changes.

## 安装说明 / Installation

下载并安装 `xr-3d-viewer-v0.6.apk`。正式包名仍为 `com.liang.xrmediaviewer`，可覆盖升级 v0.5，保留该正式版的设置和 NAS 连接资料。The signed APK updates v0.5 in place, preserving that release installation's preferences and NAS targets. Debug installations remain separate.

附件包含 SHA-256 校验文件、应用许可证及第三方声明。Assets include the SHA-256 checksum, application license and third-party notices.

## 验证 / Validation

- 55 项 Java 单元测试、3,894 项原生检查通过。55 Java unit tests and 3,894 native checks passed.
- Quest 3 上二维 1080p60 视频实测：模型推理约 19–21 ms，深度更新约 20 次/秒，所测窗口内没有因管线忙而跳过采样。这些数字代表深度模型和更新频率，不代表视频或合成器帧率。On Quest 3, the 1080p60 2D test clip measured approximately 19–21 ms inference and 20 depth maps/s, with no busy capture skips in the sampled windows. These figures describe depth processing, not video/compositor FPS.
- 图片 V2 深度推理和上传回归通过；用户已确认 Quest 3 实机测试无误。Still-image V2 inference/upload regression passed, and the user confirmed successful Quest 3 device testing.

视频深度更新参考上游 [Gilleece/moonlight-android-xr](https://github.com/Gilleece/moonlight-android-xr)，截至 `686429ab8c91ad1c8bb128a7c7e3188802c3bcf4`。Video-depth changes were selectively ported from that upstream revision.

[Full source changes / 完整源码变更](https://github.com/NeoNatural/xr-3d-viewer/compare/v0.5...v0.6)

## v0.5 — 2026-10-03

## 更新内容 / Changes

- **独立安装 / Independent installation**：应用名称改为 XR Media Viewer，正式包名为 `com.liang.xrmediaviewer`，可与 Moonlight XR 同时安装。The viewer now has its own application ID and can coexist with Moonlight XR.
- **SMB 共享浏览 / SMB share browsing**：Share 可留空，连接后列出服务器的文件共享；仍可填写 Share 直接进入指定共享。Leave Share blank to list server disk shares, or enter a share to open it directly. Access remains subject to server permissions; enter the share manually if enumeration is restricted.
- **双手摇杆 / Either-controller shortcuts**：左右手摇杆左/右拨动均可切换上一张/下一张图片，或让视频后退/前进 10 秒。Each deflection triggers once; return the stick to center before another step.
- **保留画布操作 / Canvas placement preserved**：抓握移动或缩放画布时，摇杆继续控制距离和大小，不触发媒体操作；松开后回中再拨动。Media shortcuts are suppressed during canvas placement and rearm after the sticks return to center.
- **返回导航 / Back navigation**：SMB 和本地浏览器顶部左侧新增无文字的返回图标；子目录返回上一级，根目录回到主菜单，可重新选择本地或 SMB。Both browsers now have an icon-only Back button, including a reliable route from the root to the main menu.
- **播放菜单 / Viewer controls**：返回平面菜单的确认文案改为 “Back to menu”。顶部图片/视频控制条仅在悬停时显示。The top controls now hide when the pointer leaves their region.
- **更易定位 / Easier seeking**：视频控制条加宽至画面宽度的 95%，进度条实际长度约为原来的 2.1 倍，图标和点击区域保持一致。The wider video bar gives the seek track about 2.1× its previous physical length.

## 安装说明 / Installation

Download `xr-3d-viewer-v0.5.apk` and sideload it onto the headset. The APK is signed and accompanied by a SHA-256 checksum.

**包名变化 / Package change:** v0.5 installs as a separate application from v0.4/Moonlight XR. Existing NAS targets and preferences are not migrated; enter them again in XR Media Viewer. The older application can remain installed.

## 验证 / Validation

Native and Java unit tests passed. The browsing, controller shortcuts, canvas placement, return navigation, hovering controls and wider seek bar were tested and confirmed on Quest 3. GitHub Actions verifies the release build, lint and APK signature before publishing the assets.

[Full source changes / 完整源码变更](https://github.com/NeoNatural/xr-3d-viewer/compare/v0.4...v0.5)

## v0.4 and earlier

See the [previous GitHub releases](https://github.com/NeoNatural/xr-3d-viewer/releases) for earlier versions.
