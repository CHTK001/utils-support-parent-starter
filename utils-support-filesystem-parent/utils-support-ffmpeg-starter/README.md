# utils-support-ffmpeg-starter

FFmpeg 音视频处理模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ffmpeg-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `VideoFileSystem` | 视频文件系统 SPI 实现。 基于 FFmpeg 实现视频文件的元数据读取与格式转换。 底层通过 SPI 进行实际的 FFmpeg 操作。 (SPI: `video`) |
| `VideoReadBuilder` | 视频文件读取构建器。 基于 FFmpeg 读取视频文件的元数据信息。 通过 获取编码、分辨率、时长等信息。 |
| `VideoWriteBuilder` | 视频文件写入构建器。 基于 FFmpeg 实现视频格式转换，支持编码器、码率、分辨率、帧率等参数配置。 |
| `AbstractFFmpegProcessor` | FFmpeg 处理器抽象基类，提供 FFmpeg 可执行文件查找和通用参数构建逻辑。 |
| `JaffreeFFmpegProcessor` | Jaffree 实现的 FFmpeg 处理器。 (SPI: `jaffree`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-ffmpeg-starter
├── utils-support-common-starter
```
