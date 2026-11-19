# utils-support-socketio-starter

> Socket.IO 实时通信集成模块：提供基于 netty-socketio 的**嵌入式 Socket.IO 服务端**、基于 socket.io-client 的**同步客户端 / 远程分发器**，以及**数据同步（DataSync）Agent 的 Socket.IO 通道**，统一以 SPI 名 `socketio` 接入 common 网络体系。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.socketio.support`（子包 `server` / `sync` / `dispatcher` / `source` / `agent`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-socketio-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块全名 `Utils Support SocketIO Starter`，隶属于 `utils-support-network-parent`（网络协议集成父模块），父版本 `4.0.0.51`。核心类自 `4.0.0.42` 起提供。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 服务端 | 基于 **netty-socketio 2.0.14** 的嵌入式 Socket.IO 服务端（`SocketIOServer`），传输协商 `POLLING` + `WEBSOCKET` | 不是独立部署的 Socket.IO 服务器进程；随宿主进程内嵌启动 |
| 客户端 | 基于 **socket.io-client 2.1.2** 的客户端接入（`SocketIOSyncClient`、`SocketIoDispatcherProvider`） | 客户端侧不在本模块启动任何监听端口，全部连接指向外部服务 |
| SPI | common 体系 `Server` / `SyncClient` / `DispatcherProvider` 的 `socketio` 实现；`SocketIOSyncServer` 同时实现 `SyncServer` 与 `SyncProtocol` | 不提供 TCP / UDP / HTTP 等其它协议的实现 |
| 数据同步 | DataSync Agent 的 Socket.IO 通道（`SocketIODataSyncAgent` / `SocketIODataSyncAgentServer` / `SocketIOAgentDataSyncSource`） | 不实现数据同步的存储、offset 持久化等本身能力（由 datasync 系列模块负责） |
| 依赖 | 只依赖 `utils-support-common-starter`、netty-socketio、socket.io-client；两个 datasync 依赖为 `provided` | 不依赖 Spring，无 Spring 自动装配，零配置文件键 |

### 1.3 源码结构

| 类（按声明包） | 文件位置 | 职责 |
| --- | --- | --- |
| `server.SocketIOServer` | `com/chua/socketio/support/server/SocketIOServer.java` | 嵌入式 Socket.IO 服务端，注解驱动 + 主题订阅/广播 |
| `server.SocketIODataSyncAgentServer` | `com/chua/socketio/support/SocketIODataSyncAgentServer.java` | DataSyncServer 侧的 Agent 接入服务端 |
| `sync.SocketIOSyncServer` | `com/chua/socketio/support/sync/SocketIOSyncServer.java` | 同步服务端（委托 `SocketIOServer`） |
| `sync.SocketIOSyncClient` | `com/chua/socketio/support/sync/SocketIOSyncClient.java` | 同步客户端（自动重连） |
| `sync.SocketIOSyncFlow` | `com/chua/socketio/support/sync/SocketIOSyncFlow.java` | 服务端 + 客户端的同步流程编排 |
| `dispatcher.SocketIoDispatcherProvider` | `com/chua/socketio/support/SocketIoDispatcherProvider.java` | 接入远程 Socket.IO 服务的消息分发器 |
| `agent.SocketIODataSyncAgent` | `com/chua/socketio/support/SocketIODataSyncAgent.java` | Agent 侧数据同步客户端 |
| `source.SocketIOAgentDataSyncSource` | `com/chua/socketio/support/SocketIOAgentDataSyncSource.java` | Server 侧 Agent 数据源适配 |

> 说明：`SocketIODataSyncAgentServer`、`SocketIODataSyncAgent`、`SocketIOAgentDataSyncSource` 三个文件物理上放在 `com/chua/socketio/support/` 目录，但声明的包分别为 `server` / `agent` / `source` 子包，import 时以声明包为准。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起 Socket.IO 服务端 | `server.SocketIOServer` | `new SocketIOServer(ServerSetting)`，SPI 名 `socketio` |
| 注解监听 连接/消息/断开 | `registerBean` + `@OnOpen` / `@OnMessage` / `@OnClose` | `@OnMessage("topic")` 绑定主题，注解值为空时默认主题 `default` |
| 编程式订阅 / 广播 | `onSubscribe(topic, handler)` / `publish(topic, payload)` | 服务端启动前后均可订阅；广播发给所有客户端 |
| 同步服务端 | `sync.SocketIOSyncServer` | 实现 `SyncServer` + `SyncProtocol`，协议名 `socketio` |
| 同步客户端 | `sync.SocketIOSyncClient` | 自动重连：间隔 3 秒、次数不限（`MAX_RECONNECT = 0`） |
| 服务端 + 客户端编排 | `sync.SocketIOSyncFlow` | 一键 `start()` / `stop()`，`getServer()` / `getClient()` |
| 接入远程 Socket.IO 做分发 | `dispatcher.SocketIoDispatcherProvider` | SPI 名 `socketio`；纯客户端，不启动本地服务 |
| DataSync Agent 通道 | `agent.SocketIODataSyncAgent`、`server.SocketIODataSyncAgentServer`、`source.SocketIOAgentDataSyncSource` | 事件：`register` / `pull` / `push` / `pull_result` / `push_result` / `datasync_event` |

### 2.2 SocketIOServer（嵌入式服务端）

继承 `AbstractServer`（即 common 的 `ConfigServer` / `Server` 体系），生命周期方法 `start()` / `stop()` / `isRunning()` 直接可用；支持 `ServerFilter` 过滤器链与 `@OnOpen` / `@OnClose` / `@OnMessage` 注解处理。

| 方法 | 说明 |
| --- | --- |
| `SocketIOServer(ServerSetting setting)` | 按配置创建；启动时读取 `host` / `port` / `bossThreads` / `workerThreads` |
| `registerBean(Object handler)` | 扫描 `@OnMessage` 注解绑定主题处理器（返回本实例，支持链式）；`@OnOpen` / `@OnClose` 由连接/断开事件回调 |
| `onSubscribe(String topic, ServerHandler handler)` | 编程式注册主题处理器；服务已启动时动态补挂监听 |
| `publish(String topic, String payload)` | 向所有订阅该主题的客户端广播 |
| `getProtocolType()` | 返回 `ProtocolType.WS` |

行为要点：

- `@OnMessage` 注解值为空时，主题按 `"default"` 处理；同一主题重复注册时**后注册覆盖先注册**。
- `@OnMessage` 方法参数仅支持 `String`（消息体）、`byte[]`（按 `ServerSetting.charset` 转换）、`ServerRequest`、`ServerResponse`，其它类型抛 `IllegalArgumentException`。
- 处理器返回非 `null` 结果时：结果通过 Socket.IO **ACK** 返回给发送方，并同时向该主题**广播**。
- 连接预算：`maxConnections` 生效，超限连接在握手后立即 `disconnect()` 拒绝。
- 传输固定为 `Transport.POLLING` + `Transport.WEBSOCKET`。
- **不支持 TLS**：启动时显式拒绝（提示用反向代理终结 TLS 后再转发到明文端口）。

### 2.3 SocketIOSyncServer / SocketIOSyncClient / SocketIOSyncFlow（同步体系）

`SocketIOSyncServer` 实现 `SyncServer` + `SyncProtocol`（`getProtocol()` 返回 `"socketio"`），内部委托 `SocketIOServer`；`createClient(setting)` 通过 `SyncAddressResolver.resolveAddress(setting, "http://127.0.0.1:19380")` 解析客户端地址（默认 `http://127.0.0.1:19380`）。注意其 `getProtocolType()` 返回 `ProtocolType.UNKNOWN`。

