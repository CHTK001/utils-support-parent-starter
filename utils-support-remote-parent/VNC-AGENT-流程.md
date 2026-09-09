# VNC Agent 部署与流程（业务定义）

> 状态：业务逻辑定稿 + 实施任务清单（2026-09-09 按代码现状复核）
> 关联：`utils-support-remote-agent-vnc`（agent 端 VNC 桌面能力——重写已落地于此新模块）

---

## 1. 总则

**VNC 模式的核心是：agent 部署到被控机后，自己采集/检测桌面服务可用性，按平台决定如何提供 VNC 桌面会话能力。**

- agent 是**被控机上的代理**：负责采集、检测、转发或自启 VNC 服务
- 会话内容：控制端通过 VNC 与被控机桌面交互（画面回传 + 键鼠注入）
- **与 SSH 无关**——VNC 走 RFB 协议（默认 5900 端口），agent 保证被控机具备可达的 VNC 桌面服务，供控制端连接

---

## 2. agent 启动：自采集服务器信息

agent 启动（部署到被控机）后采集：

| 采集项 | 内容 | 用途 |
|---|---|---|
| 平台类型 | `Linux` / `Mac`（类 Unix）/ `Windows` | 决定分支逻辑 |
| VNC 服务可用性 | 本机 **VNC server 是否在运行**（服务级检测：`ps -ef \| grep -E "x11vnc\|Xvnc\|vncserver\|tigervnc"` / Windows 进程/服务检测） | 决定「转发」还是「自启」 |
| 桌面环境 | 是否有图形会话（`DISPLAY` / Windows 交互式桌面） | 判定采集可行性 |
| 编码能力 | 支持的帧编码集（jpeg/h264） | 上报能力模型 |
| 凭据 | VNC 密码（配置或上报） | 会话鉴权 |

采集完成后把结果随 `AgentInfo` 注册上报到网关（agent 侧注册帧）。

---

## 3. 平台分支逻辑（核心决策）

```
agent 自采集平台类型 + VNC 服务可用性
         │
         ├── Linux ─────────────────────────────────────────┐
         │      ├─ 已有 VNC server（运行中）→ 转发（代理本机 VNC）│
         │      └─ 无 VNC server        → 自启（拉起 x11vnc）  │
         │                                                    │
         ├── Mac（类 Unix）──────── 同 Linux 逻辑 ────────────┤
         │                                                    │
         └── Windows ────────────────────────────────────────┤
                Windows 自身无 VNC server                       │
                → agent 自启 VNC server / 自研采集推流          │
                                                              │
   三种结果统一：被控机具备可达的 VNC 桌面服务                   │
   （转发=复用已有；自启=agent 补位）──────────────────────────┘
```

### 3.1 Linux
- **有 VNC server（运行中——服务级检测，非端口探测）**：agent **转发**——控制端的 VNC 会话经 agent 代理到本机已有 VNC 服务，不重复起服务
- **无 VNC server**：agent **自己启动 VNC server**（优先 `x11vnc`——直接绑定当前 `$DISPLAY`，无需登录；无 x11vnc 时尝试 tigervnc/Xvnc），再走转发

### 3.2 Mac（其它类 Unix）
- **与 Linux 完全相同的逻辑**：有 VNC server（屏幕共享）→ 转发；无 → 自启

### 3.3 Windows
- **Windows 自身不具备 VNC server** → agent **自启 VNC server**（如 UltraVNC/TightVNC 若已安装则复用，否则 agent 使用自研桌面采集推流——NativeScreenCapture GDI 采集 + JPEG/H264 编码，等价提供 VNC 画面能力）
- 不存在「转发已有服务」的分支（Windows 默认无 VNC）

---

## 4. 会话流程（控制端 → 网关 → agent → 被控机桌面）

