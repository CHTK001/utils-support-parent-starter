# utils-support-remote-desktop-agent-starter

Desktop Agent — 屏幕捕获 + 输入模拟 + 推帧

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-remote-desktop-agent-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `VideoCodecConfig` | Video codec configuration stub. |
| `stub` | Video encoder interface stub. |
| `for` | Marker interface for encoders that accept Frame directly. |
| `DesktopAgentService` | 桌面 Agent 服务 单线程采集→编码循环，使用 JavaCV(FFmpeg) 采集 + SPI 编码器 采集尺寸 = 所有会话目标尺寸的最大值 零拷贝 Fr |
| `DesktopSession` | 桌面会话 由外部驱动帧输入(feedFrame)，内部完成缩放→编码→回调发送 编码器通过 SPI 创建，支持 H264/H265/H266 优先使用 feed |
| `InputSimulator` | 输入模拟器 使用 java.awt.Robot 模拟鼠标和键盘输入 |
| `JavaCVScreenCapture` | JavaCV(FFmpeg) 屏幕采集实现 平台自动适配：Windows→gdigrab, Linux→x11grab, macOS→avfoundation  (SPI: `javacv`) |
| `ScreenCaptureProvider` | 屏幕采集 SPI 接口 — 提供零拷贝帧采集 实现类应通过 注解注册，由 ServiceProvider 发现加载。 (SPI: `name`) |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-remote-desktop-agent-starter
├── utils-support-common-starter
├── utils-support-remote-agent-starter
├── utils-support-ffmpeg-javacv-starter
```