| 类 | 关键方法 | 说明 |
| --- | --- | --- |
| `SocketIOSyncServer` | `publish(topic, message)`、`send(clientId, topic, message)`、`getConnectedClients()`、`getClientMetadata(clientId)`、`addListener/removeListener(SyncServerListener)` | 两者 `publish`/`send` 均委托底层广播；`send` 额外通知监听器 |
| `SocketIOSyncClient` | `connect()`、`disconnect()`、`isConnected()`、`getClientId()`、`send(topic, message)`、`subscribe(topic, SyncMessageHandler)`、`unsubscribe(topic)`、`addListener/removeListener(SyncFlowListener)`、`getMetadata()`、`close()` | 客户端 ID 为随机 UUID；未连接时 `send` 抛 `IllegalStateException`；`getMetadata()` 返回 `clientId` 与 `protocol=socketio` |
| `SocketIOSyncFlow` | `start()`、`stop()`、`isRunning()`、`getServer()`、`getClient()`、`addListener/removeListener(SyncFlowListener)`、`close()` | 三个构造：(ServerSetting, serverUrl) 两端都建、(serverUrl) 仅客户端、(ServerSetting) 仅服务端 |

### 2.4 SocketIoDispatcherProvider（远程分发器）

继承 `AbstractDispatcherProvider`，SPI 名 `socketio`。作为**纯客户端**接入外部已部署的 Socket.IO 服务，本地不启动任何服务器。

| 方法 | 说明 |
| --- | --- |
| `SocketIoDispatcherProvider(DispatcherConfig config)` | 从分发器配置读取地址与超时 |
| `start()` | 建立连接；重连无限次，延迟 1000ms 起、上限 5000ms；连接超时取 `connectionTimeoutMillis`（默认 5000ms） |
| `publish(String topic, Object body)` | 向远程服务 emit；未连接时仅告警不抛错 |
| `subscribe(DispatcherDefinition definition)` | 按定义的 topics 注册，同一 topic 只绑一次监听 |
| `unsubscribe(DispatcherDefinition definition)` | 取消订阅，topic 无订阅者时移除监听 |
| `close()` | 断开并清理订阅表 |

