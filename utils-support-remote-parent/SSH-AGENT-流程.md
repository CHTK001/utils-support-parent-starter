# SSH Agent 部署与流程（业务定义）

> 状态：业务逻辑定稿 + 实施任务清单（2026-09-09 按代码现状复核）
> 关联：`utils-support-remote-agent-ssh`（agent 端 SSH 能力——重写已落地于此新模块）

---

## 1. 总则

**SSH 模式的核心是：agent 部署到被控机后，自己采集服务器信息，按平台决定如何提供 SSH 会话能力。**

- agent 是**被控机上的代理**：负责采集、检测、转发或自启 SSH 服务
- 会话内容：控制端通过 SSH 与被控机交互（Linux/Mac 的 shell、Windows 的 PowerShell/CMD）
- **与 telnet 毫无关系**——SSH 走 22/SSH 协议，绝不经过 telnet

---

## 2. agent 启动：自采集服务器信息

agent 启动（部署到被控机）后采集：

| 采集项 | 内容 | 用途 |
|---|---|---|
| 平台类型 | `Linux` / `Mac`（类 Unix）/ `Windows` | 决定分支逻辑 |
| SSH 服务可用性 | 本机 **sshd 服务是否在运行**（服务级检测——**非端口 22 探测**：`systemctl status sshd` / `ps -ef \| grep sshd` / sshd 配置检查） | 决定「转发」还是「自启」 |
| 系统信息 | 内核/发行版/架构 | 上报能力模型 |
| 凭据 | SSH 用户名/密码（配置或上报） | 会话鉴权 |

采集完成后把结果随 `AgentInfo` 注册上报到网关（agent 侧注册帧）。

---

## 3. 平台分支逻辑（核心决策）

```
agent 自采集平台类型 + SSH 服务可用性
         │
         ├── Linux ─────────────────────────────────────────┐
         │      ├─ 已有 sshd 服务（运行中）→ 转发（代理本机 sshd）│
         │      └─ 无 sshd 服务         → 自启 sshd（agent 拉起）│
         │                                                    │
         ├── Mac（类 Unix）──────── 同 Linux 逻辑 ────────────┤
         │                                                    │
         └── Windows ────────────────────────────────────────┤
                Windows 自身不具备 SSH 服务                    │
                → agent 直接启动 SSH 服务器（自启）            │
                                                              │
   三种结果统一：被控机具备可达的 SSH 服务                     │
   （转发=复用已有；自启=agent 补位）──────────────────────────┘
```

### 3.1 Linux
- **有 sshd 服务（运行中——服务级检测，非端口探测）**：agent **转发**——控制端的 SSH 会话经 agent 代理到本机已有 sshd，不重复起服务
- **无 sshd 服务**：agent **自己启动 sshd**（拉起 SSH 服务进程），再走转发

### 3.2 Mac（其它类 Unix）
- **与 Linux 完全相同的逻辑**：有 sshd 服务 → 转发；无 → 自启

### 3.3 Windows
- **Windows 自身不具备 SSH 服务** → agent **直接启动 SSH 服务器**（自启一个 SSH server 进程，提供 SSH 能力）
- 不存在「转发已有服务」的分支（Windows 默认没有 sshd）

---

## 4. 会话流程（控制端 → 网关 → agent → 被控机）

```
① 注册：agent 部署被控机 → 自采集（平台/sshd 服务状态/凭据）→ 注册网关（上报 AgentInfo）
② 接入：控制端浏览器连网关（令牌/验证码）→ 选被控端 → 选连接方式 SSH → 连接
③ 会话：网关验凭据 → 创建会话（sessionId）→ 转发 ssh-start 帧到 agent
④ 供给：agent 按 3 的平台分支确保本机 SSH 服务可达（转发已存在 / 自启缺失）
⑤ 终端：控制端 ws ↔ 网关 ↔ agent ↔ SSH 服务（**WS 会话必须实时**）：
        - 输出回流：SSH 会话输出 → agent → 网关 → 控制端终端区（Linux bash / Windows shell）
        - 输入：键盘 → 控制端 → 网关 → agent → SSH 会话 stdin
⑥ 关闭：stop → 会话清理；若为自启的 SSH 服务按策略回收（会话结束即停 / 保活）
```

> **⚠️ WS 会话实时性（硬要求）**：控制端 ↔ 网关 ↔ agent 的 WebSocket 通道**必须是实时的**——
> 输出/输入**帧即收即转即渲染**，无轮询、无批量缓冲延迟；终端回显与输入必须即时双向流转
> （一次按键一次传输，一块输出一次回流），不允许攒批/延迟刷屏。

---

## 5. 现状复核（2026-09-09，按代码如实）

重写方向（原第 6 节清单）**已基本落地**，落在 `utils-support-remote-agent-ssh` 新模块：

| 原差距项 | 现状 | 结论 |
|---|---|---|
| TelnetServer（4567）错误残留 | `AgentShellService`（套壳模式）仍集成 TelnetServer | **待清理/剥离** |
| `SSHChannelManager` spawn 本地进程 | `SshSessionChannel` 已改为「连接本机 127.0.0.1:22」，系统 `ssh` 命令 + PTY，免密 key（ssh-keygen + authorized_keys），输出虚拟线程即读即发 | ✅ 方向对齐 |
| 无 sshd 检测/自启组件 | `SshServiceProbe`（服务级检测）/ `SshServiceManager`（平台分支）/ `SshServerStarter`（systemctl/service/launchctl/Windows OpenSSH 服务）已实现 | ✅ |
| 采集结果随 AgentInfo 上报 | `SshAgentBootstrap` 注册上报（platform/sshdAvailable extra + 心跳） | ✅ |
| 网关路由 | `MessageType.SSH` + `FrameCodec.sshFrame` + `GatewayServer.handleSSH` + `ControllerWebSocketServer` `/ws/ssh/{agentId}` | ✅ |
| 旧通道并存 | `AgentShellService`/`SSHChannelManager`（spawn 流程）与新通道并存 | **待归档，避免双通道** |
| resize/鉴权/Windows 补位 | 未实现/宽松 | **待补** |

