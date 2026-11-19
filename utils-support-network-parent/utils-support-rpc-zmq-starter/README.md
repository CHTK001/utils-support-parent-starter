# utils-support-rpc-zmq-starter

> ZeroMQ RPC 实现模块：基于 **JeroMQ**（纯 Java、无原生依赖）提供 `RpcServer` / `RpcClient` / `Invoker` / `DispatcherProvider` 四个 SPI 实现，SPI 名称统一为 **`zmq`**。
>
> 版本：`4.0.0.51`（随父模块 `utils-support-network-parent`；核心类 `@since 4.0.0.42`）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.rpc.support.zmq`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 添加依赖](#31-添加依赖)
  - [3.2 RPC 服务端（ROUTER）](#32-rpc-服务端router)
  - [3.3 RPC 客户端（DEALER）](#33-rpc-客户端dealer)
  - [3.4 声明式远程调用（Invoker）](#34-声明式远程调用invoker)
  - [3.5 PUB/SUB 消息分发（Dispatcher）](#35-pubsub-消息分发dispatcher)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| RPC | `ZmqRpcServer`（ROUTER，多客户端并发）+ `ZmqRpcClient`（DEALER，动态代理）的 ZeroMQ 实现 | 不是 HTTP/WebSocket RPC；不含 Dubbo / SOFA / JSON-RPC 协议实现 |
| 传输 | 纯 Java 的 JeroMQ（`org.zeromq:jeromq`），支持 `tcp://` / `ipc://` / `inproc://` 端点 | 不依赖原生 ZeroMQ 库（libzmq），无需本机安装 |
| 调用器 | `ZmqInvoker`：为注解接口生成 ZMQ RPC 声明式代理 | 不是注册中心，也不是负载均衡 / 熔断组件 |
| 消息 | `ZmqDispatcherProvider`：PUB/SUB 主题发布订阅 | 不是消息持久化 / 消费位移管理器 |
| 注册方式 | SPI：`META-INF/extensions/` 登记 + `@Spi("zmq")` | 不是 Spring 自动装配模块（源码未提供自动配置类与配置文件键） |

**一句话**：在 `network.rpc` / `network.invoker` / `concurrent.dispatcher` 的 SPI 名称里写 `zmq`，就由本模块提供 ZeroMQ 版实现。

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起 ZeroMQ RPC 服务端 | `RpcServer.createService("zmq", registryConfigs, protocolConfig, appName)` | 返回 `ZmqRpcServer`；`registryConfigs` 传 `null` 走纯直连 |
| 注册服务 | `server.register(接口全限定名, 实现对象)` 或 `server.register(实现对象)`（扫描其全部接口） | 返回自身支持链式；同时写入 `LocalServiceRegistry.INSTANCE` 供 inline 直调 |
| 启动 / 关闭服务端 | `server.afterPropertiesSet()` / `server.close()` | 启动后绑定 `tcp://host:port`；关闭释放 Socket、线程池与服务发现 |
| 拿客户端代理 | `RpcClient.createClient("zmq", registryConfigs, consumerConfig, appName).get(接口.class)` | 返回 `ZmqRpcClient` 的动态代理 |
| 声明式调用 | `InvokerFactory.getInvoker("zmq").create(接口.class)` | `ZmqInvoker`；接口需标注端点地址 |
| 同 JVM 直调 | `consumerConfig.setInline(true)` | 目标服务已在本进程注册时跳过网络与序列化 |
| PUB/SUB 消息分发 | `new ZmqDispatcherProvider(DispatcherConfig)` | `publish(topic, body)` / `subscribe(definition)` / `unsubscribe(definition)` / `start()` / `close()` |
| 服务端指标 | `server.getMetrics()` | `RpcMetrics("zmq")`：总 / 成功 / 失败 / 在途调用数、服务数、启动时间 |

核心类一览：

| 类 | SPI 名 | 职责 |
| --- | --- | --- |
| `ZmqRpcServer` | `zmq` | ROUTER Socket 收包 → 业务线程池执行 → 按 identity 帧回包；方法反射查找结果缓存 |
| `ZmqRpcClient` | `zmq` | 每个目标接口一条 DEALER Socket；同步请求-响应；传输失败按配置重试，业务异常不重试 |
| `ZmqInvoker` | `zmq` | 把注解接口变成 ZMQ RPC 代理（`create` 带缓存 / `createNew` 不缓存） |
| `ZmqDispatcherProvider` | `zmq` | PUB 绑定 + SUB 连接同一地址，双帧报文 `[topic][序列化消息体]` |

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rpc-zmq-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 RPC 服务端（ROUTER）