地址解析（`buildUrl()`）：取 `config.getUrl()`，剥离 `socketio://`、`http://`、`https://` 前缀后按 `host:port` 解析；缺省 `localhost:9092`。

### 2.5 DataSync Agent 通道（三个类）

| 类 | 侧 | 关键点 |
| --- | --- | --- |
| `SocketIODataSyncAgentServer` | DataSyncServer 侧 | 继承 `DefaultDataSyncAgentServer`；构造传端口，监听 `0.0.0.0:port`；处理 `register` 事件（载荷含 `agentId`、`sourceId`，ACK `{"status":"ok"}`）；`convertToSource(...)` 把已连接 Agent 转为 `SocketIOAgentDataSyncSource`；`pull_result` / `push_result` 监听当前为空实现（源码中为占位） |
| `SocketIODataSyncAgent` | Agent 侧 | 实现 `DataSyncAgent`；构造 `(agentId, sourceId, serverUrl, DataSyncSource)`；监听 `pull` / `push` 事件，结果以 `datasync_event` 事件回发（`type=pull_result` / `push_result`） |
| `SocketIOAgentDataSyncSource` | Server 侧 | 实现 `DataSyncSource`；`direction()` 为 `Direction.INPUT`；`read(offset, params)` 向 Agent 发 `pull` 事件并等待结果（30 秒超时）；`write(flux)` 发 `push` 事件（30 秒超时）；`currentOffset()` 返回 `null` |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-socketio-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 起一个嵌入式 Socket.IO 服务端（注解驱动）

```java
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.socketio.support.server.SocketIOServer;

ServerSetting setting = ServerSetting.defaults();
setting.setPort(9092);

SocketIOServer server = new SocketIOServer(setting);
server.registerBean(new Object() {

    @OnOpen
    public void onConnect() {
        System.out.println("客户端连接");
    }

    @OnMessage("order")
    public void onOrder(String payload) {
        System.out.println("收到: " + payload);
    }

    @OnClose
    public void onDisconnect() {
        System.out.println("客户端断开");
    }
});

server.start();
server.publish("order", "hello");   // 向所有客户端广播
// ...
server.stop();
```

### 3.3 客户端接入（同步客户端）

```java
import com.chua.socketio.support.sync.SocketIOSyncClient;

SocketIOSyncClient client = new SocketIOSyncClient("http://127.0.0.1:9092");
client.subscribe("order", (topic, message) -> System.out.println(topic + " -> " + message));
client.connect();
client.send("order", "ping");
// ...
client.close();
```

### 3.4 接入远程 Socket.IO 服务做消息分发

```java
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.socketio.support.dispatcher.SocketIoDispatcherProvider;

DispatcherConfig config = new DispatcherConfig();
config.setUrl("socketio://127.0.0.1:9092");

SocketIoDispatcherProvider provider = new SocketIoDispatcherProvider(config);
provider.start();
// provider.subscribe(definition);   // 按 DispatcherDefinition 订阅主题
// provider.publish("order", "hi");  // 向远程服务发布
provider.close();
```

---

## 4. 配置项

本模块**没有 Spring 配置文件键 / 环境变量绑定**（源码未提供 `@ConfigurationProperties` 之类的配置注入），全部配置通过构造参数与公共 Setter 传入。

### 4.1 服务端：`ServerSetting`（本模块实际消费的字段）

| 字段 | 说明 | 未设置时 |
| --- | --- | --- |
| `host` | 监听地址，写入 netty-socketio `Configuration.hostname` | 由 `ServerSetting` 默认值决定 |
| `port` | 监听端口，写入 `Configuration.port` | 由 `ServerSetting` 默认值决定 |
| `bossThreads` / `workerThreads` | Netty 线程数 | 由 `ServerSetting` 默认值决定 |
| `maxConnections` | 连接上限；超限连接被立即 `disconnect()` | 不限（`<= 0` 时不拦） |
| `charset` | `@OnMessage` 参数为 `byte[]` 时的编码 | 由 `ServerSetting` 默认值决定 |

### 4.2 同步客户端地址

`SocketIOSyncServer.createClient(setting)` 走 `SyncAddressResolver.resolveAddress(setting, "http://127.0.0.1:19380")`，即**默认地址 `http://127.0.0.1:19380`**，可被 setting 中解析出的地址覆盖。

### 4.3 分发器：`DispatcherConfig`（本模块实际消费的字段）

| 字段 | 说明 | 缺省行为 |
| --- | --- | --- |
| `url` | 远程地址，支持 `socketio://`、`http://`、`https://` 前缀（解析时剥离） | `localhost:9092` |
| `connectionTimeoutMillis` | 连接超时 | `5000` ms |

