# RDP Agent 部署与流程（业务定义）

> 状态：业务逻辑定稿 + 实施任务清单（2026-09-09 按代码现状复核）
> 关联：`utils-support-remote-agent`（agent 端 RDP 桌面能力——**待新建 `utils-support-remote-agent-rdp` 模块**，对齐 ssh/vnc 模块结构）
> 姊妹文档：`SSH-AGENT-流程.md` / `VNC-AGENT-流程.md`（结构对称）

---

## 1. 总则

**RDP 模式的核心是：agent 部署到被控机后，自己采集 RDP 服务可用性，按平台决定如何提供 RDP 桌面会话能力。**

- agent 是**被控机上的代理**：负责采集、检测、确保 RDP 服务可达，或降级为自研采集推流
- 会话内容：控制端通过 RDP 与被控机桌面交互（画面回传 + 键鼠注入）
- **与 SSH/VNC 无关**——RDP 走微软 RDP 协议（默认 3389 端口），agent 保证被控机具备可达的 RDP 服务，供控制端连接

> **⚠️ 纯服务端约束（RDP 与 SSH/VNC 的本质差异）**：
> Java 生态**没有 RDP 服务端框架**——SSH 有 MINA sshd 可纯 Java 自启，VNC 可由 agent 拉起 x11vnc；
> 而 RDP server（协议栈：T.128/T.125、RemoteFX、RDP 图形管道）**没有任何纯 Java 实现**，
> agent **无法用 Java 自启一个 RDP 服务**。因此：
> - RDP 服务的供给只能依赖**操作系统/第三方原生服务**（Windows 自带 Remote Desktop Services、Linux 装 xrdp）——agent 只做**检测 + 启停/配置**，不实现协议
> - 原生 RDP 服务不可得时（无权限开启/无 xrdp 可装），agent 只能**降级为自研采集推流**（等价画面能力，非 RDP 协议）

---

## 2. agent 启动：自采集服务器信息

agent 启动（部署到被控机）后采集：

| 采集项 | 内容 | 用途 |
|---|---|---|
| 平台类型 | `Linux` / `Mac` / `Windows` | 决定分支逻辑 |
| RDP 服务可用性 | 本机 **RDP server 是否在运行**（服务级检测——**非端口 3389 探测**：Windows 查 `TermService` 服务状态/注册表 `fDenyTSConnections`；Linux 查 `ps -ef \| grep xrdp` / systemd 单元；Mac 无 RDP） | 决定「转发」还是「降级」 |
| 桌面环境 | 是否有图形会话 | 判定降级采集可行性 |
| 编码能力 | 支持的帧编码集（h264/jpeg） | 上报能力模型（RDP 原生码流为 RemoteFX 等，需转码或走采集流） |
| 凭据 | RDP 用户名/密码（配置或上报） | 会话鉴权 |

采集完成后把结果随 `AgentInfo` 注册上报到网关（agent 侧注册帧）。

---

## 3. 平台分支逻辑（核心决策）

```
agent 自采集平台类型 + RDP 服务可用性
         │
         ├── Windows ───────────────────────────────────────┐
         │      ├─ 远程桌面已启用（TermService 运行）→ 转发    │
         │      └─ 未启用 → agent 配置并拉起系统远程桌面        │
         │         （改 fDenyTSConnections + 启 TermService，  │
         │          纯系统能力开关，非 Java 实现 RDP 协议）      │
         │                                                    │
         ├── Linux ─────────────────────────────────────────┤
         │      ├─ 已有 xrdp（运行中）→ 转发                   │
         │      └─ 无 xrdp → agent 拉起 xrdp（第三方原生进程）  │
         │           无 xrdp 可用 → 降级自研采集推流            │
         │                                                    │
         └── Mac ──── 无原生 RDP ──→ 降级自研采集推流 ─────────┤
                                                              │
   三种结果统一：控制端拿到可达的桌面画面与键鼠控制              │
   （转发=复用已有 RDP 服务；降级=自研采集等价供给）─────────────┘
```

### 3.1 Windows
- **唯一有原生 RDP server 的平台**（系统内置 Remote Desktop Services）
- 已启用 → agent **转发**（代理本机 3389）；未启用 → agent 做系统级开启（注册表 `fDenyTSConnections=0` + 启动 `TermService` 服务），**不自己实现 RDP 协议**
- 纯服务器约束提醒：即便 Windows，agent 也只是"开开关"，RDP 协议栈完全由操作系统提供

### 3.2 Linux
- 有 xrdp（运行中——服务级检测，非端口探测）→ 转发
- 无 → agent **拉起 xrdp**（第三方原生服务进程，agent 只负责启动/停止，不实现协议）
- xrdp 不可用/安装受限 → **降级自研采集推流**（复用 `AgentService` 的 `NativeScreenCapture` X11 采集 + JPEG/H264 编码，`Robot`/XTest 注入——等价桌面能力，非 RDP 协议）