```
① 注册：agent 部署被控机 → 自采集（平台/VNC 服务状态/编码能力/凭据）→ 注册网关（上报 AgentInfo）
② 接入：控制端浏览器连网关（令牌/验证码）→ 选被控端 → 选连接方式 远程桌面(VNC) → 连接
③ 会话：网关验凭据 → 创建会话（sessionId）→ 转发 vnc-start 帧到 agent
④ 供给：agent 按 3 的平台分支确保本机 VNC 桌面服务可达（转发已存在 / 自启缺失 / 自研采集）
⑤ 桌面：控制端 ws ↔ 网关 ↔ agent ↔ 桌面画面（**画面必须实时**）：
        - 画面回流：agent 桌面采集（VNC server 画面 / 自研 GDI+X11 采集）→ 编码帧(JPEG/H264) → agent → 网关 → 控制端画面区渲染
        - 输入注入：键盘/鼠标 → 控制端 → 网关 → agent → 注入被控机桌面（VNC 协议注入 / java.awt.Robot 注入）
⑥ 关闭：stop → 会话清理；若为自启的 VNC server 按策略回收（会话结束即停 / 保活）
```

> **⚠️ 画面实时性（硬要求）**：控制端 ↔ 网关 ↔ agent 的 WebSocket 通道**必须是实时的**——
> 画面帧即采即转即渲染，无轮询、无批量缓冲延迟；键鼠注入与画面刷新必须即时双向流转
> （一次按键一次传输，一帧画面一次回流），不允许攒批/延迟刷屏。

---

## 5. 现状复核（2026-09-09，按代码如实）

重写方向（原第 6 节清单）**已基本落地**，落在 `utils-support-remote-agent-vnc` 新模块：

| 原差距项 | 现状 | 结论 |
|---|---|---|
| `startVNCSession`（SSH 隧道 + vncviewer 本地开窗）方向不符 | `VncSessionChannel` 已实现自研采集推流（`ScreenCapture` → `NativeEncoder` JPEG/H264，按 fps 采集即编码即推）+ `VncInputInjector`（Robot key/mouse/wheel） | ✅ 主路径对齐 |
| 无 VNC 服务检测/自启组件 | `VncServiceProbe`（服务级检测 + 桌面会话检测）/ `VncServiceManager`（分支 + 自研采集降级）/ `VncServerStarter`（x11vnc→tigervnc / Mac 屏幕共享 / Windows）已实现 | ✅ |
| `InputInjector`（controller 侧）空实现 | agent 侧 `VncInputInjector` 真实注入（java.awt.Robot） | ✅ |
| 采集结果上报 | `VncAgentBootstrap` 注册（agentType=FORWARD + platform + vncAvailable/desktopSupported extra） | ✅ |
| 网关路由 | `MessageType.VNC` + `FrameCodec.vncFrame` + `GatewayServer.handleVNC` + `ControllerWebSocketServer` `/ws/vnc/{agentId}` | ✅ |
| 「转发已有 VNC server」语义 | **未实现**——会话画面一律走自研采集，已检测到的本机 VNC server（vncPresent=true）并未被会话消费（浏览器不解 RFB 协议） | **待澄清/对齐**（见 6.1） |
| 旧通道并存 | `AgentShellService.startVNCSession`（vncviewer 本地开窗）仍在 | **待归档** |
| H264 前端解码 | `RemoteView.vue` 只识别 NAL 起始码打标，**未解码** | **待补** |

---

## 6. 实施任务清单（可勾选——完成一项勾一项）

### 6.1 agent 检测与供给

- [x] `VncServiceProbe`——平台类型检测
- [x] `VncServiceProbe`——VNC server **服务级检测**（ps 查 x11vnc/Xvnc/vncserver/tigervnc / Windows 进程——非端口 5900 探测）
- [x] `VncServiceProbe.hasDesktopSession`——桌面会话检测（DISPLAY / Windows 交互桌面）
- [x] `VncServiceManager`——平台分支（有 VNC → 转发判定；无 → 自启；自启失败降级自研采集）
- [x] `VncServerStarter`——Linux 自启（x11vnc 绑定当前 DISPLAY → 兜底 tigervncserver）
- [x] `VncServerStarter`——Mac 自启（launchctl 屏幕共享）
- [x] `VncServerStarter`——Windows 自启（已装 VNC 工具）
- [ ] **转发模式语义对齐**：`vncPresent=true`（已有 VNC server）时会话应消费该服务（RFB 代理或以之为采集源），当前实际一律走自研采集——明确「转发」在 Web 控制端下的可实现形态并落地
- [ ] 自启的 x11vnc/tigervnc 会话结束回收策略（`-forever/-bg` 启动的进程 stop 时关闭）
- [ ] headless（无 DISPLAY 且 Windows 无交互桌面）时 start 会话明确拒绝（error 帧）语义验证

