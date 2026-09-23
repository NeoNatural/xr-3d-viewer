<p align="center">
  <img src="moonlight-xr-logo-transparent.png" height="180" alt="XR 3D Viewer 标志">
</p>

# XR 3D Viewer

[English](README.md) | **简体中文**

XR 3D Viewer 是一款开源的 Android/OpenXR 媒体查看器，可在一体式头显上将普通的
2D 照片和视频转换成立体 3D。媒体既可以从本地存储打开，也可以直接从 SMB/NAS
共享中流式读取；无需桌面端配套程序、云端上传或预先转换。

本项目基于 [Moonlight XR](https://github.com/Gilleece/moonlight-android-xr)
和 [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)。
它保留了原生 OpenXR 渲染器和 GPU 视频路径，同时将本地与 SMB 媒体播放作为主要用途。

> **项目状态：** 早期版本。目前主要面向 Meta Quest 3 以及其他采用 Snapdragon XR2
> Gen 2 同级芯片的设备开发。请使用舒适的立体视差；如果画面引起眼部不适，请停止观看。

## 功能

- 通过 Android 系统文件夹选择器浏览照片和视频。
- 直接连接 SMB 2/3 共享；保存的凭据由 Android Keystore 加密。
- 使用预处理的 Depth Anything V2 深度查看 JPEG、PNG 和 WebP 图像。
- 通过 Android MediaCodec/Media3 播放 MP4、MKV、WebM、M4V 和 MOV。
- 使用 MiDaS Small 和基于深度图像的渲染着色器，将解码后的视频帧实时转换为立体画面。
- 在 VR 内移动和缩放虚拟屏幕、调整立体视差、交换左右眼，并选择透视模式或内置环境。
- 根据头显支持情况使用控制器、手势追踪或注视操作。

实际可播放的视频编码取决于设备的 Android MediaCodec 实现。文件扩展名仅用于浏览器筛选，
不代表容器中的每一种编码或配置都一定受支持。

## 工作原理

静态图像使用细节更丰富的预处理深度路径：

```text
图像 -> Depth Anything V2 Small (518x518)
     -> 边缘感知深度预处理
     -> 分眼深度变形
     -> OpenXR 合成层
```

视频保持在 GPU 解码路径，并使用延迟更低的模型：

```text
Media3/MediaCodec -> SurfaceTexture（外部 OES 纹理）
                  -> 256x256 推理输入
                  -> 独立 LiteRT 线程上的 MiDaS Small
                  -> 边缘引导的深度上采样
                  -> 分眼遮挡感知变形
                  -> OpenXR 合成层
```

单目 2D 转 3D 无法还原物体背后原本不可见的像素，因此高对比度轮廓附近可能出现拉伸或涂抹。
视频深度也会按可配置的频率更新，可能略微落后于画面。

## 安装

从仓库的 [Releases](https://github.com/NeoNatural/xr-3d-viewer/releases) 页面下载
已签名 APK，然后使用 Meta Quest Developer Hub、SideQuest 或 ADB 侧载：

```sh
adb install -r xr-3d-viewer-v0.4.apk
```

Android 会拒绝安装未签名的 Release APK。正式发布的附件在上传前均由 CI 验证，并附带
SHA-256 校验文件。

## 使用方法

1. 在头显上启动 **Moonlight XR**。
2. 选择 **Browse local photos and videos**（浏览本地照片和视频）或
   **Browse SMB photos and videos**（浏览 SMB 照片和视频）。
3. 浏览本地媒体时，通过 Android 文件夹选择器授予文件夹读取权限；使用 SMB 时，输入
   NAS 主机、共享名称和凭据。
4. 选择一张图像或一个视频。同一文件夹内的其他受支持文件会成为上一个/下一个播放项。
5. 打开 **Media Settings**（媒体设置），调整深度更新频率、左右眼顺序、透视模式和性能诊断。

SMB 凭据不会离开设备。保存的密码使用应用专属的 Android Keystore AES-GCM 密钥加密。
只有用户明确要求时才会创建错误报告；除非维护者在构建时配置了报告接收端点，否则报告仅
保存在本地文件中，由用户手动分享。

## 支持的硬件

- Meta Quest 3 是主要测试目标。
- Pico 4 Ultra 及类似的 OpenXR 头显预计可通过现有 Moonlight XR 渲染器运行。
- Quest 2、Quest Pro、Pico 4 和更早的设备拥有较少的 GPU 性能余量。应用会采用保守配置，
  但预处理深度的静态图像或普通平面视频可能比实时视频转换更实用。

手势追踪、眼动/注视输入和透视模式均为可选功能；控制器始终作为备用输入方式。

## 从源码构建

这是一个基于 Android/Gradle 的原生 OpenXR 项目，不是 Unity 项目。

环境要求：

- JDK 21
- Android SDK 37
- Android NDK `29.0.14206865`
- Git 子模块

克隆时一并获取原生串流子模块：

```sh
git clone --recursive https://github.com/NeoNatural/xr-3d-viewer.git
cd xr-3d-viewer
```

检出路径不含空格时：

```sh
./gradlew testNonRootDebugUnitTest
make -C app/src/test/cpp test
./gradlew assembleNonRootDebug
```

本仓库有时会位于包含空格的父目录中，而 `ndk-build` 无法处理这种路径。此时请使用提供的
包装脚本；它会将项目同步到不含空格的临时路径中构建，再把 APK 复制回
`build/agent-apks/`：

```sh
./tools/build-local.sh testNonRootDebugUnitTest
./tools/build-local.sh assembleNonRootRelease lintNonRootRelease
```

Debug APK 通常位于 `app/build/outputs/apk/nonRoot/debug/`。本地 Release APK 位于
`app/build/outputs/apk/nonRoot/release/`；未配置签名时，该 APK 不会签名。

### 签署本地 Release

只需创建一次 keystore：

```sh
keytool -genkeypair -v -keystore release.keystore -alias moonlightvr \
  -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties
```

在配置文件中填写 keystore 路径、别名和密码，然后构建：

```sh
./tools/build-local.sh assembleNonRootRelease
```

`release.keystore` 和 `keystore.properties` 已被 Git 忽略。切勿提交这些文件，也不要在 CI
日志中输出其内容。

## 发布流程

GitHub Actions 工作流会运行 Java 和原生单元测试、Release Lint，以及 Debug/Release
构建。名为 `v*` 的标签还要求配置签名 Secrets：`KEYSTORE_BASE64`、
`KEYSTORE_PASSWORD`，以及可选的 `KEY_ALIAS`。工作流会验证 APK 签名、生成 SHA-256
校验文件，并将两者发布到 GitHub Release。

发布版本需在 `app/build.gradle` 中保持一致：

- `versionName`：上游 Moonlight 版本加 `-xrX.Y`
- `versionCode`：单调递增的 Android 软件包版本号
- Git 标签：`vX.Y`

## 代码结构

| 路径 | 用途 |
| --- | --- |
| `app/src/main/java/com/limelight/local/` | Android Storage Access Framework 文件浏览器 |
| `app/src/main/java/com/limelight/smb/` | SMB 浏览器、加密配置和随机访问数据源 |
| `StaticImageXrActivity.java` | 静态图像加载及预处理深度播放 |
| `VideoXrActivity.java` | 将本地/SMB Media3 视频播放到 XR 表面 |
| `binding/video/MidasDepthSource.java` | LiteRT 实时视频深度 |
| `binding/video/StillImageDepthBatcher.java` | Depth Anything V2 静态图像深度 |
| `app/src/main/jni/xr-renderer/` | OpenXR、OpenGL、输入、合成层、房间和变形渲染器 |
| `app/src/main/jni/libsmb2/` | 原生 SMB 随机访问桥接及内置 libsmb2 |
| `tools/` | 可复现构建辅助工具、模型转换和诊断工具 |

渲染器与线程方面的不变量请参阅
[`ARCHITECTURE_NOTES.md`](ARCHITECTURE_NOTES.md)。模型测量结果和来源记录在
[`DEPTH_MODEL_BENCHMARK.md`](DEPTH_MODEL_BENCHMARK.md) 中。

## 许可证与署名

由于本应用是 Moonlight for Android 的修改作品，因此以 **GNU GPL v3** 发布。请参阅
[`LICENSE.txt`](LICENSE.txt)。

所有新增的运行时依赖和资源均已检查其与 GPLv3 的兼容性。所需版权声明、完整 Apache-2.0
条款、LGPL 组件对应关系、模型来源与哈希，以及 Creative Commons 署名均收录在
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 中。这些文档也包含在每个 APK 内，
可通过 **Media Settings > Open-source licenses**（媒体设置 > 开源许可证）打开。

主要组件包括：

- `libsmb2` 使用 LGPL-2.1-or-later，其 DCE/RPC 部分使用 BSD 许可证。
- NOVA 的 `jcifs-ng` 分支使用 LGPL-2.1。
- LiteRT、Media3、OpenXR 和 Depth Anything V2 Small 源模型使用与 Apache-2.0
  兼容的条款。
- MiDaS Small 使用 MIT 许可证。
- Poly Haven 环境资源使用 CC0。
- PSX Cinema 改编自 fangzhangmnm 的 *VR Cinema Environment*，使用 CC BY 4.0。

Moonlight 名称和标志仅用于说明本项目的上游来源，并不代表上游项目对本项目的认可或背书。

## 参与贡献

欢迎提交 Issue 和目标明确的 Pull Request。请保持解码后的视频位于 GPU 路径，保留原始
渲染器的回归路径，并在提交前运行 Java 与原生单元测试。
