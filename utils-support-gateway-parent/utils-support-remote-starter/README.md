# utils-support-remote-starter

网关核心协议模块，提供接入网关、SOCKS5 反向隧道、远程控制和 Agent 注册能力。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| TCP 接入网关 | Agent 注册、心跳、二进制帧传输 |
| SOCKS5 反向隧道 | 客户端通过网关反向接入内网服务 |
| HTTP 管理 API | 管理控制台 + REST 管理接口 |
| WebSocket 代理 | 通用 WebSocket 代理转发 |
| 远程控制 | LiveKit + 远程桌面代理 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-remote-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 配置

```yaml
gateway:
  enabled: true
  tcpControlPort: 9000
  tcpAgentPort: 9001
  socks5GatewayPort: 1080
  httpManagementPort: 3000
  wsApiGatewayPort: 8081
  dwsRemoteControlPort: 8082
  httpApiPort: 8083
  managementToken: "your-token"
```

---

## 端口一览

| 端口 | 名称 | 协议 | 用途 |
|------|------|------|------|
| 9000 | TCP-CONTROL | TCP | 前端控制端信令（键盘/鼠标/窗口调整） |
| 9001 | TCP-AGENT | TCP | Agent 注册、心跳、二进制帧传输 |
| 1080 | SOCKS5-GW | SOCKS5 | 反向代理入口（客户端 -> Agent -> 目标） |
| 3000 | HTTP-MGMT | HTTP | 管理控制台 + REST 管理 API |
| 8081 | WS-API | WS | 通用 WebSocket 代理转发 |
| 8082 | DWS-REMOTE | WS | 远程控制 + LiveKit 代理 |
| 8083 | HTTP-API | HTTP | 对外 REST API（目标查询/配置读写） |

---

## SOCKS5 反向接入流程

1. 客户端连到网关 SOCKS5 端口
2. SOCKS5 用户名使用 Agent 的 `verifyCode`
3. SOCKS5 密码使用网关可验证的 token
4. 网关根据 `verifyCode` 找到在线 Agent
5. Agent 通过 `socks5-agent` 建立到目标地址的真实 TCP 连接
6. 客户端后续数据经网关转发到 Agent，再由 Agent 直连目标服务

---

## SSH 反向隧道启动临时 Agent

当远程服务器除 SSH `22` 外无法从外部访问时，控制端只调用网关完成临时 Agent 的 bootstrap：

1. 控制端提交 SSH 信息、验证码和令牌到网关
2. 网关校验令牌后 SSH 到远程服务器
3. 建立 `127.0.0.1:<remoteListenPort> -> gateway:tcpAgentPort` 的反向端口转发
4. 上传/复用 Agent jar，启动临时 Agent
5. 临时 Agent 经 SSH 隧道注册回网关

### 管理接口

```http
POST /admin/api/socks5/bootstrap
Content-Type: application/json
```

```json
{
  "sshHost": "36.133.85.79",
  "sshPort": 22,
  "sshUsername": "root",
  "sshPassword": "***",
  "agentId": "mysql-socks5-agent",
  "verifyCode": "123456",
  "token": "your-token"
}
```

---

## 配置说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `gateway.enabled` | boolean | `false` | 是否启用网关 |
| `gateway.tcpControlPort` | int | `9000` | TCP 控制端口 |
| `gateway.tcpAgentPort` | int | `9001` | TCP Agent 端口 |
| `gateway.socks5GatewayPort` | int | `1080` | SOCKS5 网关端口 |
| `gateway.httpManagementPort` | int | `3000` | HTTP 管理端口 |
| `gateway.wsApiGatewayPort` | int | `8081` | WebSocket API 端口 |
| `gateway.dwsRemoteControlPort` | int | `8082` | 远程控制端口 |
| `gateway.httpApiPort` | int | `8083` | HTTP API 端口 |
| `gateway.managementToken` | String | - | 管理令牌 |

---

## 依赖关系

```
utils-support-remote-starter
├── utils-support-common-starter  # 核心基础
├── utils-support-netty-starter   # Netty 网络框架
└── netty-all                     # Netty 核心
```

---

## 轻量化说明

已移除以下重依赖以减小体积：

- `webrtc-java`：全库无 Java 代码引用，仅作 JSON 透传
- `javafx-swing` / `javafx-web`：全库无 JavaFX 代码
- `sshd-core`：SSH 功能已由 `jsch` 和 `utils-support-ssh-starter` 覆盖
- `utils-support-netty-starter`：网关直接使用 `netty-all` 原生 API

`ffmpeg-javacv-starter` 改为 `<optional>true</optional>`，仅在需要 H.264/H.265 转码时引入。