### 6.2 agent 会话通道（VncSessionChannel）

- [x] `handleVncFrame` 帧分派（start / input / stop）
- [x] `CaptureLoop`——按帧率上限（默认 15fps）采集 → 编码 → frame 帧即推（不攒批）
- [x] 编码经 `NativeEncoder`（协商编码集 h264/jpeg）
- [x] `VncInputInjector`——Robot 注入 key/mouse/wheel（即收即注入）
- [ ] **H264 帧前端解码**（RemoteView 仅打标未解码——JMuxer / ffmpeg.wasm / WebCodecs 选型）
- [ ] 增量帧/脏区优化（当前全屏帧每帧全量编码，带宽高）
- [ ] 分辨率变化 / 多屏场景处理（尺寸变更帧 + 前端 canvas 重置）
- [ ] 采集帧率/质量接入网关协商结果（`NegotiatedCodec.quality/fps`——当前固定 DEFAULT_FPS）
- [ ] stop 后采集线程回收验证（CaptureLoop 中断 + encoder close 传播）

### 6.3 注册与上报

- [x] `VncAgentBootstrap` 启动：连网关 → 挂 `MessageType.VNC` 监听 → 注册（platform + vncAvailable + desktopSupported extra）
- [x] `stop()`：stopAll + disconnect
- [ ] 编码能力上报完整性（`encodingCapability` 帧编码集与实际 `NativeEncoder` 可用 SPI 一致性——未装 h264 native 时不误报）
- [ ] main 保活循环（`while running sleep`）与 stop 语义复核

### 6.4 网关与协议

- [x] `MessageType.VNC` 帧类型
- [x] `FrameCodec.vncFrame()` 辅助方法
- [x] `GatewayServer.handleVNC`——frame/started/error/stopped 回流控制端，其余路由 agent
- [x] `ControllerWebSocketServer`——`/ws/vnc/{agentId}` 接入 + vncSessions 路由表 + started 状态 JSON 下发
- [ ] vnc 会话与 `SessionManager` 鉴权闭环（当前 start 不校验 sessionId 归属/令牌）
- [ ] **WebRTC 媒体通道**（既定方向：桌面帧走 WebRTC，WS 仅信令——见 进度和计划.md）

### 6.5 前端（vue-support-remote-starter / RemoteView.vue）

- [x] VNC canvas 基础版：WS `/ws/vnc/{agentId}` 连接 + JPEG 帧 `createImageBitmap` 渲染 + 键鼠事件 base64 JSON 上行
- [ ] H264 解码渲染（配合 6.2）
- [ ] 键鼠事件映射完整性验证（keyCode → Robot 键位表全量覆盖，含中文输入法场景）
- [ ] 滚轮/右键/拖拽端到端验证
- [ ] 画面分辨率自适应 canvas 缩放与坐标换算复核（当前坐标直传是否按缩放换算）

### 6.6 残留清理

- [ ] 归档 `AgentShellService.startVNCSession`（SSH 隧道 + vncviewer 本地开窗——方向不符的旧实现）
- [ ] `InputInjector`（controller 模块空实现）删除或改为真实实现

### 6.7 端到端联调验收

- [ ] Linux 真机：**已有 VNC server** 分支（联调机 124.221.230.112）
- [ ] Linux 真机：**无 VNC 自启 x11vnc** 分支
- [ ] Linux headless：明确拒绝/降级表现
- [ ] Windows 真机：自研采集推流（GDI + JPEG）
- [ ] 实时性验收：画面帧即采即渲、键鼠即时回流（无攒批——文档 §4 硬要求）
- [ ] 冒烟测试补充：`VncSessionChannel` start/input/stop + 注入事件字节级用例