```java
// 1) 双方共享的接口
public interface HelloService {
    String say(String name);
}

// 2) 服务端：不传注册中心与协议配置 → 直连模式，默认绑定 tcp://0.0.0.0:5555
RpcServer server = RpcServer.createService("zmq", null, null, "demo");
server.register(HelloService.class.getName(), new HelloServiceImpl());
server.afterPropertiesSet();          // 启动 ROUTER 收包线程与业务线程池
// ...
server.close();                       // 释放 Socket、线程池、服务发现与本地注册表
```

### 3.3 RPC 客户端（DEALER）

```java
RpcRegistryConfig registry = new RpcRegistryConfig();
registry.setProtocol("direct");               // direct / zmq / 空 = 直连；zookeeper / nacos = 走服务发现 SPI
registry.setAddress("tcp://127.0.0.1:5555");  // 缺 tcp:// 前缀会自动补全

RpcClient client = RpcClient.createClient("zmq",
        Collections.singletonList(registry), new RpcConsumerConfig(), "demo");

HelloService hello = client.get(HelloService.class);   // 动态代理
String rsp = hello.say("zmq");                          // 单帧 RpcRequest → 阻塞等 RpcResponse
client.close();
```

失败语义：传输类失败（`RpcException.transport`，如超时、发送失败）按 `retries` 次数重试、间隔 `retryDelay`；服务端业务异常（`RpcException.business`）直接抛出、不重试。

### 3.4 声明式远程调用（Invoker）

```java
@RemoteService(url = "tcp://127.0.0.1:5555", protocol = "zmq")
public interface HelloApi {
    String say(String name);
}

HelloApi api = InvokerFactory.getInvoker("zmq").create(HelloApi.class);
api.say("zmq");
```

端点地址也可写在类级 `@RequestMapping`、`@RequestMethod` 或 `@InvokerService` 的 `value` 中；地址缺 `tcp://` 前缀时自动补全。缺少端点地址时抛 `IllegalArgumentException`。

### 3.5 PUB/SUB 消息分发（Dispatcher）

直接使用：

```java
ZmqDispatcherProvider dispatcher = new ZmqDispatcherProvider(
        DispatcherConfig.builder().url("tcp://127.0.0.1:5556").build());  // 缺省即 tcp://127.0.0.1:5556
dispatcher.start();                       // 启动 SUB 收包线程

dispatcher.subscribe(new DispatcherDefinition(listener,
        listener.getClass().getMethod("onMessage", Object.class),
        List.of("news")));                // 订阅主题 news

dispatcher.publish("news", "hello");      // 发布：两帧 [topic][序列化消息体]
dispatcher.close();
```

配合 `@Subscribe` 注解与 `DispatcherFlow`：

```java
static class NewsListener {
    @Subscribe(topic = "news", type = "zmq")
    public void onMessage(Object body) { /* ... */ }
}

DispatcherFlow flow = new DispatcherFlow(false);   // false = 不挂默认内存分发器
flow.register(new ZmqDispatcherProvider(DispatcherConfig.builder().url("tcp://127.0.0.1:5556").build()));
flow.register(new NewsListener());                 // 扫描 @Subscribe 自动订阅
flow.publish("news", "hello");
flow.close();
```

## 4. 配置项

本模块**不读取任何 Spring 配置文件键**（源码未提供 `application.yml` 键、`@ConfigurationProperties` 或自动配置类）；所有配置通过构造参数对象传入。便捷入口：`RpcProtocolConfig.auto("zmq", port)` 与 `RpcConsumerConfig.auto()` 按当前系统（CPU / 堆）自动调优。

**服务端 `RpcProtocolConfig`（`ZmqRpcServer` 实际消费的字段）**

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `host` | `0.0.0.0` | 监听地址；为 `0.0.0.0` 时向服务发现注册 `127.0.0.1` |
| `port` | `5555` | 监听端口 |
| `threads` | CPU 核数 × 2 | 业务线程池大小（等待队列 1024，`CallerRunsPolicy`） |
| `serialization` | 自动：`fory` → `jackson` → JDK | 请求 / 响应序列化；客户端与服务端必须一致 |