### 4.4 固定行为（不可配置，源码写死）

| 项 | 值 |
| --- | --- |
| 服务端传输 | `POLLING` + `WEBSOCKET` |
| TLS | 不支持（启动即拒绝，要求反向代理终结 TLS） |
| `SocketIOSyncClient` 重连 | 开启自动重连，间隔 3000ms、上限 6000ms、次数不限 |
| `SocketIoDispatcherProvider` 重连 | 无限次，延迟 1000ms → 5000ms |
| DataSync 源读写超时 | 30 秒（`SocketIOAgentDataSyncSource`） |

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI（`META-INF/extensions/`）

| 接口 | 注册名 | 实现 |
| --- | --- | --- |
| `com.chua.common.support.network.server.Server` | `socketio` | `com.chua.socketio.support.server.SocketIOServer` |
| `com.chua.common.support.network.sync.SyncClient` | `socketio` | `com.chua.socketio.support.sync.SocketIOSyncClient` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `socketio` | `com.chua.socketio.support.dispatcher.SocketIoDispatcherProvider` |

`SocketIOServer`、`SocketIOSyncServer`、`SocketIoDispatcherProvider` 三个类同时标注了 `@Spi("socketio")`。

### 5.2 宿主可使用的扩展点（来自 common 体系）

| 扩展点 | 用法 |
| --- | --- |
| `ServerHandler` | 主题消息处理器：`onSubscribe(topic, handler)` 或 `@OnMessage` 方法 |
| `ServerFilter` | 服务端过滤器链（继承自 `AbstractServer`），`addFilter(...) / removeFilter(...)` 管理 |
| `SyncServerListener` | 同步服务端监听：客户端上/下线、消息、错误 |
| `SyncFlowListener` | 同步流程/客户端生命周期监听：`onStart` / `onStop` / `onError` / 消息 |
| `SyncMessageHandler` | 客户端主题消息处理（函数式接口 `handle(String topic, Object message)`） |

### 5.3 GraalVM native-image

`META-INF/native-image/com.chua/utils-support-socketio-starter/` 下：

- `reflect-config.json`：注册 `SocketIoDispatcherProvider`、`SocketIOServer`、`SocketIOSyncClient` 三个类的全量构造器/方法/字段反射；
- `resource-config.json`：包含 `META-INF/extensions/.*`（保证 SPI 声明文件进入原生镜像）。

---

## 6. 构建与依赖

模块路径：`utils-support-parent-starter/utils-support-network-parent/utils-support-socketio-starter`。

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -o -q compile -DskipTests
```

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父版本 | compile | 网络抽象（`AbstractServer`、`SyncClient`、`DispatcherProvider` 等） |
| `com.corundumstudio.socketio:netty-socketio` | 2.0.14 | compile | 服务端实现 |
| `io.socket:socket.io-client` | 2.1.2 | compile | 客户端实现 |
| `com.chua:utils-support-datasync-agent-starter` | `${project.version}` | **provided** | `SocketIODataSyncAgentServer` / `SocketIODataSyncAgent` 的 DataSync 契约 |
| `com.chua:utils-support-datasync-starter` | `${project.version}` | **provided** | 数据同步配套契约 |

> 两个 datasync 依赖是 `provided`：只用 datasync 相关类（`SocketIODataSyncAgentServer`、`SocketIODataSyncAgent`、`SocketIOAgentDataSyncSource`）时，宿主需在运行期自行提供这两个模块；只用服务端 / 同步 / 分发器能力则不需要。

---

## 7. 边界：这些能力不在本模块

| 不提供 | 说明 |
| --- | --- |
| TLS / SSL 监听 | 服务端启动时显式拒绝 streamEncryption；需要 HTTPS/WSS 时请用反向代理（Nginx 等）终结 TLS 后转发明文端口 |
| 非 String 负载 | 服务端事件监听固定按 `String` 接收；`@OnMessage` 参数仅支持 `String` / `byte[]` / `ServerRequest` / `ServerResponse` |
| 主题多处理器 | 每个主题只保留一个 `ServerHandler`，重复注册为覆盖（非叠加） |
| DataSync 的存储与 offset 管理 | 本模块只做 Socket.IO 通道；`SocketIOAgentDataSyncSource.currentOffset()` 恒为 `null`，offset 持久化由 datasync 体系负责 |
| Socket.IO 集群扩展 | Redis adapter、粘性会话、多节点房间同步等源码未提供 |
| Spring 自动装配 | 无 `AutoConfiguration`、无配置属性类；按普通库显式 `new` 使用 |
| 其它协议 | TCP / UDP / HTTP / WebSocket 裸协议等由 common 网络体系及其它 starter 提供，本模块只做 Socket.IO |
