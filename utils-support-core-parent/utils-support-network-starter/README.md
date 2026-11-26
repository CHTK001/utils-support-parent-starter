# utils-support-network-starter

> utils 体系的**网络通信实现层**：基于 Vert.x 的 HTTP/WebSocket/TCP 服务端与反向代理、无中心化集群门面 (`ClusterServer`)、HTTP/WebSocket 同步服务与数据同步 Agent 的 WebSocket 传输。协议契约与公共门面（`Server`、`ServerRequest`、`ServerResponse`、`ServerSetting`、`SyncServer`、`SyncClient`、`HttpClientExecutor` 等）位于同仓 `utils-support-common-starter`，本模块只做实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，取自父 POM）｜包名：`com.chua.common.support.network.*`（实现类另含 `com.chua.vertx.support.*`）

---

## 目录

- [1. 模块定位：它是什么 / 不是什么](#1-模块定位它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 依赖](#31-依赖)
  - [3.2 起一个 Vert.x HTTP 服务](#32-起一个-vertx-http-服务)
  - [3.3 起一个 WebSocket 服务（topic 订阅/发布）](#33-起一个-websocket-服务topic-订阅发布)
  - [3.4 TCP 短连接调用（VertxTcpClient）](#34-tcp-短连接调用vertxtcpclient)
  - [3.5 一行启动无中心化集群（ClusterServer）](#35-一行启动无中心化集群clusterserver)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位：它是什么 / 不是什么

pom 描述原文：《网络通信模块：Vert.x HTTP/WebSocket、Socket、ServerFilter 集合》。

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 网络能力的**实现层**：Vert.x HTTP/WebSocket/TCP 服务端、HTTP/TCP 反向代理、无中心化集群门面、HTTP/WebSocket 同步服务、WebSocket 数据同步 Agent | 不定义网络抽象契约；`Server`/`ServerSetting`/`SyncClient` 等接口与统一入口在 `utils-support-common-starter` |
| 协议栈 | Vert.x 5（`vertx-core` / `vertx-web`）事件循环实现，`setPreferNativeTransport(true)` 优先原生传输 | **不依赖 Netty**：`network.sync.netty` 包内类名沿袭旧称，实际为纯 JDK 实现（`com.sun.net.httpserver.HttpServer`、`ServerSocket`、JDK `HttpClient`），源码无任何 `io.netty` import |
| 服务端实现 | 5 种 Vert.x 服务端：`vertx-http` / `vertx-http-proxy` / `vertx-tcp` / `vertx-tcp-proxy` / `vertx-websocket` | JDK / NIO / AIO 服务端（`JdkHttpServer`、`JdkTcpServer` 等）在 `utils-support-common-starter` |
| 集群 | `ClusterServer` 一行启动无中心化集群节点：HTTP/TCP 双入口代理 + 服务注册/路由/负载均衡/故障退避 | Scatter 对等网格本身（`ScatterServiceDiscovery`、`TcpScatterBuilder`、`ScatterNodeServer`）在 `utils-support-common-starter` |
| 同步 | `netty-http` / `netty-websocket` 两个 `SyncServer` 实现及其 `SyncClient` / `SyncFlow` | `SyncClient` / `SyncServer` 的 tcp/udp/http/websocket 常规实现已移除，由 `utils-support-common-starter` 提供（见 `META-INF/extensions` 内注释） |
| HTTP 客户端 | — | 本模块**不提供** HTTP 客户端执行器；`jdk`（common）与 `okhttp` / `httpclient5`（对应 starter）在别处注册 |
| 数据同步 | WebSocket 通道的 Agent / AgentServer / Source 适配（`WebSocketDataSyncAgent` 等） | `DataSyncAgent` / `DataSyncSource` / `DefaultDataSyncAgentServer` 等框架本体在 `utils-support-datasync-starter` / `utils-support-datasync-agent-starter`（均为 `provided` 依赖） |
| 依赖 | 仅依赖 `utils-support-common-starter` + `vertx-core` / `vertx-web` + `reactor-core`（compile：main 源码 `WebSocketDataSyncAgent` / `WebSocketAgentDataSyncSource` 直接用 `Flux`）+ provided 的 datasync 两模块 | 不依赖 Spring、不依赖 Netty |

规模：Java 源文件 **27** 个（`src/main/java`，截至 4.0.0.51）。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起响应式 HTTP 服务 | `ServerBuilder.create().type("vertx-http")` → `VertxHttpServer` | SPI 名 `vertx-http` / `http`；同步阻塞 + 响应式（`supportsReactor()=true`）双模式；虚拟线程执行过滤器链；支持 SSE（`sse()`/`sseEvent`/`sseClose`）、h2c 明文多路复用、gzip、CORS、SSL（JKS/PEM/自签名）、WebSocket 升级分发（`onSubscribe(topic, handler)`） |
| 起 WebSocket 服务 | `VertxWebSocketServer`（SPI `vertx-websocket`） | 按「topic\nbody」约定路由；`onSubscribe(topic, handler)` 订阅、`publish(topic, payload)` 广播；`@OnOpen` / `@OnMessage`（`value()` 即 topic）/ `@OnClose` 注解驱动；未接 TLS |
| TCP 服务 | `VertxTcpServer`（SPI `vertx-tcp`） | 与 `JdkTcpServer` 能力对齐：默认回显、`registerHandler(String, JdkTcpServer.TcpHandler)`、`setHandler(TcpServerHandler)`；keep-alive；`ServerSetting#isEncrypt()` + `encryptKey` 时经 `StreamCodec` 传输加密，帧格式不变 |
| TCP 短连接调用 | `VertxTcpClient`（`com.chua.vertx.support.client`） | `call(host, port, byte[])` 一请求一响应一断；帧协议「4 字节大端长度头 + 主体」（上限 64MB）；`encrypt(type, key)` 开启加密（`aes-gcm` / `keylog`，与 `JdkTcpClient` 同口径） |
| HTTP / TCP 反向代理 | `VertxHttpProxyServer`（`vertx-http-proxy`）、`VertxTcpProxyServer`（`vertx-tcp-proxy`） | 全链路事件循环异步，双向 `pipeTo` 转发（TCP）；后端地址由 common 包 `ProxyTargetResolver<InetSocketAddress>` 解析（构造可注入静态 backend 或解析器实例，无 SPI 加载） |
| 过滤器式代理 | `HttpReverseProxyServerFilter` / `TcpProxyServerFilter` / `UdpProxyServerFilter` / `VertxWebSocketProxyServerFilter` / `ReverseProxyServerFilter` | 以 `ServerFilter` 挂到任意服务端；HTTP（Vert.x HttpClient / JDK HttpClient 两种）、TCP、UDP、WebSocket 双向帧转发；静态路由 `staticRoutes(Map)` 或动态解析器；`ReverseProxyServer` 提供可读门面（`route`/`scatterId`/`balance`/`excludeServerId`） |
| 无中心化集群 | `ClusterServer`（`com.chua.common.support.network.cluster`） | 一行启动节点：seeds 引导加入 Scatter 对等网格，自动注册自身与显式远端服务，HTTP/TCP 双入口代理，按 `scatterId` 分组路由、负载均衡（weight/round/random）、故障退避 |
| HTTP / WebSocket 同步 | `NettyHttpSyncServer`（SPI `netty-http`）、`NettyWebSocketSyncServer`（SPI `netty-websocket`） | 实现 `SyncServer` 契约（注册端点 `/api/sync/send`、`/api/sync/pull`）；同名 `NettyHttpSyncClient` / `NettyWebSocketSyncClient` / `Netty*SyncFlow` 配套；注意：均为纯 JDK 实现，无 Netty 依赖 |
| WebSocket 数据同步 | `WebSocketDataSyncAgent` / `WebSocketDataSyncAgentServer` / `WebSocketAgentDataSyncSource` | 与 DataSyncServer 长连接，register/pull/push 消息协议；需要 `utils-support-datasync-agent-starter`、`utils-support-datasync-starter`（provided） |

---

## 3. 快速开始

### 3.1 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-network-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 起一个 Vert.x HTTP 服务

```java
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;

Server server = ServerBuilder.create()
        .type("vertx-http")          // 本模块实现的 SPI 名称：vertx-http / http
        .host("0.0.0.0")
        .port(8080)
        .mapping("/hello", HttpMethod.GET, (req, resp) -> resp.setResult("Hello, Vert.x"))
        .build();

server.start();
System.out.println(server.getServerUrl());
// ... 业务代码
server.stop();
```

> `ServerBuilder` / `ServerSetting` / `ServerRequest` / `ServerResponse` 等 API 由 `utils-support-common-starter` 提供；`Server` 的 SPI 注册表（`META-INF/services/com.chua.common.support.network.server.Server`）中登记了本模块 4 个 Vert.x 实现：`VertxHttpServer`、`VertxHttpProxyServer`、`VertxTcpServer`、`VertxTcpProxyServer`。
>
> Vert.x 服务端支持 SSE 与 WebSocket 升级：HTTP 请求带 `Upgrade: websocket` 头时自动切换到 Vert.x 原生 WebSocket，按「topic\nbody」路由到 `onSubscribe(topic, handler)` 注册的处理器。

### 3.3 起一个 WebSocket 服务（topic 订阅/发布）

```java
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.vertx.support.server.websocket.VertxWebSocketServer;

VertxWebSocketServer wsServer = new VertxWebSocketServer(ServerSetting.defaults());
wsServer.onSubscribe("chat", (ServerHandler) (req, resp) ->
        resp.setResult("echo: " + req.getBodyString()));
wsServer.start();

// 服务器主动向该 topic 广播（先注册后广播）
wsServer.publish("chat", "hello everyone");
wsServer.stop();
```

> 也支持注解驱动：向 `registerBean(Object)` 注册 Bean，方法标 `@OnMessage`（`value()` 为 topic）、`@OnOpen`、`@OnClose` 即自动接线。**注意：`VertxWebSocketServer` 未接 TLS**（`doStart()` 会拒绝流式加密配置，需加密请用反向代理终结 TLS）。

### 3.4 TCP 短连接调用（VertxTcpClient）

```java
import com.chua.vertx.support.client.VertxTcpClient;

try (VertxTcpClient client = new VertxTcpClient()) {
    byte[] resp = client.call("127.0.0.1", 9000, "ping".getBytes());
    System.out.println(new String(resp));
    // 需要传输加密时（与服务端 ServerSetting#setEncryptType/setEncryptKey 对应）：
    // client.encrypt("aes-gcm", "共享口令");
}
```

> 服务端一侧用 `VertxTcpServer`：默认回显；业务处理用 `registerHandler(name, TcpHandler)` 或 `setHandler(TcpServerHandler)`（短连接一请求一响应帧）。

### 3.5 一行启动无中心化集群（ClusterServer）

```java
import com.chua.common.support.network.cluster.ClusterServer;

try (ClusterServer server = ClusterServer.builder()
        .scatterId("order")                       // 业务分组：仅与同 scatterId 节点互通
        .seeds("192.168.1.10:19001")              // 种子节点（主机:端口），引导加入对等网格
        .addServer("/api", "192.168.1.20", 8080, "http")   // 声明远端服务，scatter 自动扩散
        .build()) {
    server.start();                               // 启动 scatter 节点 + HTTP/TCP 代理入口并注册服务
    // server.manager().route("/api", "order", "http");  // 集群管理器：视图/路由/健康检查
    // server.discovery();                              // Scatter 服务发现实例
}
```

> `ClusterServer` 内部由 `ClusterNode`（Scatter 加入 + HTTP/TCP 双入口代理）、`ClusterManager`（集群视图/路由/故障退避）、`ServerEntry`（服务元数据壳类，`validate()` 仅校验字段非空与协议白名单 http/tcp/udp）组合而成。**协议限制如实说明**：`ServerEntry` 类 javadoc 声称「同一路径混用协议抛 `IllegalArgumentException`」，但 `validate()` 未实现该检查；`ClusterServer.Builder#addServer` 注释则写明「协议不限制，混用同路径允许」——代码实际允许同路径混用协议。HTTP 入口为 `jdk-http` 服务端 + `ServiceDiscoveryServerFilter` + `ReverseProxyServerFilter`；TCP 入口为 `TcpProxyServer` + `DiscoveryProxyTargetResolver`（common 包 `ProxyTargetResolver<InetSocketAddress>` 实现）。端口规划：业务端口 `port`（0=自动分配），TCP 代理 `port+1`，scatter 通信 `port+2`（或显式 `scatterPort`）。

---

## 4. 配置项

**本模块零配置文件、零自动装配**：无 `application*.yml/properties` 读取逻辑，无 Spring 集成，引入依赖即可用（与源码结构及旧 README「零配置模块」表述一致）。

与模块直接相关的**系统属性**（源码与 SPI 注册文件内注释原文）：

| 系统属性 | 值 | 作用 |
| --- | --- | --- |
| `-Dserver.accesslog.enabled=true` | 布尔 | 开启访问日志过滤器（`AccessLogServerFilter`，默认不注册——有请求级开销） |
| `-Dserver.tracing.enabled=true` | 布尔 | 开启链路追踪过滤器（`TracingServerFilter`，默认不注册） |
| `-Dbench.fast=true` | 布尔 | `VertxHttpServer` 基准测试模式：跳过虚拟线程，直接在事件循环执行过滤器链 |

除上述外，网络参数统一走 `utils-support-common-starter` 的 `com.chua.common.support.network.server.ServerSetting`（构造/构建器传入，非配置文件）。源码中实际使用到的取值（在 `VertxHttpServer` / `VertxTcpServer` / `VertxWebSocketServer` 等处逐字出现）：`getHost()`、`getPort()`、`getMaxRequestSize()`、`getMaxHeaderSize` 相关的 `maxRequestSize`、`getBacklog()`、`getBufferSize()`、`isTcpNoDelay()`、`isSoReuseAddr()`、`getReadTimeout()`、`isGzipEnabled()` / `getGzipLevel()` / `getGzipMinSize()`、`getMaxFrameSize()`、`getMaxConnections()`、`getWorkerThreads()`、`getCharset()`、`isReactor()`、`getContextPath()`、`getCors()`（`CorsConfig`：`isAllowOrigin()` / `getAllowedOrigins()` / `getAllowedMethods()` / `getAllowedHeaders()`）、`getSsl()`（`SslConfig`：`isEnabled()` / `isSelfSignedAuto()` / `getKeyStorePath()` / `getKeyStorePassword()` / `getCertPath()` / `getKeyPath()` / `isSelfSigned()`）、以及 TCP 加密的 `isEncrypt()`（配合 `encryptKey` / `encryptType`）。`ServerSetting` 的完整字段清单以 common-starter 源码为准（**源码未提供**完整枚举）。

集群配置见 `com.chua.common.support.network.cluster.ClusterSetting`（可由 `ClusterServer.builder()` 设置，字段与构建器一一对应，源码一目了然）：`nodeId`、`host`（默认 `127.0.0.1`）、`scatterId`（默认 `default`）、`seeds`、`servicePaths`、`serverEntries`、`port`（默认 `0`=自动分配）、`scatterPort`（默认 `0`=端口+2）、`httpEnabled`（默认 `true`）、`tcpEnabled`（默认 `true`）、`balance`（默认 `weight`，可选 `weight` / `round` / `random`）、`timeoutMillis`（默认 `3000`）、`autoDiscoveryIntervalMillis`（默认 `1000`）、`master`（仅元数据记录，不影响发现逻辑）。

---

## 5. SPI 扩展点

### 5.1 本模块登记的实现（META-INF）

**`META-INF/services/com.chua.common.support.network.server.Server`**（JDK ServiceLoader）：

| 实现 | 类名 |
| --- | --- |
| `VertxHttpServer` | `com.chua.vertx.support.server.VertxHttpServer`（注解 `@Spi({"vertx-http", "http"})`） |
| `VertxHttpProxyServer` | `com.chua.vertx.support.server.VertxHttpProxyServer`（`@Spi("vertx-http-proxy")`） |
| `VertxTcpServer` | `com.chua.vertx.support.server.VertxTcpServer`（`@Spi("vertx-tcp")`） |
| `VertxTcpProxyServer` | `com.chua.vertx.support.server.VertxTcpProxyServer`（`@Spi("vertx-tcp-proxy")`） |

另有两个 `@Spi` 注解的同步实现：`NettyHttpSyncServer`（`netty-http`）、`NettyWebSocketSyncServer`（`netty-websocket`），以及 `VertxWebSocketServer`（`@Spi("vertx-websocket")`，在 `server.websocket` 子包）。

**`META-INF/extensions/com.chua.common.support.network.server.filter.ServerFilter`**（扩展接口注册，11 个 key）：

| key | 实现（均为 `com.chua.common.support.network.server.filter.*`） |
| --- | --- |
| `ip` | `IpServerFilter` |
| `firewall` | `FirewallServerFilter` |
| `rate-limit` | `RateLimitServerFilter` |
| `routing` | `UrlMappingServerFilter` |
| `request-id` | `RequestIdServerFilter` |
| `request-timeout` | `RequestTimeoutServerFilter` |
| `ipc` | `IpcServerFilter` |
| `response-rewrite` | `ResponseRewriteServerFilter` |
| `gzip` | `GzipServerFilter` |
| `cors` | `CorsServerFilter` |
| `ws-proxy` | `com.chua.common.support.network.server.filter.proxy.VertxWebSocketProxyServerFilter` |

> 注册文件注释原文提醒：访问日志 / 链路追踪不进 SPI 自动发现（请求级开销），需要时用 `-Dserver.accesslog.enabled=true` / `-Dserver.tracing.enabled=true`，或 `server.addFilter(new AccessLogServerFilter())` / `new TracingServerFilter()` 手动注册。

**已停用的扩展文件**（现仅含注释，表示能力迁移去向，保留逐字原文供参考）：

- `META-INF/extensions/com.chua.common.support.network.sync.SyncClient`：`# Netty 已移除：SyncClient 的 tcp/udp/http/websocket 实现由 utils-support-common-starter 提供`
- `META-INF/extensions/com.chua.common.support.network.server.SyncServer`：`# Netty 已移除：SyncServer 的 tcp/udp/http/websocket 实现由 utils-support-common-starter 提供`
- `META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor`：`# Netty 已移除：HTTP 客户端执行器由 utils-support-common-starter（jdk）及其他模块（okhttp/httpclient5）提供`

### 5.2 本模块定义的扩展接口

| 接口 | 说明 |
| --- | --- |
| `com.chua.common.support.network.server.filter.proxy.ProxyTargetResolver` | `@FunctionalInterface`，`Discovery resolve(InetSocketAddress remoteAddr)`；仅 `TcpProxyServerFilter` / `UdpProxyServerFilter` 使用（`staticRoutes(Map<String, InetSocketAddress>)` / `of(…, resolver)` 注入），可与服务发现实现组合 |
| `com.chua.common.support.network.server.proxy.ProxyTargetResolver<T>`（common 包） | `@FunctionalInterface`，`InetSocketAddress resolve(T remote)`；`VertxHttpProxyServer` / `VertxTcpProxyServer`（构造器 `(setting, resolver)` / `(setting, backend)`，无 SPI 加载）与 common 的 `DiscoveryProxyTargetResolver` 使用；HTTP/WebSocket 过滤器（`HttpReverseProxyServerFilter` / `ReverseProxyServerFilter` / `VertxWebSocketProxyServerFilter`）不用解析器接口，后端改由 `ServerAttribute.getBackendDiscovery(request)` 提供 |

另提供 GraalVM native-image 配置（`META-INF/native-image/com.chua/utils-support-network-starter/`）：`reflect-config.json`（如 `AccessLogServerFilter`、`CorsFilter`、`GzipFilter`、`IpcServerFilter`、`IpFilter`、`VertxWebSocketProxyFilter`、`RateLimitFilter` 等）、`proxy-config.json`（`com.chua.common.support.network.server.Server`）、`resource-config.json`。

---

## 6. 构建与依赖

模块坐标（`pom.xml`）：

```xml
<parent>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-core-parent</artifactId>
    <version>4.0.0.51</version>
</parent>
```

依赖关系：

```
utils-support-network-starter  (com.chua:4.0.0.51)
├── com.chua:utils-support-common-starter        (compile, ${project.version})
├── io.vertx:vertx-core                           (compile, ${vertx.version})
├── io.vertx:vertx-web                            (compile, ${vertx.version})
├── io.projectreactor:reactor-core                (compile, ${reactor.core.version};main 源码用 Flux)
├── com.chua:utils-support-datasync-agent-starter (provided, ${project.version})
└── com.chua:utils-support-datasync-starter       (provided, ${project.version})
```

> `${vertx.version}`、`${reactor.core.version}` 由仓库根 POM 定义；`vertx.version` 当前为 `5.2.0`。构建命令与同仓其它模块一致（`mvn clean install`，仓库默认 `skipTests=true`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 网络抽象契约与统一门面：`Server` / `ServerBuilder` / `ServerSetting` / `ServerRequest` / `ServerResponse` / `ServerFilter` 接口、`SyncServer` / `SyncClient` / `SyncFlow`、`HttpClientExecutor`、`TcpClient`/`TcpServer`/`TcpServerHandler`、`ServiceDiscovery`、`Discovery`、`ProtocolType` 等 | `utils-support-common-starter` |
| JDK/NIO/AIO 服务端（`JdkHttpServer`、`NioHttpServer`、`AioHttpServer`、`JdkTcpServer`、`AioTcpServer` 等）与 FTP/SIP/Telnet/代理等 common 内置实现 | `utils-support-common-starter` |
| `SyncClient` / `SyncServer` 的 tcp/udp/http/websocket 常规实现 | `utils-support-common-starter`（本模块扩展文件注释：“Netty 已移除”） |
| HTTP 客户端执行器（`jdk` / `okhttp` / `httpclient5`）及 `HttpClientFactory` | `utils-support-common-starter`、`utils-support-okhttp-starter`、`utils-support-httpclient-starter` |
| 下载 `Downloader`、SSE、DNS、IP 归属、RPC、隧道、scatter 对等网格本体、`ServiceDiscovery` 实现等 | `utils-support-common-starter` |
| 数据同步框架本体：`DataSyncAgent` / `DataSyncSource` / `SyncDataOffset` / `Direction` / `DefaultDataSyncAgentServer` 等 | `utils-support-datasync-agent-starter`、`utils-support-datasync-starter`（本模块以 `provided` 引用） |
| DHT / BT 类能力（旧版 README 所列 `MetadataDownloader`、`PeerWireClient`、`DhtConfig`、`DhtMessage`、`DhtMessageType`、`DhtPeer`、`DhtPolledDirectory`、`DhtProtocol`、`DhtRoutingTable`、`DhtCrawlListener` 等） | **源码未提供**：当前 `src/main/java` 中已不存在这些类，勿按旧文档引用 |
| Netty 依赖本身 | 本模块不依赖 Netty；`network.sync.netty` 包为纯 JDK 实现（类名沿袭旧称） |