### 3.3 Mac
- **无原生 RDP，也无成熟的第三方 RDP server** → 直接走**自研采集推流**（CoreGraphics 采集 + 编码 + CGEvent 注入）
- 不存在「转发已有服务」分支

---

## 4. 会话流程（控制端 → 网关 → agent → 被控机）

```
① 注册：agent 部署被控机 → 自采集（平台/RDP 服务状态/编码能力/凭据）→ 注册网关（上报 AgentInfo）
② 接入：控制端浏览器连网关（令牌/验证码）→ 选被控端 → 选连接方式 远程桌面(RDP) → 连接
③ 会话：网关验凭据 → 创建会话（sessionId）→ 转发 rdp-start 帧到 agent
④ 供给：agent 按 3 的平台分支确保桌面可达（转发原生 RDP / 拉起 xrdp / 降级自研采集）
⑤ 桌面（两条数据面，按供给方式二选一）：
   a. 自研采集流（主路径，与 VNC PUSH 一致）：
      画面回流：agent 桌面采集 → 编码帧(JPEG/H264) → agent → 网关 → 控制端画面区渲染
      输入注入：控制端键鼠 → 网关 → agent → 注入被控机桌面（Robot/XTest/CGEvent）
   b. 原生 RDP 码流（未来直传）：RDP 码流（RemoteFX 等）→ agent → 网关（需 RDP 解码转码，
      当前原生库仅 h264/h265——见 进度和计划.md 遗留备忘）
⑥ 关闭：stop → 会话清理；自启的 xrdp / 系统远程桌面开关按策略回收（会话结束即停 / 保活）
```

> **⚠️ 画面实时性（硬要求）**：与 VNC 相同——控制端 ↔ 网关 ↔ agent 的通道**必须实时**，
> 帧即采即转即渲染，键鼠注入即时双向流转，不允许攒批/延迟刷屏。
>
> **⚠️ 控制端协议约束（进度和计划.md 已确认）**：SSH 走 WebSocket 信令通道，**远程桌面走 WebRTC 媒体通道**——RDP/VNC 桌面帧最终应经 WebRTC 到浏览器（WebRTC 落地前暂用 WS 传帧）。

---

## 5. 与现状代码的差距（如实）

| 现状 | 正确业务 | 差距 |
|---|---|---|
| `AgentShellService.startRDPSession()`：SSH 隧道 + 启动 **freerdp 进程**（`SSHTunnelManager.startFreerdp`，连 `127.0.0.1:rdpLocalPort`） | 检测 → 转发/开启系统 RDP，画面回传控制端 | **方向不符——freerdp 是本地开窗的 RDP 客户端，不是远程回传**；且把 agent 当作连接第三方 RDP 目标机的跳板，与「agent 在被控机本机」语义混淆 |
| `MessageType` 只有 `SIGNAL/DATA/CTRL/SSH/VNC`，**无 RDP 帧类型** | 网关需 `rdp-start/input/stop` 帧路由 | **缺失——需新增 RDP 帧类型 + `FrameCodec.rdpFrame()`** |
| 网关 `ControllerWebSocketServer` 仅路由 `/ws/ssh/{agentId}`、`/ws/vnc/{agentId}` | 浏览器 RDP 接入 | **缺失——需增加 `/ws/rdp/{agentId}`（WebRTC 落地后为信令面）** |
| 无「RDP 服务可用性检测 + 系统开启/拉起 xrdp」组件 | Windows 开 TermService / Linux 拉 xrdp | **缺失——需新增检测/开启组件（注意：受纯服务器约束，只能操纵原生服务，不能 Java 实现 RDP）** |
| `AgentService`（PUSH 模式）已有自研桌面采集（GDI/X11/CG + JPEG/H264） | 降级路径的画面供给 | **可复用——作为 RDP 不可达时的等价供给** |
| `InputInjector`（controller 模块）为空实现 | 键鼠注入 | 需 agent 侧真实注入（同 VNC 的 `VncInputInjector`，可共用） |
| 前端：`apps/vue-support-remote-starter/RemoteView.vue` 仅 ssh/vnc；`pages/server` 的 Guacamole 栈（`ScGuacViewer`）支持 rdp 但走 guacd | 控制端 RDP 画面区 + 键鼠 | **RemoteView 缺 RDP 选项**；自研路径渲染与 VNC 相同（canvas JPEG/H264），无需 guacd |

---

## 6. 实施任务清单（可勾选——完成一项勾一项）

> RDP 是三者中唯一**未动工**的通道（协议/网关/agent/前端全缺）。骨架尽量复用已落地的 SSH/VNC 模块。

### 6.1 协议层（remote-protocol / remote-core）

