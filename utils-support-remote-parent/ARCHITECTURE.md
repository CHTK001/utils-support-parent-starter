# Remote 系统架构（collapse-executor / utils-support-remote-parent）

> 浏览器控制端 → 网关 → 被控端 agent → 真实目标，双向交互远控系统。
> 本架构图基于 2026-09-09 浏览器端到端实证（xterm 终端 ↔ 远端 Ubuntu bash 双向交互已通）。

## 一、整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        控制端（浏览器 / 桌面控制端）                │
│  Vue RemoteView 页面（默认值预填·零输入）                          │
│  ├─ SSH 终端：xterm.js（ANSI 渲染 + 光标 + 键盘输入）              │
│  ├─ 远程桌面：H264/JPEG 解码渲染（canvas） + 键鼠注入               │
│  └─ VNC/RDP：WebSocket 会话                                         │
└───────────────┬─────────────────────────────────────────────────┘
                │ HTTP 9001（/verify 验证·/config 配置·CORS 放行）
                │ WS 9003（/ws/ssh/{agentId} · /ws/vnc/...）
                ▼
┌─────────────────────────────────────────────────────────────────┐
│  remote-gateway  网 关                                            │
│  ├─ 鉴权：接入令牌 + 验证码（AuthManager）                        │
│  ├─ 会话管理：SessionManager（sshSessions 路由映射）              │
│  ├─ 路由：控制端帧 ↔ 被控端帧双向转发（onAgentSshFrame 等）        │
│  ├─ 转码协商：TranscodeEngine（H264↔JPEG 能力交集协商）            │
│  └─ HTTP：JdkHttpServer（verify/config/gateways/agents + CORS）  │
└───────────────┬─────────────────────────────────────────────────┘
                │ TCP 9000 帧通道（自研帧引擎·SIGNAL/DATA/CTRL）
                ▼
┌─────────────────────────────────────────────────────────────────┐
│  remote-agent  被控端                                            │
│  ├─ 注册/心跳：AgentInfo 上报（平台/能力/凭据）·每 5s 重发注册     │
│  ├─ SSH 套壳：Windows OpenSSH ssh -tt → 远端 bash（真实 pty）     │
│  ├─ VNC/RDP 套壳 + 反向隧道（SshClient.forward）                  │
│  └─ 桌面采集：30fps 虚拟线程截图 → ImageProcessors 编码（SPI）     │
└───────────────┬─────────────────────────────────────────────────┘
                │ ssh -tt / RDP / VNC / 采集
                ▼
        ┌───────────────────┐
        │  被控目标（真实）   │
        │  远端 Ubuntu bash  │
        │  / 桌面 / 服务      │
        └───────────────────┘
```

## 二、模块职责（6 个 L1 模块）

| 模块 | 职责 | 关键类 |
|---|---|---|
| **remote-protocol** | 帧协议定义：`SIGNAL`（注册/信令）/`DATA`（会话数据）/`CTRL`（控制）+ 帧编解码 + 能力模型（AgentInfo/CodecProfile）+ SPI 接口 10 类 | FrameCodec、MessageType、AgentInfo |
| **remote-core** | 传输层：自研帧引擎（绕开 common 的 Netty 缺陷）+ 帧编解码 + RemoteServer/Client/Flow 6 类 | FrameServer、FrameClient、RemoteTransport |
| **remote-gateway** | 鉴权（令牌/验证码）、会话管理、双向路由、转码协商 5 类 | GatewayServer、AuthManager、SessionManager、TranscodeEngine |
| **remote-agent** | 被控端：注册/心跳、SSH-RDP-VNC 套壳、反向隧道、桌面采集编码 6 类 | SshAgentBootstrap、SshSessionChannel |
| **remote-controller** | 控制端库：解码渲染、键鼠注入 3 类 | — |
| **扩展点** | 编码全 SPI（`META-INF/services/com.chua.remote.protocol.spi.*`）——零拷贝/BufferedImage 等 | ImageProcessors、@Spi |

复用基础：`common`（ImageProcessors/Json/ThreadUtils/@Spi）+ `network`（NettyWebSocketSyncFlow + AES-256-GCM）+ `ssh`/`video-processor`/`oshi` starter。

## 三、端口架构（本机实测）

| 端口 | 用途 |
|---|---|
| **9000** | TCP 帧通道（agent ↔ 网关——SIGNAL/DATA/CTRL 帧） |
| **9001** | HTTP（`/verify` 验证 · `/config` 配置 · 代理列表——**带 CORS**） |
| **9002** | FrameServer 内部 WS |
| **9003** | ControllerWS——浏览器会话（`/ws/ssh/{agentId}` 等） |

## 四、一次 SSH 会话的数据流（端到端实证）

```
1. 页面加载     → GET /api/remote/config（同 IP 默认网关/WS——零输入）
2. 点"连接"    → POST /verify {agentId, verifyCode}（CORS 放行）→ 鉴权 ✓
3. 建立 WS     → ws://<ip>:9003/ws/ssh/local-ssh
4. 发 ssh-start → 网关「自动补建会话」→ 路由 → TCP 9000 → agent
5. agent       → Windows OpenSSH ssh -tt ubuntu@远端 → 远端真实 bash（pty）
6. 输出回传    → agent 回传帧 {type:output, stream:stdout, data:"Welcome to Ubuntu..."}
                → 网关按 sessionId 路由回 WS → 前端 term.write → xterm 渲染 ✓
7. 键盘输入    → term.onData → ws input 帧 → 网关 → agent → ssh stdin
                → 远端执行 → 回显（echo PING_FROM_BROWSER ✓）
```

## 五、核心设计要点

- **帧协议三态**：SIGNAL（注册/心跳/信令）、DATA（会话数据流）、CTRL（控制指令）——一个帧引擎统一承载 SSH/VNC/RDP 全部会话
- **会话路由**：控制端 sessionId（前端生成）贯穿全链——网关以 sessionId 建映射，agent 回传帧携带真实会话 id，双向命中
- **套壳模式**：agent 不实现协议栈——直接拉起系统 `ssh -tt`（真实 pty，top/vim 可用）+ 输出回流——"复用已有能力而非再造"
- **SPI 扩展**：编码/采集/传输全部可插拔——换编码器/采集器不改主链路
- **韧性**：agent 心跳每 5s 重发注册（帧丢失自愈）+ 断连重连循环

## 六、端到端验证修复链（2026-09-09）

| 问题 | 根因 | 修复 |
|---|---|---|
| 浏览器 `/verify` 被拦截 | 网关 HTTP 缺 CORS 头 | `createCorsContext` 加放行 + OPTIONS 预检 |
| bash 永不渲染 | 前端 `handleTextMessage` 只认 `type="ssh"`，回传帧 type 实为 `connected/started/output/stopped` | 改 `msg.stream` 判定 |
| 浏览器连不上 | 前端默认 wsUrl 端口 9001（纯 HTTP），ControllerWS 实际在 9003 | 默认端口 9001→9003 |
| 会话启动即死 | Git 发行版 `ssh.exe` 子进程缺 DLL（`error while loading sha`） | `resolveSshExecutable` 换 Windows 内置 OpenSSH |
| 注册帧随机丢失 | 首次注册帧丢失后网关永远不知 agent | 心跳每 5s 重发注册（幂等覆盖） |
| 能力错报"无桌面" | agent 注册未设 desktopSupported | `detectDesktopSupported`（Win/mac 恒真） |
