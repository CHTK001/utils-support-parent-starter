# utils-support-gateway-parent

远程接入网关（Remote Gateway）—— 统一管理远程代理连接、协议嗅探、会话管理与数据中继的核心模块。

> **🎨 交互式架构图**：所有架构图已迁移为 [G6 交互查看器](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)，支持鼠标滚轮缩放、拖拽平移、节点工具提示和 PNG 导出。文中每张图均标注对应的查看器章节编号。

---

## 目录

- [一、架构总览](#一架构总览)
- [二、模块结构](#二模块结构)
- [三、端口架构](#三端口架构)
- [四、数据流架构](#四数据流架构)
- [五、核心组件详解](#五核心组件详解)
- [六、SPI 扩展机制](#六spi-扩展机制)
- [七、Agent 生命周期](#七agent-生命周期)
- [八、会话生命周期](#八会话生命周期)
- [九、配置参考](#九配置参考)
- [十、快速开始](#十快速开始)

---

## 一、架构总览

> 📖 **[架构图.md / 图 1 — 整体架构总览](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：控制端 → 网关 → Agent / 持久化层的全局架构关系。

---

## 二、模块结构

```
utils-support-gateway-parent/
├── pom.xml                                          ← 父 POM
├── utils-support-remote-starter/                    ← 核心网关服务器
│   ├── src/main/java/com/chua/remote/support/
│   │   ├── gateway/
│   │   │   ├── GatewayNettyServer.java              ← 7 端口生命周期管理器
│   │   │   ├── GatewayStandalone.java               ← 独立启动器 (main)
│   │   │   ├── config/
│   │   │   │   ├── GatewayProperties.java           ← 网关配置属性
│   │   │   │   ├── GatewayConfigService.java        ← 运行时热加载配置服务
│   │   │   │   ├── Protocol.java                    ← 14 种协议枚举
│   │   │   │   └── ConnectionMode.java              ← 长连接/短连接枚举
│   │   │   ├── agent/
│   │   │   │   ├── AgentRegistry.java               ← Agent 注册表
│   │   │   │   ├── AgentInfo.java                   ← Agent 信息模型
│   │   │   │   └── AgentHeartbeatChecker.java       ← 心跳超时检测
│   │   │   ├── core/
│   │   │   │   ├── auth/
│   │   │   │   │   ├── AuthHandler.java             ← 函数式认证接口
│   │   │   │   │   ├── AclManager.java              ← ACL 权限接口
│   │   │   │   │   └── AuthenticationException.java
│   │   │   │   ├── router/
│   │   │   │   │   ├── TargetRegistry.java          ← 目标路由表
│   │   │   │   │   └── TargetEntry.java             ← 目标条目
│   │   │   │   ├── session/
│   │   │   │   │   ├── SessionManager.java          ← 会话管理器
│   │   │   │   │   ├── GatewaySession.java          ← 网关会话
│   │   │   │   │   ├── SessionStatus.java           ← 会话状态枚举
│   │   │   │   │   └── ControllerRegistry.java      ← 控制端连接管理
│   │   │   │   ├── ratelimit/
│   │   │   │   │   └── GatewayRateLimiter.java      ← Guava 令牌桶限流
│   │   │   │   ├── firewall/
│   │   │   │   │   ├── GatewayFirewall.java         ← 防火墙管理器
│   │   │   │   │   ├── IpFilterManager.java         ← IP 黑白名单
│   │   │   │   │   └── AccessLogManager.java        ← 访问日志 + QPS 统计
│   │   │   │   └── detector/
│   │   │   │       └── ProtocolDetector.java        ← 协议嗅探器
│   │   │   ├── transport/
│   │   │   │   ├── tcp/
│   │   │   │   │   ├── TcpFrontendHandler.java      ← TCP 前端接入
│   │   │   │   │   ├── TcpAgentRegisterHandler.java ← Agent 注册/心跳
│   │   │   │   │   ├── TcpRelayHandler.java         ← TCP 双向中继
│   │   │   │   │   ├── AgentTcpFrameDecoder.java    ← Agent 帧解码器
│   │   │   │   │   ├── BinaryAgentFrameHandler.java ← 桌面二进制帧处理
│   │   │   │   │   └── AgentRelayHandler.java       ← Agent 消息中继
│   │   │   │   ├── http/
│   │   │   │   │   ├── HttpManagementHandler.java   ← HTTP 管理端口
│   │   │   │   │   ├── HttpApiHandler.java          ← REST API 端口
│   │   │   │   │   ├── HttpForwardHandler.java      ← HTTP 转发
│   │   │   │   │   └── ApiGatewayProxyFilterForwarder.java
│   │   │   │   ├── ws/
│   │   │   │   │   ├── WebSocketProxyHandler.java   ← WS 通用代理
│   │   │   │   │   ├── RemoteControlWsHandler.java  ← 远程控制 WS
│   │   │   │   │   ├── LiveKitProxyHandler.java     ← LiveKit 代理
│   │   │   │   │   ├── ClientCapabilityManager.java ← 客户端编码能力
│   │   │   │   │   └── MonitorPushService.java      ← 监控推送
│   │   │   │   ├── socks5/
│   │   │   │   │   ├── ReverseSocks5GatewayHandler.java
│   │   │   │   │   ├── ReverseSocks5TunnelManager.java
│   │   │   │   │   └── Socks5AgentFrameHandler.java
│   │   │   │   └── codec/
│   │   │   │       └── H264ToJpegTranscoder.java    ← H264→JPEG 转码
│   │   │   └── ssh/
│   │   │       ├── GatewaySshReverseTunnelManager.java
│   │   │       └── Socks5AgentBootstrapManager.java
│   │   └── spi/
│   │       ├── RemoteGatewaySpi.java               ← 远程网关 SPI
│   │       ├── RemoteGatewayRequest.java
│   │       ├── RemoteGatewayResponse.java
│   │       ├── GatewayConfigStore.java             ← 配置存储 SPI
│   │       ├── GatewayFirewallProvider.java        ← 防火墙 SPI
│   │       ├── GatewayTokenVerifier.java           ← 令牌验证 SPI
│   │       └── RemoteTransportProvider.java        ← 传输提供者 SPI
│   └── pom.xml
│
├── utils-support-remote-agent-starter/              ← Agent 运行库
│   ├── src/main/java/com/chua/remote/support/agent/
│   │   ├── BaseRemoteAgent.java                    ← Agent 抽象基类
│   │   └── launch/
│   │       ├── AgentLauncher.java                  ← Agent 启动器 (main)
│   │       └── AgentProperties.java                ← Agent 配置
│   └── pom.xml
│
├── utils-support-remote-desktop-agent-starter/      ← 桌面远程 Agent
│   └── ...
├── utils-support-remote-ssh-agent-starter/          ← SSH Agent
│   └── ...
├── utils-support-remote-socks5-agent-starter/       ← SOCKS5 Agent
│   └── ...
├── utils-support-remote-control-starter/            ← 远程控制服务
│   └── ...
├── utils-support-remote-rustdesk-agent-starter/     ← RustDesk Agent
│   └── ...
└── utils-support-remote-rust-agent-starter/         ← Rust SDK Agent
    └── ...
```

---

## 三、端口架构

网关通过 **7 个端口** 提供多样化的接入方式：

> 📖 **[架构图.md / 图 2 — 端口架构](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：7 个端口及对应协议映射关系。

| 端口 | 名称 | 协议 | 用途 | 协议嗅探 |
|------|------|------|------|----------|
| 9000 | TCP-CONTROL | TCP | 前端控制连接 | ✅ SSH/HTTP/VNC/RDP/MYSQL/REDIS 自动识别 |
| 9001 | TCP-AGENT | TCP | Agent 注册/心跳/数据 | 二进制帧 (magic `0xBF`) + JSON 文本帧 |
| 3000 | HTTP-MGMT | HTTP | 管理控制台 + REST 管理 API | N/A |
| 8083 | HTTP-API | HTTP | 对外 REST API | N/A |
| 8081 | WS-API | WS | WebSocket 代理网关 | N/A |
| 8082 | DWS-REMOTE | WS+SOCKS5 | 远程控制 + LiveKit 代理 | 首字节 `0x05` → SOCKS5，否则 WS |
| 1080 | SOCKS5-GW | SOCKS5 | SOCKS5 反向代理入口 | N/A (≤0 禁用) |

---

## 四、数据流架构

### 4.1 控制端 → Agent 数据流

> 📖 **[架构图.md / 图 3 — 控制端 → Agent 数据流](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：Agent 注册 → 控制端连接 → 会话建立 → 双向中继。

### 4.2 Agent 注册序列

> 📖 **[架构图.md / 图 4 — Agent 注册序列](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：Agent 通过 TCP 9001 注册到网关的完整请求-响应流程。

### 4.3 TCP-CONTROL 端口协议嗅探流程

> 📖 **[架构图.md / 图 5 — 协议嗅探流程](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：端口 9000 自动识别 SSH / RDP / VNC / HTTP / MySQL / Redis 等协议。

---

## 五、核心组件详解

### 5.1 GatewayNettyServer — 端口管理器

> 📖 **[架构图.md / 图 6 — GatewayNettyServer 类图](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：7 端口生命周期管理器的核心字段与方法。

**职责：**
- 统一管理 7 个端口的 `start` / `stop` 生命周期
- 端口 8082 使用 `UnifiedRemoteEntryHandler` 动态嗅探 SOCKS5 vs WebSocket
- 启动后启动心跳检测器 + 控制端连接扫描器
- `stop()` 按依赖顺序释放资源：监控→心跳→Channel→线程池

### 5.2 AgentRegistry — Agent 注册表

> 📖 **[架构图.md / 图 7 — AgentRegistry 类图](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：Agent 注册表 — 身份认证、心跳驱逐、禁用管理。

**关键能力：**
- 密钥验证 (`agentRegisterKey`)
- Agent ID 冲突检测
- 验证码自动生成与复用（重连场景）
- IP 显示地址解析（SSH Bootstrap 场景）
- 心跳超时驱逐（可配置间隔 + 丢失次数上限）
- 禁用/启用管理（立即关闭在线通道）
- SOCKS5 接入开关

### 5.3 SessionManager — 会话管理器

> 📖 **[架构图.md / 图 8 — SessionManager 状态机](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：ACTIVE → RECONNECTING → CLOSED 完整生命周期。

**特性：**
- 创建时生成 UUID 会话 ID
- 支持 Agent 通道与 Client 通道的双向绑定
- 高性能原子计数器（`AtomicLongFieldUpdater`）
- 按 Agent/Channel 批量查询和关闭

### 5.4 ProtocolDetector — 协议嗅探器

| 协议 | 特征 | 模式 |
|------|------|------|
| SSH | `SSH-` 前缀 | 长连接 |
| RDP | `0x03 0x00 0x00 0x13 0x0e 0xe0 0x00 0x00` | 长连接 |
| VNC | `RFB ` 或 `RFB` | 长连接 |
| HTTP | `GET` / `POST` / `PUT` / `DELETE` / `HEAD` / `PATCH` / `OPTIONS` | 短连接 |
| HTTPS/TLS | `0x16 0x03...` | 短连接 |
| HTTP2 | `PRI * HTTP/2` | 短连接 |
| MySQL | `0x10 0x00 0x00 0x00` | 短连接 |
| Redis | `*` / `+` / `-` / `:` / `$` / `PING` / `AUTH` / `INFO` / `SET` / `GET` | 短连接 |
| 未知 | 无法识别 | 短连接 |

### 5.5 TargetRegistry — 目标路由表

- 根据 Agent 上报的 protocols × transports 自动生成目标条目
- 目标 ID = MD5(agentId + protocol + transport) 取前 12 字符
- 支持按 Protocol / AgentId 查询
- 支持 Agent 自动同步 (syncAgent)
- 心跳超时后自动清理

---

## 六、SPI 扩展机制

网关大量使用 SPI（`com.chua.common.support.spi.ServiceProvider`）实现插拔式扩展：

> 📖 **[架构图.md / 图 9 — SPI 扩展机制](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：配置存储 / 令牌验证 / 防火墙 / 传输层 SPI 的可插拔架构。

| SPI 接口 | 用途 | 默认实现 |
|----------|------|---------|
| `GatewayConfigStore` | 配置持久化读写 | `datasource` (SQLite) |
| `GatewayTokenVerifier` | API 令牌的创建/验证/管理 | `datasource` (SQLite) |
| `GatewayFirewallProvider` | IP 过滤、限流、访问日志 | `GatewayFirewall` (内存) |
| `RemoteTransportProvider` | Agent 传输层（TCP/UDP/QUIC） | TCP (Netty 内置) |

---

## 七、Agent 生命周期

> 📖 **[架构图.md / 图 10 — Agent 生命周期](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：INIT → CONNECTING → REGISTERED → ACTIVE → STOPPED 完整状态机。

**Agent 消息类型：**

| 方向 | 消息类型 | 说明 |
|------|---------|------|
| GW→Agent | `challenge` | 连接建立后的挑战令牌 |
| Agent→GW | `register` | 注册请求（agent_id + secret + protocols） |
| GW→Agent | `register` | 注册响应（OK/FAILED/DUPLICATE_ID/AUTH_FAILED） |
| Agent→GW | `heartbeat` | 心跳（agent_id + verify_code + sentAt） |
| GW→Agent | `heartbeat` | 心跳响应（OK） |
| GW→Agent | `connect` | 建立新会话（sessionId + protocol + target + auth） |
| GW→Agent | `disconnect` | 断开会话（sessionId） |
| GW→Agent | `input` / `resize` / `mouse` / `key` / `desktop_control` | 用户输入事件 |
| Agent→GW | `terminal_output` / `desktop_frame` / `connected` / `error` | 远程输出 |

---

## 八、会话生命周期

> 📖 **[架构图.md / 图 11 — 会话生命周期](./%E6%9E%B6%E6%9E%84%E5%9B%BE.html)**：从发起连接到认证、限流、目标查找、通道建立、双向中继到关闭。

---

## 九、配置参考

### 9.1 网关配置 (`GatewayProperties`)

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `tcpControlPort` | 9000 | TCP 控制端口 |
| `tcpAgentPort` | 9001 | Agent 注册端口 |
| `socks5GatewayPort` | 1080 | SOCKS5 入口 (≤0 禁用) |
| `httpManagementPort` | 3000 | 管理控制台端口 |
| `httpApiPort` | 8083 | REST API 端口 |
| `wsApiGatewayPort` | 8081 | WebSocket 网关端口 |
| `dwsRemoteControlPort` | 8082 | 远程控制 WS 端口 |
| `bossThreads` | 1 | Netty boss 线程数 |
| `workerThreads` | 0 | Netty worker 线程数 (0=CPU×2) |
| `agentHeartbeatInterval` | 30 | Agent 心跳间隔 (秒, 5-300) |
| `maxHeartbeatMisses` | 3 | 最大心跳丢失次数 (1-10) |
| `agentRegisterKey` | `gateway-agent-secret` | Agent 注册密钥 |
| `maxSessions` | 10000 | 最大并发会话数 |
| `rateLimitTokensPerSecond` | 1000 | QPS 上限 |
| `rateLimitBurstCapacity` | 2000 | 突发流量上限 |

### 9.2 Spring Boot 客户端配置 (`gateway.client.*`)

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 是否启用自动注册 |
| `host` | `127.0.0.1` | 网关地址 |
| `port` | 9001 | 网关 Agent 注册端口 |
| `secret` | `gateway-agent-secret` | 注册密钥 |
| `agent-id` | UUID (自动) | Agent ID |
| `protocols` | `[HTTP]` | 注册协议列表 |
| `http-port` | 0 | 本应用 HTTP 端口 (0=自动检测 server.port) |
| `ws-port` | 0 | 本应用 WebSocket 端口 |

---

## 十、快速开始

### 10.1 独立启动网关

```bash
# 启动独立网关（无需 Spring Boot）
java -jar utils-support-remote-starter/target/remote-gateway.jar

# 默认端口:
#   TCP-CONTROL :9000, TCP-AGENT :9001
#   HTTP-MGMT   :3000, HTTP-API    :8083
#   WS-API      :8081, DWS-REMOTE  :8082
#   SOCKS5-GW   :1080
```

### 10.2 Spring Boot 集成

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-remote-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

```yaml
# application.yml
gateway:
  tcp-control-port: 9000
  tcp-agent-port: 9001
  http-management-port: 3000
  agent-register-key: my-secret-key
```

### 10.3 Spring Boot 客户端自动注册

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>spring-support-gateway-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

```yaml
# application.yml
gateway:
  client:
    host: 192.168.1.100
    port: 9001
    secret: my-secret-key
    protocols:
      - HTTP
```

### 10.4 独立 Agent 启动

```bash
# 启动 Desktop Agent
java -jar utils-support-remote-desktop-agent-starter/target/remote-agent.jar \
  --agent.id=my-desktop \
  --gateway.host=192.168.1.100 \
  --gateway.port=9001 \
  --gateway.secret=my-secret-key \
  --agent.protocols=DESKTOP

# 启动 SSH Agent
java -jar utils-support-remote-ssh-agent-starter/target/remote-ssh-agent.jar \
  --agent.id=my-ssh \
  --gateway.host=192.168.1.100 \
  --gateway.port=9001 \
  --gateway.secret=my-secret-key \
  --agent.protocols=SSH
```

---

## 开发指南

### 编译要求

- JDK 25+
- Maven 3.9+
- Netty 4.x
- Lombok

### 编译命令

```bash
# 编译所有模块
mvn clean install -pl utils-support-gateway-parent -am

# 仅编译核心模块
mvn clean install -pl utils-support-remote-starter -am

# 跳过测试
mvn clean install -DskipTests
```

### 注意事项

1. **端口冲突**：启动前确保 7 个端口未被占用
2. **Agent 密钥**：网关和服务端 `agentRegisterKey` 必须一致
3. **防火墙**：确保端口在防火墙中放行
4. **内存**：桌面远程场景建议分配至少 256MB JVM 堆内存

---

## License

本项目基于 Apache 2.0 许可证开源。