- [ ] `MessageType` 增加 `RDP` 枚举值
- [ ] `FrameCodec.rdpFrame()` 辅助方法（对齐 `sshFrame`/`vncFrame`）
- [ ] 元数据键约定：`rdpAction`（start/input/stop）+ `rdpSessionId`（对齐 vnc）

### 6.2 网关（remote-gateway）

- [ ] `GatewayServer.handleRDP`——frame/started/error/stopped 回流控制端，其余路由 agent（照抄 `handleVNC` 骨架）
- [ ] `ControllerWebSocketServer`——`/ws/rdp/{agentId}` 接入 + rdpSessions 路由表 + started 状态 JSON 下发
- [ ] path 白名单放行 `/ws/rdp/*`（当前 `path must be /ws/{ssh|vnc}/{agentId}` 会拒绝）
- [ ] rdp 会话与 `SessionManager` 鉴权闭环（**与 ssh/vnc 共同欠账**——一并补）
- [ ] **WebRTC 媒体通道**（既定方向：桌面帧走 WebRTC，WS 仅信令——rdp/vnc 共同项）

### 6.3 agent 检测与供给（受纯服务器约束——只操纵原生服务，不实现 RDP 协议）

- [ ] 新建模块 `utils-support-remote-agent-rdp`（对齐 agent-ssh/agent-vnc 结构与 pom）
- [ ] `RdpServiceProbe`——平台类型检测
- [ ] `RdpServiceProbe`——Windows：`TermService` 服务状态 + 注册表 `fDenyTSConnections`（**服务级检测，非端口 3389 探测**）
- [ ] `RdpServiceProbe`——Linux：`ps`/systemd 查 xrdp；Mac：恒 false
- [ ] `RdpServiceManager`——平台分支（Windows 已启用→转发 / 未启用→系统开启；Linux 有 xrdp→转发 / 无→拉起 / 不可得→降级自研采集；Mac→直接降级）
- [ ] `RdpServerStarter`——Windows 系统级开启远程桌面（`fDenyTSConnections=0` + 启 `TermService`，需管理员权限的失败路径处理）
- [ ] `RdpServerStarter`——Linux 拉起 xrdp（systemctl / 直接进程，含 `-forever` 式保活参数决策）
- [ ] 自启服务会话结束回收策略（对齐 ssh/vnc 6.1 共同项）
- [ ] 降级判定上报（`rdpAvailable=false` + `desktopSupported=true` → 控制端提示走自研画面）

### 6.4 agent 会话通道

- [ ] `RdpSessionChannel`——或**与 `VncSessionChannel` 合并为 `DesktopSessionChannel`**（采集/推帧/注入骨架完全同构，仅帧类型与供给分支不同——先决策再动手）
- [ ] 降级路径：`ScreenCapture` → `NativeEncoder` JPEG/H264 采集推流（复用，无新代码则勾选验证即可）
- [ ] 键鼠注入：复用 `VncInputInjector`（抽出共享注入组件到公共依赖，避免双份 Robot 代码）
- [ ] handleRdpFrame 帧分派（start/input/stop）+ started/error/stopped 状态帧
- [ ] 原生 RDP 码流直传（**未来项**——需 RDP 解码转码，当前原生库仅 h264/h265；见 进度和计划.md 遗留备忘）

### 6.5 agent 注册（RdpAgentBootstrap）

- [ ] `RdpAgentBootstrap`——连网关 → 挂 `MessageType.RDP` 监听 → 注册（agentType + platform + `rdpAvailable` + `desktopSupported` extra）
- [ ] 心跳（对齐 SshAgentBootstrap 5s tick）
- [ ] `main` 入口 + stop 清理 + 保活循环

### 6.6 前端（vue-support-remote-starter / RemoteView.vue）

- [ ] 连接方式增加「远程桌面(RDP)」（当前仅 ssh/vnc）
- [ ] WS 路径 `/ws/rdp/{agentId}` + 键鼠/画面消息（渲染逻辑与 VNC 完全同构——canvas + JPEG，先复用）
- [ ] H264 解码渲染（与 VNC 共同项）
- [ ] 坐标换算与分辨率自适应复核（与 VNC 共同项）

### 6.7 残留清理

- [ ] 归档/移除 `AgentShellService.startRDPSession` + `SSHTunnelManager.startFreerdp`（freerdp 本地开窗——方向不符；仅当确有「第三方 RDP 目标机跳板」需求时保留并单独明确语义）

### 6.8 端到端联调验收

- [ ] Windows 真机：**转发模式**（联调目标 192.168.200.18，RDP 3389 已确认可达）
- [ ] Windows 真机：远程桌面未开启 → agent 系统开启分支
- [ ] Linux 真机：xrdp 拉起分支
- [ ] Linux/Mac：降级自研采集分支
- [ ] 实时性验收：画面即采即渲、键鼠即时回流（文档 §4 硬要求）
- [ ] 冒烟测试补充：rdp 帧字节级用例（对齐 `RemoteCoreControllerSmokeTest` 风格）
