# utils-support-video-processor-starter

视频转 HLS 处理器（Rust JNI）

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-video-processor-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `VideoProcessorBridge` | VideoProcessorBridge |
| `HlsTranscodeExample` | HLS 转码真实示例 — 演示通过 VideoProcessorBridge 调用 Rust 原生库将 MP4 转为 HLS。 |
| `VideoProcessorSpiExample` | 视频处理 SPI 示例 — 演示通过 ServiceProvider 发现并调用 FFmpegProcessor 真实实现。 |
| `VideoProcessor` | VideoProcessor |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-video-processor-starter
├── utils-support-common-starter
├── utils-support-native-video-processor
├── utils-support-ffmpeg-rust-starter
```