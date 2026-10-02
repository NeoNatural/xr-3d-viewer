# Changelog

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