---

## 6. 实施任务清单（可勾选——完成一项勾一项）

### 6.1 agent 检测与供给

- [x] `SshServiceProbe`——平台类型检测（Linux/Mac/Windows）
- [x] `SshServiceProbe`——sshd **服务级检测**（非端口 22 探测：systemctl/进程/配置）
- [x] `SshServiceProbe.collectCredentials`——凭据采集
- [x] `SshServiceManager`——平台分支（有 sshd → 转发；无 → 自启；Windows 直接自启）
- [x] `SshServerStarter`——Linux 自启（`systemctl start ssh/sshd` → 兜底 `service`）
- [x] `SshServerStarter`——Mac 自启（launchctl / systemctl）
- [x] `SshServerStarter`——Windows 自启（`Start-Service sshd` / `net start sshd`）
- [ ] Windows **未安装** OpenSSH Server 时的安装补位（`Add-WindowsCapability Online Name OpenSSH.Server`）
- [ ] 自启服务回收策略落地（会话结束即停 / 保活——文档 §4⑥ 要求，当前无停回逻辑）
- [ ] 无权限自启时的失败上报语义（error 帧内容规范化）

### 6.2 agent 会话通道（SshSessionChannel）

- [x] `handleSSHFrame` 帧分派（start / input / stop）
- [x] start：meta host 优先，local 走 `ensureSshService()` 供给（转发已有 / 自启缺失）
- [x] 免密 key 准备（ssh-keygen ed25519 + 装入 authorized_keys，`ssh -i` 认证）
- [x] 系统 `ssh` 命令 + PTY（cols/rows）实时双向
- [x] 输出即读即发（虚拟线程 readLoop，不攒批）+ started/error/stopped 状态帧
- [ ] **resize 帧**支持（终端尺寸变更 → 重新按 cols/rows 拉起/同步 PTY）
- [ ] 进程异常退出检测 → 主动推 stopped 帧（当前仅 stop 动作触发）
- [ ] 会话空闲超时回收
- [ ] Windows 会话真机验证（依赖目标机 OpenSSH client；PowerShell/CMD 默认 shell 确认）
- [ ] 远程直连分支（meta host 非 local，`sshpass`/凭据路径）验证——当前免密 key 只覆盖本机用户

### 6.3 注册与上报

- [x] `SshAgentBootstrap` 启动：连网关 → 挂 `MessageType.SSH` 监听 → 注册（agentType=FORWARD + platform + `sshdAvailable` extra）
- [x] 心跳日志（5s keepalive tick）
- [x] `stop()`：stopAll + disconnect（含 main 线程栈诊断日志）
- [ ] `collectCredentials` 结果接入 `AgentInfo.username/password` 完整流转（注册 → 网关 → 控制端会话参数）

### 6.4 网关与协议

- [x] `MessageType.SSH` 帧类型
- [x] `FrameCodec.sshFrame()` 辅助方法
- [x] `GatewayServer.handleSSH`——output/started/error/stopped 回流控制端，其余路由 agent
- [x] `ControllerWebSocketServer`——`/ws/ssh/{agentId}` 接入 + sshSessions 路由表
- [ ] ssh 会话与 `SessionManager` 鉴权闭环（当前 start 仅查 sessionId 存在；需校验令牌/验证码归属）
- [ ] 并发/异常路径：agent 离线时 start 帧的错误回传给浏览器

### 6.5 残留清理

- [ ] **移除 TelnetServer（4567）** 从 SSH 流程（`AgentShellService`）——或明确其为独立 APM 通道并从本文档剥离
- [ ] 归档 `AgentShellService.startSSHSession` / 旧 `SSHChannelManager` spawn 流程（避免与新通道双轨并存）
- [ ] `SSHTunnelManager` 对齐「代理本机 sshd」转发语义（当前绑定隧道客户端场景）

### 6.6 前端（vue-support-remote-starter / RemoteView.vue）

- [x] SSH 终端基础版：WS `/ws/ssh/{agentId}` 连接 + base64 输入 + stdout/stderr 渲染
- [ ] 换 **xterm.js**（当前 div 拼 span——无光标/历史/选择复制/自动换行）
- [ ] xterm resize 联动（终端尺寸变化 → start/resize 帧带 cols/rows，配合 6.2 resize）
- [ ] 会话错误/断线重连 UI

### 6.7 端到端联调验收

- [ ] Linux 真机：**转发模式**（已有 sshd，联调机 124.221.230.112）
- [ ] Linux 真机：**无 sshd 自启**分支（停 sshd → agent 拉起 → 会话恢复）
- [ ] Windows 真机：自启 OpenSSH Server + PowerShell 会话
- [ ] 实时性验收：一次按键一次回显（无攒批延迟——文档 §4 硬要求）
- [ ] 冒烟测试补充：`SshSessionChannel` start/input/stop 帧字节级用例（沿用 `RemoteCoreControllerSmokeTest` 风格）
