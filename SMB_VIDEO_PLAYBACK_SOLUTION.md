# SMB 视频播放解决方案

## 最终路线

- **浏览、目录和图片**继续使用 `jcifs-ng`。
- **视频数据读取**使用原生 `libsmb2 7.0.0`，通过 JNI 向 Media3 提供随机访问。
- Media3/ExoPlayer 将视频交给 Android 硬件解码器，解码结果直接输出到 `XrRenderer` 的 `Surface`。
- 视频帧保持在 GPU 路径中，继续复用现有 OpenXR、实时深度和立体渲染流程，不做逐帧 CPU 回读，也不整段下载视频。

数据路径：

```text
NAS → libsmb2 → SmbDataSource → Media3/硬件解码 → Surface → OpenXR
```

## 关键实现

- `SmbDataSource` 将 Media3 的 range/seek 请求转换为 SMB `readAt()`。
- 随机探测读取使用 128 KiB；确认连续读取后扩大到 1 MiB，兼顾容器探测与吞吐量。
- `libsmb2` 负责持续的高码率视频读取；打开失败时保留 `jcifs-ng` 作为兼容回退。
- JNI 层解码 SMB URI 中百分号编码的共享名和文件路径，以支持空格、中文等文件名。
- SMB 视频播放使用严格的 **48 MiB** Media3 缓存上限：
  - 最小/最大缓存：10 秒 / 30 秒
  - 首次播放阈值：3 秒
  - 卡顿恢复阈值：5 秒
  - 字节上限优先于时间目标

## 曾遇到的问题

### 4K60 周期性卡顿

仅用 `jcifs-ng` 时，实测持续读取约为 7.9 MiB/s，无法稳定覆盖部分高码率 4K60 视频。增加更长缓存只能延后卡顿，不能解决吞吐瓶颈。

解决方法是将视频读取切换到原生 `libsmb2`，而不是继续扩大 jcifs 缓存。

### SMB 视频闪退

首版 `libsmb2` 无法打开百分号编码的文件路径，因此回退到 jcifs。Media3 当时按 30–90 秒、时间优先的策略预取高码率视频，最终耗尽 Quest 的 256 MiB Java 堆并触发 `OutOfMemoryError`。

修复包括：

1. 正确解码 SMB 共享名和文件路径。
2. 将 Media3 缓存限制为 48 MiB，并改为字节上限优先。
3. 仅对成功连接的原生 SMB 上下文执行断开操作。
4. 日志明确区分 URL 解析、共享连接、文件打开和属性读取失败。

## 验证结果

- Java 单元测试通过。
- 原生测试 104 项全部通过。
- APK 包含四种 ABI 的 `libsmb2-jni.so`。
- Quest 3 实机验证 SMB 视频播放流畅，包括此前会卡顿或闪退的高码率视频。

## 回归检查

出现 SMB 视频问题时，优先检查日志是否包含：

```text
Native libsmb2 video source opened
```

如果出现 `Native SMB2 open failed; using jcifs fallback`，应先解决原生连接或路径兼容问题；不要通过无限增加 Media3 缓存来掩盖吞吐不足。
