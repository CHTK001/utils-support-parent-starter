# utils-support-kcp-starter

> utils 网络体系中的 **KCP 可靠 UDP 传输集成**：基于 `com.github.l42111996:kcp-base`（Java KCP over Netty）实现 KCP 消息服务器与消息客户端、`SyncServer` / `SyncClient` 同步通道，以及在 KCP 之上的 HTTP 风格简化调用。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，编译带 `--enable-preview`）｜包名：`com.chua.kcp.support`（子包 `server` / `client` / `sync`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 线路协议与内置参数](#4-线路协议与内置参数)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-kcp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 传输 | 基于 UDP 的 KCP 可靠传输（kcp-base 1.6.2，Netty 承载） | 不是 TCP/TLS 传输，不提供流加密 |
| 服务端 | `KcpServer` 消息服务器：主题发布/订阅、会话管理、批量下行推送 | 不是完整 HTTP 服务器（`KcpHttpServer` 仅为简化占位） |
| 客户端 | `KcpClient`：注册握手、主题订阅、消息收发、同步请求-响应 | 不含自动重连/重试编排（源码未提供） |
| 同步通道 | `KcpSyncServer` / `KcpSyncClient`：common 层 `SyncServer` / `SyncClient` SPI 的 KCP 实现 | — |
| 集成方式 | SPI 名称 `kcp`，可经 `ServerBuilder.type("kcp")` 创建 | 不含 Spring Boot 自动装配（utils 层模块，无 spring 依赖） |

**判断口径**：需要「可靠 UDP 上的消息总线 / 双向同步」时用本模块；需要完整 HTTP 语义时改用 common-starter 内置的 JDK HttpServer 等 HTTP 服务器实现（`ServerBuilder.type("jdk")`）。

### 1.3 源码结构（6 个类）

| 类 | 包 | 职责 |
| --- | --- | --- |
| `KcpServer` | `server` | KCP 消息服务器（`@Spi("kcp")`，协议分类为 `ProtocolType.KCP`，消息型，与 MQTT 同类） |
| `KcpHttpServer` | `server` | KCP 之上的 HTTP 风格简化占位服务器（内部启动一个 `KcpServer`，不做完整 HTTP 解析） |
| `KcpClient` | `client` | KCP 消息客户端，与 `KcpServer` 配对使用 |
| `KcpHttpClient` | `client` | KCP 之上的 HTTP 风格简化客户端（请求打包为 JSON，经主题 `http` 同步往返） |
| `KcpSyncServer` | `sync` | `SyncServer` / `SyncProtocol` 的 KCP 实现，委托给 `KcpServer` |
| `KcpSyncClient` | `sync` | `SyncClient` 的 KCP 实现，包装 `KcpClient`，支持连接前缓存订阅 |

---

## 2. 核心 API 与速查表

### 2.1 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起 KCP 消息服务器 | `ServerBuilder.create().type("kcp").port(19380).build()` | 返回 `Server`，`start()` / `stop()` |
| 客户端订阅与收发 | `new KcpClient("kcp://host:port")` | `subscribe` → `connect` → `send` / `close` |
| 同步请求-响应 | `KcpClient.execute(topic, message)` | 默认 5s 超时；异步用 `executeAsync` |
| 服务端广播 / 定向推送 | `KcpServer.publish(topic, msg)` / `send(clientId, topic, msg)` | 批量聚合后写出，减少 KCP 包数 |
| 在线客户端与元数据 | `getConnectedClients()` / `getClientMetadata(clientId)` | 内存会话表（`ConcurrentHashMap`） |
| 注解驱动消息处理 | `registerBean(bean)` + `@OnOpen` / `@OnMessage` / `@OnClose` / `@OnError` | `@OnMessage` 空主题值视为通配 `#` |
| Sync 双向同步通道 | `KcpSyncServer` / `KcpSyncClient`（SPI 名 `kcp`） | 配对使用；客户端支持连接前订阅缓存 |
| KCP 上的 HTTP 风格调用 | `KcpHttpClient.get(path)` / `post(path, body)` | 主题 `http` + JSON 载荷，简化实现 |

### 2.2 KcpServer（服务端）

```java
KcpServer(ServerSetting setting)                     // 也可经 ServerBuilder.type("kcp") 创建
String getProtocol()                                 // "kcp"
ProtocolType getProtocolType()                       // ProtocolType.KCP
void start() / void stop()                           // 继承 AbstractServer
void publish(String topic, Object message)           // 向所有客户端广播
void send(String clientId, String topic, Object message)  // 向指定客户端发送
List<String> getConnectedClients()                   // 已连接客户端标识
Map<String, Object> getClientMetadata(String clientId)
KcpServer onSubscribe(String topic, BiConsumer<String, String> handler)
void addListener(SyncServerListener) / removeListener(SyncServerListener)
void onConnect(Consumer<String>) / onDisconnect(Consumer<String>) / onError(Consumer<Throwable>)
KcpServer registerBean(Object handler)               // 扫描 @OnOpen/@OnClose/@OnMessage/@OnError
public static final int KCP_CONV = 0x4b4350          // KCP 会话标识（conv），两端保持一致
```

`SyncServerListener` 回调：`onClientConnected(clientId, meta)`、`onClientDisconnected(clientId)`、`onMessage(clientId, topic, payload)`。

### 2.3 KcpClient（客户端）

```java
KcpClient(String serverUrl)                          // 形如 kcp://127.0.0.1:19380，端口缺省 19380
KcpClient(String clientId, String serverUrl)
void connect()                                       // 完成 register 握手，等待确认最长 3s，失败抛 RuntimeException
void disconnect() / void close()
boolean isConnected() / String getClientId()
void send(String topic, Object message) / void publish(String topic, Object message)
String execute(String topic, Object message)                     // 同步请求-响应，默认 5s 超时
String execute(String topic, Object message, long timeoutMs)
CompletableFuture<String> executeAsync(String topic, Object message[, long timeoutMs])
KcpClient subscribe(String topic, BiConsumer<String, String> handler)
void unsubscribe(String topic)
KcpClient onMessage(BiConsumer<String, String>)      // 兜底消息监听（不经主题匹配）
KcpClient onConnect(Consumer<String>) / onDisconnect(Consumer<String>) / onError(Consumer<Throwable>)
KcpClient registerBean(Object bean)                  // @OnOpen/@OnMessage/@OnClose/@OnError
Map<String, Object> getMetadata()
```

### 2.4 KcpSyncServer / KcpSyncClient（同步通道）

```java
KcpSyncServer(ServerSetting setting)                 // 委托 KcpServer；实现 SyncServer、SyncProtocol
SyncServer createServer(ServerSetting setting)
SyncClient createClient(Object setting)              // 经 SyncAddressResolver.resolveAddress 解析地址，
                                                     // 缺省 kcp://127.0.0.1:19380
// 其余 publish / send / getConnectedClients / getClientMetadata / addListener / removeListener 同 KcpServer

KcpSyncClient(String serverUrl) / KcpSyncClient(String clientId, String serverUrl)
void connect() / disconnect() / close()              // connect 前注册的订阅会被缓存，连接后统一应用
void subscribe(String topic, SyncMessageHandler handler) / unsubscribe(String topic)
void send(String topic, Object message) / publish(String topic, Object message)
String execute(String topic, Object message[, long timeoutMs])          // 默认 5s 超时
CompletableFuture<String> executeAsync(String topic, Object message[, long timeoutMs])
void addListener(SyncFlowListener listener)          // removeListener 为空实现（底层不支持按实例移除）
KcpSyncClient onFlow(SyncFlowListener listener)
void register(Map<String, Object> meta)              // 写入客户端元数据
Map<String, Object> getMetadata() / String getClientId() / String getServerUrl()
```

### 2.5 KcpHttpClient（HTTP 风格简化客户端）

```java
KcpHttpClient(String host, int port)                 // 超时缺省 5000ms
KcpHttpClient(String host, int port, long timeoutMs)
void connect() throws Exception
HttpResponse request(String method, String path, Map<String, String> headers, byte[] body)
HttpResponse get(String path) / HttpResponse post(String path, byte[] body)
boolean isConnected() / void close()

// HttpResponse：int getStatus() / Map<String,String> getHeaders() / String getHeader(name)
//               byte[] getBody() / String getBodyString()
```

请求经主题 `http` 同步发送，载荷为 JSON（`method` / `path` / `headers` / `body`）；服务端需自行订阅 `http` 主题并按同样 JSON 结构（`status` / `headers` / `body`）回包——`KcpHttpServer` 未内置该处理逻辑（源码未提供）。

---

## 3. 快速开始

### 3.1 启动 KCP 消息服务器

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;

Server server = ServerBuilder.create()
        .type("kcp")          // SPI 名称，命中 KcpServer
        .host("127.0.0.1")
        .port(19380)
        .build();
server.start();
```

### 3.2 客户端订阅、发布与请求-响应

```java
import com.chua.kcp.support.client.KcpClient;

KcpClient client = new KcpClient("client-1", "kcp://127.0.0.1:19380");
client.subscribe("order/#", (topic, payload) -> System.out.println(topic + " -> " + payload));
client.connect();                              // 完成 register 握手（最长等待 3s）
client.send("greet", "hello");                 // 发布到主题 greet
String ack = client.execute("echo", "ping");   // 同步请求-响应（默认 5s 超时）
client.close();
```

### 3.3 服务端广播与定向推送

```java
import com.chua.kcp.support.server.KcpServer;

KcpServer kcp = (KcpServer) server;            // ServerBuilder 返回的即 KcpServer 实例
kcp.onSubscribe("order/#", (topic, payload) -> System.out.println("订阅到 " + topic));
kcp.publish("notice", "维护通知");              // 广播给所有客户端
kcp.send("client-1", "notice", "定向消息");     // 只发给指定客户端
```

### 3.4 Sync 双向同步通道

```java
import com.chua.common.support.network.server.ServerSetting;
import com.chua.kcp.support.sync.KcpSyncClient;
import com.chua.kcp.support.sync.KcpSyncServer;

ServerSetting setting = ServerSetting.defaults();
setting.setHost("127.0.0.1");
setting.setPort(19380);

KcpSyncServer syncServer = new KcpSyncServer(setting);
syncServer.start();

KcpSyncClient syncClient = new KcpSyncClient("sync-1", "kcp://127.0.0.1:19380");
syncClient.subscribe("cmd/#", (topic, payload) -> { /* SyncMessageHandler */ }); // 连接前订阅会被缓存
syncClient.connect();
syncClient.send("cmd/scan", "start");
syncClient.close();
syncServer.stop();
```

---

## 4. 线路协议与内置参数

### 4.1 线路协议（文本协议，两端按行切分）

| 约定 | 格式 | 说明 |
| --- | --- | --- |
| 消息帧 | `topic:payload` | 文本格式，服务端写出统一以 `\n` 结尾；客户端按 `\n` 切行，跨包缓冲保留未完成行 |
| 注册请求 | `register:clientId` | 客户端连接建立后发送（延迟 50ms），服务端据此把会话重命名为 clientId |
| 注册确认 | `registered:clientId` | 服务端在 `onConnected` 即回发一次（初始 id 形如 `client-<nanoTime>-<hashCode>`），重命名后再次回发 |
| 请求-响应 | 请求主题 `req/<topic>`，载荷 `<requestId>|<message>` | `requestId` 为 UUID；响应经主题 `resp/<topic>` 回传，载荷 `<requestId>|<body>` |
| 主题通配 | `#` 多级通配；`+` / `*` 单级通配 | `order/#` 匹配 `order/` 下任意层级主题 |

### 4.2 内置传输参数（源码常量，未对外暴露配置）

| 参数 | 值 | 位置 |
| --- | --- | --- |
| KCP MTU | 1400 | `KcpServer.KCP_MTU` / `KcpClient.connect()` |
| KCP interval | 1ms（配合 `nodelay(true, 1, 2, true)`：快速模式、关闭拥塞控制、快速重传阈值 2） | `KcpServer.KCP_INTERVAL` / `KCP_FAST_RESEND` |
| 会话空闲超时 | 60000ms | `ChannelConfig.setTimeoutMillis` |
| 批量 flusher | max(2, min(CPU 核数, 8)) 个虚拟线程，按连接哈希分片合并写出 | `KcpServer.doStart()` |
| 注册确认超时 | 3000ms | `KcpClient.REGISTER_TIMEOUT_MS` |
| 请求-响应默认超时 | 5000ms | `KcpClient.DEFAULT_TIMEOUT_MS` / `KcpHttpClient` |
| 默认服务端端口 | 19380 | `KcpClient.DEFAULT_PORT` / `KcpSyncServer.DEFAULT_URL` |
| KCP 会话标识 conv | `KcpServer.KCP_CONV = 0x4b4350`、`KcpHttpServer.KCP_CONV = 0x48455054` | 两端保持一致的声明常量 |

---

## 5. 配置项

- **无独立配置文件、无配置键、无环境变量读取**（源码未提供）：6 个源文件均未读取 properties / 系统属性。
- 服务端地址、端口、boss 线程数经 `ServerSetting` 传入（`ServerBuilder.host(...)` / `port(...)`；`KcpServer` 读取 `getHost()` / `getPort()` / `getBossThreads()`，并把 `protocol` 置为 `kcp`）。
- 客户端服务端地址为构造参数 `kcp://host:port`，端口缺省 `19380`。
- KCP 传输参数（MTU、interval、fastResend、nodelay、会话超时）为源码内置常量，见 [4.2](#42-内置传输参数源码常量未对外暴露配置)，未提供配置入口。

---

## 6. SPI 扩展点

### 6.1 注册明细（`src/main/resources/META-INF/`）

| SPI 接口 | 名称 | 实现 | 登记文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.server.Server` | `kcp` | `com.chua.kcp.support.server.KcpServer` | `extensions/…server.Server`、`services/…server.Server` |
| `com.chua.common.support.network.server.SyncServer` | `kcp` | `com.chua.kcp.support.sync.KcpSyncServer` | `extensions/…server.SyncServer` |
| `com.chua.common.support.network.sync.SyncClient` | `kcp` | `com.chua.kcp.support.sync.KcpSyncClient` | `extensions/…sync.SyncClient` |

- `@Spi("kcp")` 注解标注于 `KcpServer`、`KcpSyncServer`、`KcpSyncClient`。
- 消息处理注解（经 `registerBean` 注册 Bean 后由服务器/客户端反射分发）：`@OnOpen`、`@OnMessage(value = "topic")`、`@OnClose`、`@OnError`（来自 `com.chua.common.support.objects.annotation`）。
- 服务端消息链路接入 common 层的 `ServerFilter` 过滤器体系：每条业务消息构造协议无关的 `ServerRequest` / `ServerResponse` 走 `DefaultServerFilterChain`，链尾再做主题订阅分发、注解分发与监听器通知。
- 内置 GraalVM `native-image` 配置：`META-INF/native-image/com.chua/utils-support-kcp-starter/` 下的 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。

### 6.2 底层回调

`kcp.KcpListener`（`onConnected` / `handleReceive` / `handleException` / `handleClose`）由内部类 `OAuthKcpListener` 实现并桥接到上述分发逻辑；注意 kcp-base 1.6.2 的读取任务自行管理 `ByteBuf` 引用计数，回调内不可重复 `release`。

---

## 7. 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | 网络契约：`Server` / `ServerBuilder` / `ServerSetting` / `SyncServer` / `SyncClient` 等 |
| `com.github.l42111996:kcp-base` | 1.6.2（`kcp.base.version`） | Java KCP over Netty 核心；排除 `kcp-fec` 与 `netty-all` |
| `com.github.l42111996:kcp-fec` | 1.6.2 | FEC 前向纠错库；排除其传递的 `netty-all` |
| `io.netty:netty-buffer` / `netty-common` / `netty-transport` / `netty-transport-classes-epoll` / `netty-codec` | 项目统一 `${netty.all.version}`（4.2.18.Final） | kcp-base 内部使用 Netty buffer/Channel/EventLoop，显式引入项目统一 Netty 4.2 |
| `io.netty:netty-transport-classes-kqueue` | 4.2.18.Final | 补齐 kcp-base 1.6.2（`AbstractKcpServer`）静态引用的 KQueue 类 |
| `org.jctools:jctools-core` | 3.3.0 | 无锁队列 |
| `com.lmax:disruptor` | optional | 可选依赖 |

> Netty 版本说明（来自 pom 注释）：kcp-base 1.6.2 内部使用 `NioEventLoopGroup`，kcp-fec 依赖 netty-all；与项目统一 Netty 4.2 可能存在 API 差异，故显式排除 netty-all，由项目统一注入 4.2 模块。

---

## 8. 边界：这些能力不在本模块

- **不提供 TLS / 自定义流加密**：`KcpServer` 启动时调用 `rejectStreamEncryption` 明确拒绝——KCP 基于 UDP，既无 TLS 会话，通用 KCP 客户端也不支持自定义流加密。
- **不是完整 HTTP 实现**：`KcpHttpServer` 不做 HTTP 解析（仅启动内嵌 `KcpServer` 作为协议入口占位）；`KcpHttpClient` 只是把 method/path/headers/body 打包为 JSON 经主题 `http` 往返，均不处理完整 HTTP 语义。
- **无自动重连、心跳保活编排**（源码未提供）：会话空闲 60s 超时由 kcp-base `ChannelConfig` 设定，断线后的重连策略需调用方自行处理。
- **无鉴权 / 身份校验**：注册握手仅用 `register:clientId` 重命名会话，不校验身份。
- **FEC 未提供开启入口**：`kcp-fec` 虽在依赖中，但源码未提供任何 FEC 配置项（源码未提供）。
- **无持久化、无集群**：会话表、订阅表、元数据均保存在进程内 `ConcurrentHashMap`，进程退出即失。
- **不含 Spring Boot 自动装配**：本模块属 utils 最底层工具库，无任何 `spring-*` 依赖，也不实现任何上层业务 starter 的业务。
