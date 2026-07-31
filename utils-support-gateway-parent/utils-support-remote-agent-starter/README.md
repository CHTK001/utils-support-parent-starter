# utils-support-remote-agent-starter

远程 Agent 启动器，提供 TCP 注册、SSH、桌面共享和 SOCKS5 反向隧道能力。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| TCP 注册 | Agent 通过 TCP 连接网关并注册 |
| SSH 代理 | 基于 JSch 的 SSH 终端代理 |
| 桌面共享 | 远程桌面画面采集与传输 |
| SOCKS5 反向隧道 | 通过网关反向接入内网服务 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-remote-agent-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 启动 Agent

```bash
java -jar utils-support-remote-agent-starter.jar \
  --gateway.host=127.0.0.1 \
  --gateway.port=9001 \
  --agent.id=my-agent \
  --agent.secret=gateway-agent-secret \
  --agent.protocols=SSH,DESKTOP,SOCKS5 \
  --agent.transport=TCP
```

---

## SOCKS5 反向隧道

`NettySocks5AgentService` 接收网关下发的指令：

| 指令 | 说明 |
|------|------|
| `socks_connect` | 网关发起连接请求，指定目标 host 和 port |
| `socks_data` | 双向数据转发 |
| `socks_close` | 关闭连接 |

### 工作流程

1. 网关发起 `socks_connect`，指定目标 `host` 和 `port`
2. Agent 用本机网络连接目标服务（MySQL / HTTP / 其他 TCP）
3. 连接成功后，Agent 回传 `socks_connected`
4. 后续字节通过 `socks_data` 双向转发
5. 连接关闭时回传 `socks_closed`

---

## 配置说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `gateway.host` | 网关地址 | `127.0.0.1` |
| `gateway.port` | 网关 TCP Agent 端口 | `9001` |
| `agent.id` | Agent 唯一标识 | - |
| `agent.secret` | Agent 认证密钥 | - |
| `agent.protocols` | 支持的协议列表 | `SSH,DESKTOP,SOCKS5` |
| `agent.transport` | 传输协议 | `TCP` |

---

## 运行约定

- Agent 通过 `AgentLauncher` 启动
- `transport` 需要包含 `TCP`
- SOCKS5 目标连接由 Agent 所在机器的网络环境决定

---

## 依赖关系

```
utils-support-remote-agent-starter
├── utils-support-common-starter    # 核心基础
├── utils-support-ssh-starter       # SSH 支持
├── netty-all                       # 网络通信
└── jsch                            # SSH 客户端
```
