# VNC Agent 部署与流程（业务定义）

> 状态：业务逻辑定稿（按产品定义梳理）
> 关联：`utils-support-remote-agent`（agent 端 VNC 桌面能力）

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

## 5. 与现状代码的差距（如实）

| 现状（`utils-support-remote-agent`） | 正确业务 | 差距 |
|---|---|---|
| `AgentShellService` VNC 分支仅做 **SSH 隧道 + 启动 vncviewer 进程**（`startVNCSession`） | 检测 → 转发/自启 VNC 服务，画面回传控制端 | **方向不符——vncviewer 是本地开窗查看，不是远程回传** |
| 无「VNC 服务可用性检测 + 自启」组件 | Linux/Mac 无 VNC → 自启 x11vnc | **缺失——需新增采集/检测/自启组件** |
| `AgentService`（PUSH 模式）已有自研桌面采集（GDI/X11/CG 采集 + JPEG/H264 编码） | Windows 无 VNC 时自研采集推流 | **可复用——下沉为通用桌面采集通道** |
| `InputInjector`（controller 模块）为空实现 | 键鼠事件注入被控机桌面 | **需在 agent 侧实现真实注入（java.awt.Robot / VNC 协议）** |

---

## 6. 重写方向（下一步实现清单）

1. **新增**：`VncServiceProbe`——采集平台类型 + 检测本机 VNC server **服务**（`ps -ef | grep -E "x11vnc|Xvnc|vncserver|tigervnc"` / Windows 进程检测——**非端口 5900 探测**）
2. **新增**：`VncServiceManager`——平台分支：
   - Linux/Mac：有 VNC server → 转发模式；无 → 自启 x11vnc（绑定当前 DISPLAY）
   - Windows：复用已装 VNC server；否则自研桌面采集推流（复用 `AgentService` 的 `NativeScreenCapture` + `NativeEncoder`）
3. **新增**：`VncSessionManager`——桌面会话生命周期：
   - `start`：按平台分支供给 VNC 服务 → 启动桌面采集循环（编码帧推送）
   - `input`：接收控制端键鼠事件 → `VncInputInjector` 注入
   - `stop`：停止采集 + 回收自启的 VNC 服务
4. **新增**：`VncInputInjector`——真实键鼠注入（`java.awt.Robot`：`keyPress/keyRelease/mouseMove/mousePress/mouseRelease`），替代 controller 侧空实现
5. **上报**：采集结果（平台 / VNC 服务状态 / 编码能力）随 `AgentInfo` 注册上报
6. **路由**：网关侧 `ControllerWebSocketServer` 增加 `vnc` 消息处理（start/input/stop），复用 SSH 的帧路由骨架（FrameCodec 编码 VNC 消息类型）
