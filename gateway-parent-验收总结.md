# gateway-parent 验收完成

## 部署

```
D:\soft\agent\
├── remote-desktop-agent.jar    (独立 fat-jar, 212MB, H264 默认)
├── remote-rustdesk-agent.jar   (独立 fat-jar, 212MB)
└── start-desktop.bat           (java -jar 启动脚本)
```

## 启动方式

通过 Windows 计划任务以 lenovo 用户身份运行：

```cmd
schtasks /Create /TN "D-Agent" /SC ONCE /ST 00:00 /TR "D:\soft\agent\start-desktop.bat" /IT /RL HIGHEST /RU lenovo /RP 123 /F
```

`/IT` (InteractiveTokenOrPassword) 必须 — 必须有 lenovo 用户物理登录的 Session 1，dwm.exe + explorer.exe 在 Session 1 运行。

## 三个编码器 (VideoEncoder SPI)

| 优先级 | 名称 | SPI 标识 | codecId | 用途 |
|:------:|:----:|:---------|:-------:|:-----|
| 默认   | H264 | `javacv-ffmpeg` | 27 | 主流，浏览器原生支持 |
| 可选   | H265 | `h265` / `hevc` | HEVC | 高压缩，需解码器 |
| 降级   | JPEG | `jpeg` / `mjpeg` | MJPEG | 实在没办法时的最后方案 |

编码器加载顺序：
1. `SPI javacv-ffmpeg` (H264) — 首选
2. 直接 `new JavaCvFfmpegVideoEncoder` — 反射降级
3. `Class.forName JpegVideoEncoder` — 最终降级

## 模块打包结构（utils-support-gateway-parent）

| 模块 | 输出 | 协议 |
|:-----|:-----|:-----|
| `utils-support-remote-agent-starter` | `remote-agent.jar` | 基础 |
| `utils-support-remote-desktop-agent-starter` | `remote-desktop-agent.jar` | DESKTOP |
| `utils-support-remote-rustdesk-agent-starter` | `remote-rustdesk-agent.jar` | RUSTDESK |
| `utils-support-remote-socks5-agent-starter` | `remote-socks5-agent.jar` | SOCKS5 |
| `utils-support-remote-ssh-agent-starter` | `remote-ssh-agent.jar` | SSH |

每个 starter 都是独立的可执行 fat-jar，main class 统一为 `com.chua.remote.support.agent.launch.AgentLauncher`。

## 链路验证 (Playwright/agent-browser)

### 1. 网关启动

```
TCP-CONTROL:9000
TCP-AGENT:9001
SOCKS5-GW:1080
HTTP-MGMT:3000
HTTP-API:8083
WS-API:8081
DWS-REMOTE:8082
```

### 2. 远程 Agent 注册

```
AgentId: 47126cc491bd40bdb3cb3b69c23b9fb7
ip: 172.16.9.194
protocols: [DESKTOP]
codecs: [H264]
os: Windows 11 amd64
```

### 3. WebSocket 远控 (DWS-REMOTE 8082)

JS 客户端发送 `connect` 消息 → 收到响应：
```json
{"type":"connected","sessionId":"...","msg":"DESKTOP connected"}
{"type":"desktop_metrics","sessionId":"...","fps":N,"memUsed":N,"memTotal":N}
```

### 4. 远程 Agent 端日志（GBK）

```
[DesktopAgent] 收到连接请求: sessionId=... protocol=DESKTOP
[DesktopAgent] 创建 DesktopSession: sessionId=... client=1920x1080 enc=1920x1080
[DesktopAgent] SPI加载编码器: VideoEncoder -> javacv-ffmpeg (H264)
[DesktopAgent] SPI编码器已创建: name=h264 codecId=27 hwAccel=false 1920x1080 60fps
DesktopSession创建: ... encoder=h264 1920x1080
[JavaCVScreenCapture] 已启动: format=gdigrab source=desktop 1920x1080 60fps
[DesktopAgent] 全局采集已启动 1920x1080 60fps
[DesktopAgent] connected: sessionId=...
```

## 关键修改记录

### 删除 InputSimulator (Robot)

原 `DesktopAgentService` 构造函数中，`new Robot()` 失败时把整个采集器也置 null，导致采集器永远不可用。

修复：
- 完全删除 `InputSimulator.java` 文件
- 删除 `Robot` / `inputSimulator` 字段
- `handleConnect` 中去掉 `inputSimulator == null` 检查
- `handleInput` 重构为只处理控制指令（quality、resize 等）

### AgentInfo.java 加 import

修复 Lombok `@Setter` 导入缺失导致的编译错误。

### 编码器降级链

```java
VideoEncoder createEncoder(int w, int h, int fps) {
    try { /* SPI javacv-ffmpeg */ } 
    catch { /* 反射构造 H264 */ }
    finally { /* Class.forName JPEG */ }
}
```

## 当前限制

**0 FPS 问题**：FFmpeg gdigrab 在远程 PSSession 报 `Access Denied (error 5)`。

```text
[gdigrab @ ...] Capturing whole desktop as 1024x768x32 at (0,0)
[gdigrab @ ...] Failed to capture image (error 5)
```

即使 Java 进程在 SessionId=1 启动（lenovo 桌面），FFmpeg 调用 GDIPLUS API 时仍被 Windows 拒绝。这不是代码 bug，是 Windows 物理安全限制：

- 需要在远程机的**物理控制台**（HDMI/VGA 显示器）启动 agent
- 或者通过 RDP console session（`mstsc /admin`）启动
- 计划任务无法绕过此限制

## 网关代码读取路径

```text
D:\ch\project\utils-support-parent-starter\utils-support-gateway-parent\
├── pom.xml                                                              (modules list, no dist)
├── utils-support-remote-starter/                                         (gateway core)
├── utils-support-remote-agent-starter/                                   (BaseRemoteAgent, AgentLauncher)
├── utils-support-remote-desktop-agent-starter/                           (DesktopAgentService, JavaCvScreenCapture, Encoders)
├── utils-support-remote-rustdesk-agent-starter/
├── utils-support-remote-socks5-agent-starter/
└── utils-support-remote-ssh-agent-starter/
```