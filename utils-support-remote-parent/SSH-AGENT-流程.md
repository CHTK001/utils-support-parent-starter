# SSH Agent 部署与流程（业务定义）

> 状态：业务逻辑定稿（按产品定义梳理）
> 关联：`utils-support-remote-agent`（agent 端 SSH 能力）

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

## 5. 与现状代码的差距（如实）

| 现状（`utils-support-remote-agent`） | 正确业务 | 差距 |
|---|---|---|
| `AgentShellService` 启动 **TelnetServer（4567）** | 与 SSH 无关 | **错误残留——SSH 流程绝不涉及 telnet，应移除** |
| `SSHChannelManager` 收到 ssh-start 后 **spawn 进程**（Linux `bash` / Windows `cmd.exe` / 远程 `sshpass ssh`） | 转发已有 sshd / 自启 sshd | **方向不符——应改为「检测 → 转发/自启 SSH 服务」** |
| `SSHTunnelManager`（SshClient 正向/反向隧道） | 转发语义相关，但绑定隧道客户端 | 需对齐「代理本机 sshd」的转发语义 |
| 无「sshd 可用性检测 + 自启」组件 | Linux/Mac 无 sshd → 自启 | **缺失——需新增采集/检测/自启组件** |

---

## 6. 重写方向（下一步实现清单）

1. **移除**：TelnetServer（4567）从 SSH 流程剥离（与 SSH 无关的残留）
2. **新增**：`SshServiceProbe`——采集平台类型 + 检测本机 sshd **服务**（`systemctl status sshd` / 进程 / 配置——**非端口 22 探测**）
3. **新增**：`SshServiceManager`——平台分支：
   - Linux/Mac：有 sshd → 转发模式；无 → 自启 sshd（拉起 sshd 进程）
   - Windows：直接自启 SSH 服务器（Windows 无原生 sshd 时 agent 补位）
4. **改造**：`SSHChannelManager`——从「spawn 本地进程」改为「连接本机 SSH 服务（转发/自启后的 22）」做会话代理，输出/输入经 SSH 通道
5. **上报**：采集结果（平台 / sshd 状态 / 凭据）随 `AgentInfo` 注册上报
