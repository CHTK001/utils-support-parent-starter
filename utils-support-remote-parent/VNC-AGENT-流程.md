# VNC Agent 部署与流程（业务定义）

> 状态：业务逻辑定稿（按产品定义梳理，2026-09-10 重写）
> 关联：`utils-support-remote-agent-vnc`（agent 端 VNC 桌面能力）
> 连接方式：前端下拉 `SSH` / `SSH 直连（隐藏）` / `VNC` / `RDP`

---

## 0. 两种连接方式（必须分清）

| 连接方式 | 协议 | agent 职责 | 控制端职责 |
|---|---|---|---|
| **自定义**（已实现） | 自研私有帧协议 | `ScreenCapture`(GDI/X11) 采集桌面 → `NativeEncoder`(JPEG/H264) 编码 → 帧推网关；`java.awt.Robot` 注入键鼠 | `canvas` 渲染 JPEG 帧 + 键鼠 JSON 上行 |
| **VNC**（标准 RFB 协议） | RFB 3.3/3.7/3.8（端口 5900） | 检测本机 VNC 服务 → 没有就**启动一个**（rustvncserver / tigervnc / x11vnc）；agent 只确保 VNC server 可用，**不做采集、不做注入** | Java RFB 客户端连 VNC server → 画面帧按 RFB 协议解析 → 转 web；键鼠按 RFB 协议发回 VNC server |

**本文档只描述 VNC 模式（标准 RFB 协议）。自定义模式的实现已独立存在（`VncSessionChannel`）。**

---

## 1. 总则（VNC 模式）

**VNC 模式的核心是：agent 部署到被控机后，检测/自启 VNC server（RFB 服务端），让被控机具备可达的 RFB 桌面服务。控制端通过标准 RFB 客户端连接，画面/键鼠走 RFB 协议。**

- agent 是**被控机上的 VNC 服务供给方**：检测有没有 VNC server → 没有就启动（rustvncserver / com.sshtools:rfb-server / tigervnc）
- **agent 不做桌面采集、不注入键鼠**——这些由 VNC server + RFB 客户端完成
- 控制端通过 WebSocket 把 RFB 帧转发到浏览器（或走 WebRTC 媒体通道）

---

## 2. agent 启动：自采集 VNC 服务信息

agent 启动（部署到被控机）后采集：

| 采集项 | 内容 | 用途 |
|---|---|---|
| 平台类型 | `Linux` / `Mac`（类 Unix）/ `Windows` | 决定 VNC server 启动方式 |
| VNC 服务可用性 | 本机 **VNC server 是否在运行**（进程级检测，非端口 5900 探测） | 有 → 转发复用；无 → 自启 |
| 桌面环境 | 是否有图形会话（`DISPLAY` / Windows 交互式桌面） | 判定 VNC server 可否启动 |

采集完成后把结果随 `AgentInfo` 注册上报到网关。

---

## 3. 平台分支逻辑（核心决策）

```
agent 自采集平台类型 + VNC 服务可用性
         │
         ├── Linux/Mac ──────────────────────────────┐
         │      ├─ 已有 VNC server（运行中）→ 转发    │
         │      └─ 无 VNC server → 自启 VNC server   │
         │                                            │
         └── Windows ────────────────────────────────┤
                ├─ 已有 VNC server → 转发             │
                └─ 无 VNC server → 自启 rustvncserver │
                   / com.sshtools:rfb-server          │
                                                       │
    三种结果统一：被控机具备可达的 RFB 服务             │
    （转发=复用已有；自启=agent 补位）─────────────────┘
```

### 3.1 Linux/Mac
- **有 VNC server（进程级检测）**：转发模式（复用已有，不重复起服务）
- **无 VNC server**：自启 VNC server
  - 优先 `rustvncserver`（native-parent 编译，跨平台统一）
  - 其次 `x11vnc -display $DISPLAY -forever -bg`
  - 兜底 `tigervncserver`

### 3.2 Windows
- **有 VNC server（UltraVNC/TightVNC 进程检测）**：转发
- **无 VNC server**：自启 `rustvncserver`（native-parent 编译）或 `com.sshtools:rfb-server`
- **RDP 优先**：Windows 更适合走 RDP（已有原生支持），VNC 作为备选

---

## 4. 会话流程（控制端 → 网关 → agent → VNC server）

```
① 注册：agent 部署被控机 → 自采集（平台/VNC 服务状态）→ 注册网关（上报 AgentInfo）
② 接入：控制端浏览器 → 网关 HTTP 验证 → 选被控端 → 选连接方式 VNC → WebSocket 建立
③ 会话：网关验凭据 → 创建会话（sessionId）→ 转发 vnc-start 帧到 agent
④ 供给：agent 按平台分支确保 VNC 服务可达（转发已有 / 自启 VNC server）
⑤ 桌面：
   - 控制端 Java RFB 客户端连 VNC server（经 agent 或直连）
   - RFB 画面帧 → 解码 → 转 web canvas
   - 键鼠事件 → RFB 协议 → VNC server → 被控机桌面
⑥ 关闭：stop → 会话清理；自启的 VNC server 按策略回收
```

---

## 5. 与 SSH 模块的对称性

| SSH 模块 | VNC 模块（对标实现） |
|---|---|
| `SshServiceProbe` 检测 sshd | `VncServiceProbe` 检测 VNC server |
| `SshServerStarter` 自启 sshd | `VncServerStarter` 自启 VNC server |
| `SshServiceManager` 转发/自启决策 | `VncServiceManager` 转发/自启决策 |
| `SshSessionChannel` SSH 会话代理 | `VncSessionChannel`（标准 RFB 桥接——待实现） |
| `SshAgentBootstrap` 入口 | `VncAgentBootstrap` 入口 |

---

## 6. 待实现（下一步）

### 6.1 核心组件
- [ ] `VncServerStarter` 改用 `rustvncserver`（native-parent）/ `com.sshtools:rfb-server`（Maven）启动 VNC server
- [ ] `VncSessionChannel` 改为标准 RFB 桥接：Java RFB 客户端连接 VNC server → 画面帧解析 → 通过帧协议推网关
- [ ] 删除 `VncInputInjector`（Robot 注入），键鼠走 RFB 协议

### 6.2 控制端
- [ ] Java RFB 客户端：连接 VNC server，解析 RFB framebuffer，转 JPEG/原始帧推 web
- [ ] 前端 canvas：接收帧渲染 + 键鼠事件下行

### 6.3 依赖
- [ ] native-parent：`utils-support-native-vnc`（rustvncserver 编译产物）
- [ ] Maven：`com.sshtools:rfb-server`（可选替代方案，Maven Central 验证可用性）

### 6.4 协议对齐
- [ ] RFB 版本协商（3.3/3.7/3.8）
- [ ] 帧编码支持（Raw/RRE/Hextile/ZRLE）
- [ ] 安全类型（None/VNC Auth/Tight Auth）

---

## 7. 与「自定义」模式的区别

| 维度 | 自定义（已实现） | VNC（待实现） |
|---|---|---|
| 画面采集 | agent 自研 `ScreenCapture` | VNC server 自己采集 |
| 键鼠注入 | `java.awt.Robot` | VNC server（RFB 协议） |
| 协议 | 自研私有帧协议 | 标准 RFB 3.8 |
| 互通性 | 仅我们的控制端 | 任何 RFB 客户端（TigerVNC Viewer 等）均可连 |
| 依赖 | JNA(GDI/X11) + NativeEncoder | VNC server 进程 + RFB 客户端库 |
| 延迟 | 采集→编码→帧推网关（3跳） | RFB 原生帧传输（更少跳数，更低延迟） |
