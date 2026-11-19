# utils-support-mqtt-starter

> utils 体系的 **MQTT 消息中间件集成模块**：内嵌 MQTT 3.1.1 服务端、基于 Eclipse Paho 的链式客户端、跨进程事件分发器与注解监听解析器，统一通过本仓 SPI 体系按名称 `mqtt` 装配。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 pom `maven.compiler.source/target=25`）｜包名：`com.chua.mqtt.support`（子包 `server` / `client` / `dispatcher` / `parser`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. 主题与 QoS 语义](#5-主题与-qos-语义)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mqtt-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 服务端 | 内嵌 MQTT 3.1.1 Broker：原生 `ServerSocket` + 虚拟线程，支持 CONNECT/PUBLISH/SUBSCRIBE 等全量报文与遗嘱消息 | 不是全功能 Broker：无保留消息、无共享订阅、无 TLS、无集群 |
| 客户端 | Eclipse Paho MQTT v3 的链式封装（订阅/发布/注解回调/自动重连补订阅） | 不是自研协议栈，也不是 MQTT v5 客户端 |
| 分发 | `DispatcherProvider` SPI 的 MQTT 实现，借 Broker 做跨进程发布/订阅 | 不保留离线消息（`MemoryPersistence`） |
| 集成 | utils 层 SPI 模块，随依赖引入即注册 | 不是 Spring Boot starter，无自动配置类 |

**判断口径**：点对点收发消息用 `MqttClientWrapper`；要嵌入统一事件分发体系用 `MqttDispatcherProvider`（SPI `mqtt`）；要在进程内起一个 Broker 用 `MqttServer`（SPI `mqtt`）。

### 1.3 源码地图

| 类型 | 位置 | 说明 |
| --- | --- | --- |
| `MqttServer` | `com.chua.mqtt.support.server` | 内嵌服务端，继承 `AbstractServer`，SPI `mqtt` |
| `MqttClientWrapper` | `com.chua.mqtt.support.client` | 链式客户端，`AutoCloseable`，直用类（非 SPI） |
| `MqttDispatcherProvider` | `com.chua.mqtt.support.dispatcher` | 分发器提供者，继承 `AbstractDispatcherProvider`，SPI `mqtt` |
| `MqttListenerParser` | `com.chua.mqtt.support.parser` | `@OnOpen/@OnClose/@OnMessage/@OnError` 解析器，SPI `mqtt` |
| SPI 注册文件 | `src/main/resources/META-INF/extensions/` | `Server`、`ListenerParser`、`DispatcherProvider` 三个接口各一条 `mqtt=` 记录 |
| native-image | `src/main/resources/META-INF/native-image/com.chua/utils-support-mqtt-starter/` | `reflect-config.json`（三类全部登记）、`proxy-config.json`、`resource-config.json` |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起内嵌 MQTT 服务端 | `new MqttServer(ServerSetting)` → `start()` | `publish()` 服务端发布、`onSubscribe()` 注册处理器、`getSessionCount()` 在线数 |
| 注解监听连接/消息 | `server.registerBean(handler)` | `@OnOpen` / `@OnClose` / `@OnMessage("order/#")` / `@OnError` |
| 连接凭据校验 | `server.withAuthenticator(...)` | `MqttAuthenticator.authenticate(clientId, username, password)` |
| 链式客户端 | `MqttClientWrapper.builder().broker(...).clientId(...).build().start()` | broker/clientId/username/password/keepAlive/cleanSession 等可配 |
| 订阅主题 | `client.subscribe().topic("order/#").qos(1).handler(...).start()` | 支持 `#`/`+` 通配符；`stop()` 取消订阅 |
| 发布消息 | `client.publish().topic("order/created").payload("...").qos(1).send()` | 另有 `sendAsync()`、`sendAndWait(timeoutMs)`、`retained(boolean)` |
| 跨进程事件分发 | `new MqttDispatcherProvider(DispatcherConfig)` 或 SPI 取 `mqtt` | `start()` → `publish(topic, body)` / `subscribe(definition)` → `close()` |
| 注解解析 | `new MqttListenerParser().parse(clazz)` | 返回事件名（`open`/`close`/`error`/`message`/`message:topic`）→ `Method` |
| 主题通配符工具 | `MqttTopics`（位于 utils-support-common-starter） | `matches` / `isValidFilter` / `isValidTopic` / `effectiveQos` |

### 2.2 MqttServer 主要方法

| 方法 | 说明 |
| --- | --- |
| `MqttServer(ServerSetting setting)` | 构造；`start()` / `stop()` / `isRunning()` / `close()` 继承自 `AbstractServer` |
| `withAuthenticator(MqttAuthenticator)` | 设置凭据校验器；为 `null`（默认）时接受任意 CONNECT |
| `setMaxSupportedQos(int)` | 服务端最大出站 QoS，取值 0~1，默认 1；SUBACK 按授予 QoS 回包 |
| `onSubscribe(String topic, BiConsumer<String, String> handler)` | 注册原生主题处理器，支持 `#`/`+`；非法过滤器抛 `IllegalArgumentException` |
| `publish(String topic, String payload)` / `publish(topic, payload, qos)` | 服务端发布：投递本地处理器 + 注解方法 + 所有匹配的在线订阅者 |
| `registerBean(Object handler)` | 扫描 `@OnOpen/@OnClose/@OnMessage/@OnError` 并登记（`@OnMessage` 缺省 topic 为 `#`） |
| `getSessionCount()` | 当前在线会话数 |
| `getProtocolType()` | 返回 `ProtocolType.MQTT` |

嵌套类型：`MqttServer.MqttAuthenticator`（函数式接口，`boolean authenticate(String clientId, String username, String password)`）、`MqttServer.MqttServerException`（运行时异常）。

### 2.3 MqttClientWrapper 主要方法

| 方法 | 说明 |
| --- | --- |
| `MqttClientWrapper.create(String broker)` / `builder()` | 两个静态工厂 |
| `start()` | 连接 Broker；连接失败抛 `MqttClientException` |
| `subscribe()` → `SubscribeOperation` | `topic(t).qos(q).handler((topic,msg)->...)` 或 `.onMessage(msg->...)`，`start()` 生效、`stop()` 退订 |
| `publish()` → `PublishOperation` | `topic(t).payload(String或byte[]).qos(n)`（或 `qos0/1/2()`）、`retained(r)`、`send()`、`sendAsync()` 返回 `IMqttDeliveryToken`、`sendAndWait(timeoutMs)` |
| `register(Object handler)` | 注解回调；`@OnMessage` 按 QoS 1 下发订阅，先 register 后 start 也生效 |
| `onConnect(Runnable)` / `onDisconnect(Consumer<Throwable>)` / `onMessage(String topic, BiConsumer<String, String>)` | 编程式监听 |
| `isConnected()` | 是否已连接 |
| `shutdown()` / `close()` | 断开并释放；实现 `AutoCloseable` |
| Builder 参数 | `broker`（默认 `tcp://127.0.0.1:1883`）、`clientId`（默认 `mqtt-client-` + 时间戳）、`username`/`password`、`keepAlive`（60 秒）、`cleanSession`（true）、`connectionTimeout`（10 秒）、`automaticReconnect`（true） |

异常：`MqttClientWrapper.MqttClientException`（运行时异常）。自动重连成功后按期望订阅表自动补订阅（`cleanSession=true` 时 Broker 侧订阅会随连接丢失）。

---

## 3. 快速开始

### 3.1 服务端：起一个内嵌 Broker

```java
ServerSetting setting = ServerSetting.defaults();
setting.setPort(1883);
MqttServer server = new MqttServer(setting);

server.registerBean(new Object() {
    @OnOpen
    public void onConnect() { System.out.println("客户端连接"); }

    @OnMessage("order/#")
    public void onOrder(String payload) { System.out.println("收到: " + payload); }

    @OnClose
    public void onDisconnect() { System.out.println("客户端断开"); }
});

server.start();
server.publish("order", "hello");   // 投递给本地处理器与所有匹配的在线订阅者
server.stop();
```

生产部署建议加凭据校验（默认接受任意 CONNECT）：

```java
server.withAuthenticator((clientId, username, password) ->
        "admin".equals(username) && "secret".equals(password));
```

### 3.2 客户端：订阅与发布

```java
MqttClientWrapper client = MqttClientWrapper.builder()
        .broker("tcp://127.0.0.1:1883")
        .clientId("my-client")
        .username("user").password("pass")
        .build();
client.start();

// 链式订阅
client.subscribe().topic("order/#").qos(1).handler((topic, msg) -> {
    System.out.println(topic + ": " + msg);
}).start();

// 链式发布（另有 sendAsync() / sendAndWait(timeoutMs)）
client.publish().topic("order/created").payload("{\"id\":1}").qos(1).send();

client.shutdown();
```

### 3.3 分发器：跨进程事件分发（SPI `mqtt`）

```java
DispatcherConfig config = DispatcherConfig.builder()
        .url("tcp://127.0.0.1:1883")     // 必填，缺失时 start() 抛 IllegalStateException
        .clientId("dispatcher-demo")     // 可选，缺省为 mqtt-dispatcher- + 时间戳
        .build();

MqttDispatcherProvider dispatcher = new MqttDispatcherProvider(config);
dispatcher.start();
dispatcher.publish("config-changed", "reload");   // QoS 1、不保留

// 订阅：DispatcherDefinition(订阅者对象, 目标方法, 主题列表)
DispatcherDefinition definition = new DispatcherDefinition(
        listener, Listener.class.getMethod("onChanged", String.class), List.of("config-changed"));
dispatcher.subscribe(definition);

dispatcher.close();
```

---

## 4. 配置项

本模块**无任何配置文件键**（无 `application.properties` / `application.yml` 项，源码中亦无配置绑定注解），全部配置通过代码 API 完成：

| 维度 | 配置载体 | 可配项（与源码一致） |
| --- | --- | --- |
| 服务端 | `ServerSetting` | `host`、`port`（0 时由系统分配并回填）、`soReuseAddr`、`bufferSize`（接收缓冲下限 16384）、`backlog`（下限 2048）、`maxConnections`（>0 时超限拒绝）、`tcpNoDelay`、`maxRequestSize`（报文剩余长度上限，封顶 268435455）、`shutdownQuietPeriod`（优雅关闭等待）；`encrypt` 必须为 `false`，否则启动即失败 |
| 服务端 QoS | `setMaxSupportedQos(int)` | 0~1，默认 1 |
| 客户端 | `MqttClientWrapper.Builder` | `broker` / `clientId` / `username` / `password` / `keepAlive` / `cleanSession` / `connectionTimeout` / `automaticReconnect`（默认值见 2.3） |
| 分发器 | `DispatcherConfig` | 本实现仅消费 `url`（必填）与 `clientId`（可选）；其余字段对 MQTT 实现无效果 |

系统级开关：`AbstractServer` 支持 `-Dserver.accesslog.enabled=true`、`-Dserver.tracing.enabled=true` 挂载访问日志/链路追踪过滤器（对 MQTT 无请求级语义，通常用不到）。

---

## 5. 主题与 QoS 语义

### 5.1 主题通配符（`MqttTopics`，来自 common-starter）

- `#` 匹配剩余所有层级，必须独占一个层级且位于末尾；`sport/#` 同时匹配 `sport` 与 `sport/a/b`。
- `+` 只匹配一个层级，必须独占一个层级；`sport/+` 不匹配 `sport/a/b`。
- 层级按 `/` 切分且保留空层级：`order/` 与 `order` 是两个主题，`order/+` 匹配 `order/`。
- 以 `$` 开头的系统主题只允许精确订阅，不参与首层 `#`/`+` 匹配。
- 发布主题不得包含通配符（`isValidTopic` 校验，违规抛 `IllegalArgumentException`）。

### 5.2 服务端投递语义（`MqttServer`）

- 入站 QoS 2 完整握手：PUBLISH → PUBREC → PUBREL → 投递 → PUBCOMP。
- 出站最大 QoS 为 `setMaxSupportedQos`（默认 1）；SUBACK 按授予 QoS 回包，转发按 `min(发布 QoS, 授予 QoS)`（再受服务端上限约束）降级。
- **不支持保留消息**（RETAIN 标志被忽略）与**共享订阅**（`$share/`）。
- 异常断开（未收到 DISCONNECT 即断链）时按 CONNECT 的 Will 标志发布遗嘱消息。
- keepAlive > 0 时，读超时按 1.5 个 keepAlive 周期收口；PINGREQ 回 PINGRESP。
- 仅支持协议名 `MQTT` / `MQIsdp`、协议级别 3 / 4（即 MQTT 3.1 / 3.1.1）；CONNACK 返回码：`0x00` 接受、`0x01` 协议名/版本不可接受、`0x02` 标识符被拒、`0x04` 用户名或密码错误。
- 空 clientId 仅在 cleanSession 时允许，服务端自动生成 `session-N` 标识。
- 线程模型：accept 与会话处理均使用虚拟线程（`ThreadUtils.newVirtualThreadPerTaskExecutor`）；每个会话的写出在独立写锁下串行，保证帧完整。

---

## 6. SPI 扩展点

本模块**不定义新的 SPI 接口**，全部是实现 common-starter 的既有扩展点，注册文件位于 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | 名称 | 实现 | 注册内容 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.server.Server` | `mqtt` | `com.chua.mqtt.support.server.MqttServer` | `mqtt=com.chua.mqtt.support.server.MqttServer` |
| `com.chua.common.support.network.annotations.ListenerParser` | `mqtt` | `com.chua.mqtt.support.parser.MqttListenerParser` | `mqtt=com.chua.mqtt.support.parser.MqttListenerParser` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `mqtt` | `com.chua.mqtt.support.dispatcher.MqttDispatcherProvider` | `mqtt=com.chua.mqtt.support.dispatcher.MqttDispatcherProvider` |

另在 `META-INF/services/com.chua.common.support.network.server.Server` 登记 `com.chua.mqtt.support.server.MqttServer`（JDK `ServiceLoader` 通道）。

按名称获取：

```java
Server server = ServiceProvider.of(Server.class).getExtension("mqtt");
DispatcherProvider dispatcher = ServiceProvider.of(DispatcherProvider.class).getExtension("mqtt");
```

`MqttClientWrapper` 是直用类，不走 SPI。GraalVM native-image 的 `reflect-config.json` 已登记 `MqttServer` / `MqttDispatcherProvider` / `MqttListenerParser` 三个类的全部构造器、方法与字段。

---

## 7. 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mqtt-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

依赖树（`pom.xml`）：

```
utils-support-mqtt-starter
├── com.chua:utils-support-common-starter        （契约层：Server/ListenerParser/DispatcherProvider SPI、MqttTopics、AbstractServer 等）
└── org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5
```

- 所属父模块：`com.chua:utils-support-middleware-parent`（Kafka、RabbitMQ、Redis 等中间件集成聚合模块）。
- 编译口径：JDK 25，UTF-8 源码编码。
- 构建：在 `utils-support-parent-starter` 仓库根目录执行 `mvn -o -q compile -DskipTests`。
- 本模块当前无测试源码目录。

---

## 8. 边界：这些能力不在本模块

- **无 TLS**：`ServerSetting#encrypt=true` 时服务端启动即失败（提示用反向代理终结 TLS 后转发到明文端口）；客户端侧也未提供 TLS 配置。
- **无保留消息**：服务端忽略 RETAIN 标志；客户端虽可 `retained(true)`，但本模块服务端不会保存/回放保留消息。
- **无共享订阅**：不支持 `$share/` 前缀的共享订阅语义。
- **无 MQTT 5.0**：协议级别仅支持 3 / 4（MQTT 3.1 / 3.1.1）。
- **无会话持久化与离线消息**：服务端会话在内存中，分发器使用 `MemoryPersistence`；Broker 重启或客户端离线期间的消息不保证补投。
- **无细粒度权限模型**：只有连接级凭据校验回调（`MqttAuthenticator`），没有主题级 ACL。
- **非 Spring Boot starter**：没有自动配置类与 `spring.factories`/`AutoConfiguration.imports`；在 Spring 工程中使用需自行装配 Bean。
- **外部 Broker 能力**：Kafka / RabbitMQ / Redis / NATS 等其他中间件由 `utils-support-middleware-parent` 下的同层模块提供，不在本模块。
