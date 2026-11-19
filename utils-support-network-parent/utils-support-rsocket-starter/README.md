# utils-support-rsocket-starter

> utils 体系的 **RSocket 响应式通信集成模块**：基于 RSocket Java（Netty TCP 传输）提供嵌入式 RSocket 服务端、分发器客户端、同步（Sync）客户端/服务端/流程门面，以及数据同步（DataSync）Agent 侧与服务端侧的 RSocket 实现；通过 `@Spi("rsocket")` 注册进 utils 的 `Server` / `SyncServer` / `SyncClient` / `DispatcherProvider` SPI 体系。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.rsocket.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 通信约定（载荷格式）](#4-通信约定载荷格式)
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
    <artifactId>utils-support-rsocket-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 自述：`RSocket 响应式通信集成，基于 RSocket Java，含 DispatcherProvider 及 Subscriber SPI 实现`。全部类标注 `@author CH`、`@since 4.0.0.42`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | RSocket Java（`io.rsocket` 1.1.5）之上的轻量集成层，把 RSocket 接入 utils 的 `Server` / `SyncServer` / `SyncClient` / `DispatcherProvider` SPI | 不是 RSocket 协议本身的实现（协议实现来自 `io.rsocket:rsocket-core`） |
| 能力 | 嵌入式 TCP RSocket 服务端、按主题（topic）的请求响应 / fire-and-forget / 请求流、注解式消息处理、分发器客户端、DataSync Agent 接入 | 不是 Spring Boot 自动装配 starter（无 `spring.factories` / 自动配置导入文件，无 Spring 依赖） |
| 传输 | 仅 TCP（`TcpServerTransport` / `TcpClientTransport`） | 不含 TLS 终结、不提供 WebSocket / HTTP 传输 |
| 业务 | 只做通信通道与 SPI 适配 | 不实现数据同步业务规则（业务契约在 `utils-support-datasync-*`，本模块仅以 provided 依赖实现其接口） |

### 1.3 规模与包地图

共 **8 个 Java 源文件**，无测试源码（`src/test` 不存在；根 POM 亦配置 `skipTests=true`）。

| 包 | 类 | 职责 |
| --- | --- | --- |
| `com.chua.rsocket.support.server` | `RSocketServer`、`RSocketDataSyncAgentServer` | 嵌入式 RSocket 服务端；DataSync Agent 服务端 |
| `com.chua.rsocket.support.dispatcher` | `RSocketDispatcherProvider` | 分发器客户端（接入远端 RSocket 服务） |
| `com.chua.rsocket.support.sync` | `RSocketSyncServer`、`RSocketSyncClient`、`RSocketSyncFlow` | 同步通道的服务端 / 客户端 / 一体化流程 |
| `com.chua.rsocket.support.agent` | `RSocketDataSyncAgent` | 数据同步 Agent 的 RSocket 接入实现 |
| `com.chua.rsocket.support.source` | `RSocketAgentDataSyncSource` | Agent 数据源（Server 侧经 RSocket 读 / 写） |

---

## 2. 核心 API 与速查表

### 2.1 类清单

| 类 / 接口 | SPI 名称 | 说明 |
| --- | --- | --- |
| `RSocketServer` | `rsocket`（`Server`） | RSocket 嵌入式服务器，轻量级实现。继承 `AbstractServer`，支持 `@OnOpen` / `@OnClose` / `@OnMessage` 注解处理与过滤器链；基于 RSocket Java 实现，支持 请求响应、fire 和 forget、请求流 模型 |
| `RSocketDispatcherProvider` | `rsocket`（`DispatcherProvider`） | RSocket 分发器提供者，基于 RSocket Java 客户端连接远程 RSocket 服务；本地不启动任何服务器，通过 请求流 订阅 topic 并逐条分发给本地订阅定义 |
| `RSocketSyncServer` | `rsocket`（`SyncServer`） | rSocket 同步服务端实现，实现 `SyncServer` + `SyncProtocol`，底层委托给 `RSocketServer` |
| `RSocketSyncClient` | `rsocket`（`SyncClient`） | rSocket 协议下的同步客户端实现，基于 fire 和 forget + 请求流 模型，支持断开后重连（默认无限次）与按 topic 的流订阅 |
| `RSocketSyncFlow` | — | rSocket 同步流程管理器，组合 `RSocketSyncServer` 与 `RSocketSyncClient`，实现 `SyncFlow` |
| `RSocketDataSyncAgent` | — | RSocket 数据同步 Agent，通过 RSocket 与 DataSyncServer 建立双向流式连接，实现 `com.chua.datasync.agent.support.DataSyncAgent` |
| `RSocketDataSyncAgentServer` | — | RSocket 数据同步 Agent 服务端，运行在 DataSyncServer 侧，通过 RSocket 管理 Agent 连接，支持 request-stream 拉取数据；继承 `com.chua.starter.datasync.agent.DefaultDataSyncAgentServer` |
| `RSocketAgentDataSyncSource` | — | RSocket Agent 数据源，Server 侧通过 RSocket request-stream / fire-and-forget 与 Agent 交互，实现 `com.chua.datasync.agent.support.DataSyncSource` |

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起一个 RSocket 服务端 | `new RSocketServer(ServerSetting)` | `start()` / `stop()`；地址端口来自 `ServerSetting` |
| 按主题收消息（服务端） | `server.onSubscribe(topic, Consumer<String>)` | 注册主题处理器 |
| 按主题请求-响应（服务端） | `server.onRequest(topic, ServerHandler)` | 处理器写入 `ServerResponse` 的内容作为 请求响应 的响应返回 |
| 注解式消息处理 | `server.registerBean(handler)` + `@OnOpen` / `@OnMessage("topic")` / `@OnClose` | `@OnMessage` 无值时主题为 `default` |
| 向订阅者推送 | `server.publish(topic, payload)` | 推送给所有订阅该主题的 请求流 客户端 |
| 作为分发器接入远端 | `new RSocketDispatcherProvider(config)` | SPI 名称 `rsocket`；`start()` / `publish` / `subscribe` / `unsubscribe` / `close` |
| 同步客户端 | `new RSocketSyncClient(serverUrl)` | `connect` / `send` / `subscribe` / `unsubscribe` / `disconnect`；断线自动重连 |
| 同步服务端 / 一体化流程 | `new RSocketSyncServer(setting)` / `new RSocketSyncFlow(...)` | `SyncServer` / `SyncFlow` 契约的 rsocket 实现 |
| 数据同步 Agent 接入 | `new RSocketDataSyncAgent(agentId, sourceId, host, port, source)` | `start()` / `stop()`；`dataUrl()` 返回 `tcp://host:port` |
| DataSync 服务端 / 数据源 | `new RSocketDataSyncAgentServer(port)` / `new RSocketAgentDataSyncSource(agentId, sourceId, host, port)` | 服务端监听 `0.0.0.0:port`；数据源方向为 `Direction.INPUT` |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rsocket-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 服务端：起一个 RSocket Server 并按主题收发

```java
import com.chua.common.support.network.server.ServerSetting;
import com.chua.rsocket.support.server.RSocketServer;

ServerSetting setting = ServerSetting.defaults();
setting.setPort(7000);

RSocketServer server = new RSocketServer(setting);
server.onSubscribe("order", payload -> System.out.println("收到: " + payload));
server.start();

server.publish("order", "hello");   // 推送给订阅了 order 主题的 请求流 客户端

server.stop();
```

注解式写法（`registerBean` 扫描 `@OnOpen` / `@OnMessage` / `@OnClose`）：

```java
server.registerBean(new Object() {
    @OnOpen
    public void onConnect() { System.out.println("客户端连接"); }

    @OnMessage("order")
    public void onOrder(String payload) { System.out.println("收到: " + payload); }

    @OnClose
    public void onDisconnect() { System.out.println("客户端断开"); }
});
```

### 3.3 客户端：RSocketSyncClient

```java
import com.chua.rsocket.support.sync.RSocketSyncClient;

RSocketSyncClient client = new RSocketSyncClient("tcp://127.0.0.1:7000");
client.subscribe("order", (topic, data) -> System.out.println(topic + " -> " + data));
client.connect();
client.send("order", "hello");
client.disconnect();
```

说明：`RSocketSyncClient` 的载荷格式为 `topic:message`（见第 4 节）；`send` 走 fire 和 forget，`subscribe` 走 请求流。断开后默认无限次重连，间隔 3000ms。

### 3.4 分发器：RSocketDispatcherProvider

```java
import com.chua.rsocket.support.dispatcher.RSocketDispatcherProvider;

// DispatcherConfig 来自 utils-support-common-starter（concurrent.dispatcher 包），
// url 支持 rsocket://、http://、https:// 前缀，如 rsocket://127.0.0.1:7000
RSocketDispatcherProvider dispatcher = new RSocketDispatcherProvider(config);
dispatcher.start();                     // 连接远端 RSocket 服务并注册已订阅主题
dispatcher.publish("order", "hello");   // fire 和 forget 发布到远端
dispatcher.close();
```

订阅侧传入 `DispatcherDefinition`（同样来自 common-starter），本模块负责把远端推送的每条消息经 `DispatcherDefinition.dispatch(data)` 分发给本地订阅者。

---

## 4. 通信约定（载荷格式）

以下格式均直接来自源码实现，使用时请保持一致：

| 场景 | 载荷格式 |
| --- | --- |
| `RSocketServer` 提取主题 | 优先解析 data 的 JSON 字段 `topic`；解析失败再读 metadata 的 JSON 字段 `route`（兼容 OAuth client 将路由放在 metadata 中）；均失败则视为无主题 |
| `RSocketDispatcherProvider` 发布 | data 为 JSON：`{"action":"publish","topic":...,"data":...}`，走 fire 和 forget |
| `RSocketDispatcherProvider` 订阅 | data 为 JSON：`{"action":"subscribe","topic":...}`，走 请求流；同一 topic 只注册一次 |
| `RSocketSyncClient` 发送 | data 为字符串拼接：`topic + ":" + message`，走 fire 和 forget |
| `RSocketDataSyncAgentServer` / `RSocketAgentDataSyncSource` | data 为 JSON：`type` 取 `register` / `pull` / `push`，附带 `agentId`、`sourceId`、`offset`、`data` 字段；`pull` 走 请求流 / 请求响应，`push` 走 fire 和 forget |

---

## 5. 配置项

**本模块无任何外部配置文件与配置键**（`src/main/resources` 下仅有 `META-INF/extensions/` SPI 注册文件与 GraalVM native-image 配置，无 `application*.yml` / `*.properties` / `spring.factories`）。所有配置均通过构造参数与设置对象传入：

| 配置来源 | 项 | 说明 |
| --- | --- | --- |
| `ServerSetting`（common-starter） | `host`、`port` 等 | `RSocketServer` / `RSocketSyncServer` 的监听地址与端口，示例取 `ServerSetting.defaults()` 后 `setPort(7000)` |
| `DispatcherConfig`（common-starter） | `url` | 远端 RSocket 服务地址；支持 `rsocket://`、`http://`、`https://` 前缀；未配置时主机默认 `localhost`、端口默认 `7000` |
| `DispatcherConfig`（common-starter） | `connectionTimeoutMillis` | 连接超时；`<= 0` 时取默认 5000ms |
| `RSocketSyncClient` | `serverUrl` | 构造参数；URI 端口 `<= 0` 时按 `7000` 处理；连接阻塞上限 10s；keepAlive 30s / 90s |
| `RSocketDataSyncAgent` | `agentId` / `sourceId` / `host` / `port` / `source` | 构造参数；keepAlive 30s / 10s |
| `RSocketDataSyncAgentServer` | `port` | 构造参数；绑定 `0.0.0.0:port` |
| `RSocketAgentDataSyncSource` | `agentId` / `sourceId` / `host` / `port` | 构造参数；双参构造默认 `localhost:8080` |

内置常量（源码写死，不可配）：`RSocketSyncClient` 重连间隔 `3000ms`、最大重连次数 `0`（表示无限重连）；`RSocketServer` 启动时将 Netty 工人线程数设为 `max(4, CPU 核数 * 2)`（系统属性 `reactor.netty.ioWorkerCount`），业务处理使用虚拟线程执行器异步派发。

---

## 6. SPI 扩展点

本模块是 SPI 的**实现方**：扩展点接口全部定义在 `utils-support-common-starter`，本模块以名称 `rsocket` 注册实现。注册文件位于 `src/main/resources/META-INF/extensions/`：

| 扩展点接口（common-starter） | 注册名 | 实现类 |
| --- | --- | --- |
| `com.chua.common.support.network.server.Server` | `rsocket` | `com.chua.rsocket.support.server.RSocketServer` |
| `com.chua.common.support.network.server.SyncServer` | `rsocket` | `com.chua.rsocket.support.sync.RSocketSyncServer` |
| `com.chua.common.support.network.sync.SyncClient` | `rsocket` | `com.chua.rsocket.support.sync.RSocketSyncClient` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `rsocket` | `com.chua.rsocket.support.dispatcher.RSocketDispatcherProvider` |

按名称获取实现（`ServiceProvider` 用法见 common-starter 文档）：

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.network.server.Server;

Server server = ServiceProvider.of(Server.class).getNewExtension("rsocket", setting);
```

本模块自身**不提供新的扩展点接口**；DataSync 侧实现的是 `utils-support-datasync-agent-starter` 中的 `DataSyncAgent` / `DataSyncSource` 契约（该依赖为 `provided`，由使用方提供）。

---

## 7. 构建与依赖

模块 POM（父模块 `utils-support-network-parent` 4.0.0.51）：

```text
utils-support-rsocket-starter
├── com.chua:utils-support-common-starter          （compile，契约与工具）
├── io.rsocket:rsocket-core                        （1.1.5，根 POM dependencyManagement 统一托管）
├── io.rsocket:rsocket-transport-netty             （1.1.5，Netty TCP 传输）
├── com.chua:utils-support-datasync-agent-starter  （provided，DataSync Agent 契约）
└── com.chua:utils-support-datasync-starter        （provided，DataSync 契约）
```

- 反应堆：`reactor-core`（`Mono` / `Flux`）随 rsocket-core 传递引入。
- 编译：JDK 25（`maven.compiler.source/target=25`），源码使用 record、箭头 switch 等语法。
- GraalVM native-image：内置 `META-INF/native-image/com.chua/utils-support-rsocket-starter/` 下的 `reflect-config.json`（注册 `RSocketServer`、`RSocketDispatcherProvider`、`RSocketSyncServer`、`RSocketSyncClient` 四个类的全部构造器 / 方法 / 字段）与 `resource-config.json`（包含 `META-INF/extensions/.*`）。
- 构建：在本模块目录执行 `mvn -o -q compile -DskipTests`；根 POM 默认 `skipTests=true` 且本模块无测试源码。

---

## 8. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| Spring Boot 自动装配 | 无 `spring.factories` / 自动配置导入文件，无 Spring 依赖；"starter" 只是仓内命名惯例，需自行 `new` 或经 SPI 装配 |
| TLS / 流加密 | `RSocketServer` 启动时显式拒绝流加密（本实现未接入 TLS）；如需加密请用反向代理终结 TLS 或改用支持 TLS 的传输层 |
| WebSocket / HTTP 传输 | 仅提供 TCP 传输（`TcpServerTransport` / `TcpClientTransport`）；WebSocket、HTTP 等传输源码未提供 |
| 数据同步业务规则 | 本模块只实现 DataSync 的通信接入（Agent / Source / AgentServer 的 RSocket 通道）；同步编排、offset 管理、存储等业务在 `utils-support-datasync-*` 模块。`RSocketAgentDataSyncSource.currentOffset()` 固定返回 `null`，checkpoint 语义由上层负责 |
| Subscriber SPI 实现类 | 模块 POM 描述提到 "Subscriber SPI 实现"，但本模块源码未提供名为 Subscriber 的类（源码未提供） |
| 单元测试 | `src/test` 不存在；根 POM `maven.test.skip=true` / `skipTests=true` |
| RSocket 协议本身 | 协议编解码、帧、复用等由 `io.rsocket:rsocket-core` 1.1.5 提供，本模块只是使用方 |