**客户端 `RpcConsumerConfig`（`ZmqRpcClient` 实际消费的字段）**

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `timeout` | `10000` | DEALER 接收超时（毫秒），防止服务端无响应时无限阻塞 |
| `retries` / `retryEnabled` | `0` / 不启用 | 传输失败重试次数；`retryEnabled=false` 强制关闭 |
| `retryDelay` | `100` | 重试间隔（毫秒） |
| `inline` | `false` | 同 JVM 直调：目标服务已在本进程注册时直接调用本地对象 |
| `serialization` | 同服务端 | 请求 / 响应序列化 |

**注册中心 `RpcRegistryConfig`**

| 字段 | 说明 |
| --- | --- |
| `protocol` | `direct` / `zmq` / 空 → 纯直连；`zookeeper` / `nacos` 等 → SPI 加载 `ServiceDiscovery` |
| `address` | 直连端点或注册中心地址；客户端会补全 `tcp://` 前缀 |

**分发器 `DispatcherConfig`（`ZmqDispatcherProvider` 实际消费的字段）**

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `url` | `tcp://127.0.0.1:5556` | PUB/SUB 端点地址 |
| `serializer` | `jackson`（`JacksonSerialization`） | 消息体序列化器 SPI 名称（如 `fury` / `fory` / `jackson`），加载失败回退 Jackson |

**内置常量**：服务端收包循环空转间隔 `RECV_TIMEOUT=100ms`；客户端 `DEFAULT_RETRY_DELAY=100ms`；分发器收包空转间隔 `RECV_TIMEOUT=100ms`。

## 5. SPI 扩展点

本模块向容器登记 4 个 SPI 实现（`src/main/resources/META-INF/extensions/`，实现类均标注 `@Spi("zmq")`）：

| SPI 接口 | 登记名 | 实现 |
| --- | --- | --- |
| `com.chua.common.support.network.rpc.RpcServer` | `zmq` | `com.chua.rpc.support.zmq.ZmqRpcServer` |
| `com.chua.common.support.network.rpc.RpcClient` | `zmq` | `com.chua.rpc.support.zmq.ZmqRpcClient` |
| `com.chua.common.support.network.invoker.Invoker` | `zmq` | `com.chua.rpc.support.zmq.ZmqInvoker` |
| `com.chua.common.support.concurrent.dispatcher.DispatcherProvider` | `zmq` | `com.chua.rpc.support.zmq.ZmqDispatcherProvider` |

本模块**消费**的 SPI（由 classpath 上其他模块提供，可选）：

| SPI | 用途 |
| --- | --- |
| `ServiceDiscovery` | `RpcRegistryConfig.protocol` 为注册中心类型时，按 `/appName/serviceName` 注册与查询服务端地址 |
| `Serialization` | `DispatcherConfig.serializer` 指定消息体序列化器；RPC 侧由 `RpcSerialization` 按 `fory` → `jackson` → JDK 优先级选择 |

## 6. 构建与依赖

```text
utils-support-rpc-zmq-starter
├── com.chua:utils-support-common-starter   （契约与 SPI 基础设施）
└── org.zeromq:jeromq:0.6.0                 （${jeromq.version}，纯 Java ZeroMQ）
```

```bash
mvn -o -q compile -DskipTests      # 编译
mvn -o -q install -DskipTests      # 安装到本地仓库
```

> 模块仅依赖 common-starter 与 jeromq；无 Spring、无原生库。JDK 25 编译（`maven.compiler.source/target=25`）。

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| Dubbo / SOFA / JSON-RPC 协议实现 | `utils-support-rpc-dubbo-starter` / `utils-support-rpc-sofa-starter` / `utils-support-rpc-json-starter` |
| 注册中心本身（ZooKeeper、Nacos 等） | 对应注册中心 starter；本模块只经 `ServiceDiscovery` SPI 消费 |
| 序列化实现（Fory、Jackson 等） | common-starter / fory-starter；本模块经 SPI 使用 |
| RPC 监控 / 指标后端 | 本模块仅提供内存计数 `getMetrics()` 快照 |
| 已连接客户端列表 | `ZmqRpcServer.getConnections()` 恒返回空列表（JeroMQ 不暴露对端 identity 列表） |
| `@RpcService` 的 version / group / token 校验 | 契约定义在 common-starter；本模块源码未提供针对 zmq 请求的该校验逻辑（仅类注释提及） |
