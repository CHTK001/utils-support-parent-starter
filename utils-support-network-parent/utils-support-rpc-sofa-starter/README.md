# utils-support-rpc-sofa-starter

> utils 网络/RPC 体系的 **SOFA-RPC 协议实现模块**：把 `utils-support-common-starter` 定义的 `RpcServer` / `RpcClient` SPI 契约落到蚂蚁 SOFA-RPC（`sofa-rpc-all`）上，上层用统一 RPC 门面即可发布与调用 SOFA 服务。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.rpc.support.sofa`

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
    <artifactId>utils-support-rpc-sofa-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | `RpcServer` / `RpcClient` 的 SPI 实现（`@Spi("sofa")`），把统一 RPC 契约映射到 SOFA 的 `ProviderConfig` / `ConsumerConfig` | 不是 RPC 契约定义方（契约与配置模型在 `utils-support-common-starter`） |
| 能力 | 服务注册暴露（`export`）、远程代理获取（`refer`）、本地注册中心文件路径规划、服务数/连接/指标快照 | 不是注册中心本身（ZooKeeper / Nacos 等需自备），也不是服务治理控制台 |
| 装配 | 通过 `META-INF/extensions` 按名字 `"sofa"` 装配，随依赖引入即生效 | 无 Spring 自动配置类，不是传统 Spring Boot starter |
| 依赖 | 依赖 `utils-support-common-starter` 与 `sofa-rpc-all 5.14.3` | 不依赖任何 `spring-*` 业务 starter |

**模块内只有两个实现类**：

| 类 | SPI 名 | 说明 |
| --- | --- | --- |
| `SofaRpcServer` | `sofa` | SOFA-RPC 服务端：装配 `RegistryConfig` / `ServerConfig`，`register()` 时构建 `ProviderConfig` 并 `export()` |
| `SofaRpcClient` | `sofa` | SOFA-RPC 客户端：按接口缓存 `ConsumerConfig`，`get()` 时 `refer()` 出本地代理 |

---

## 2. 核心 API 与速查表

契约接口与配置模型均来自 `com.chua.common.support.network.rpc`（定义在 `utils-support-common-starter`）；本模块提供 `"sofa"` 协议实现。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建 SOFA 服务端 | `RpcServer.createService("sofa", registryConfig, protocolConfig, appName)` | 也支持多注册中心列表重载 |
| 暴露一个服务 | `server.register("com.example.HelloService", impl)` | 构建 `ProviderConfig` 并立即 `export()` |
| 暴露实现的全部接口 | `server.register(impl)` | default 方法，自动扫描实现的所有接口 |
| 创建 SOFA 客户端 | `RpcClient.createClient("sofa", registryConfig, consumerConfig, appName)` | 也支持多注册中心列表重载 |
| 获取远程代理并调用 | `client.get(HelloService.class)` | 返回动态代理，直接调用接口方法 |
| 协议参数自动调优 | `RpcProtocolConfig.auto("sofa", port)` | 按 CPU 核数与 JVM 堆内存生成 `ServerConfig` 参数 |
| 消费者参数自动调优 | `RpcConsumerConfig.auto()` | 自动计算超时、连接数、重试间隔等 |
| 查看运行状态 | `server.getMetrics()` / `server.getConnections()` / `server.getServiceCount()` | `RpcMonitor` 契约；SOFA 返回监听端点与已暴露服务数 |

补充：使用 `local` 注册中心时，可用系统属性 `-Dsofa.rpc.registry.file.dir=...` 指定本地注册文件目录（见 [4. 配置项](#4-配置项)）。

---

## 3. 快速开始

### 3.1 定义服务接口与实现（业务侧自备）

```java
public interface HelloService {
    String sayHello(String name);
}

public class HelloServiceImpl implements HelloService {
    @Override
    public String sayHello(String name) {
        return "hello, " + name;
    }
}
```

### 3.2 服务端：注册并暴露服务

```java
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;

RpcRegistryConfig registry = new RpcRegistryConfig();
registry.setProtocol("local");   // 本机开发用 local；生产可换 zookeeper / nacos

RpcServer server = RpcServer.createService("sofa", registry,
        RpcProtocolConfig.auto("sofa", 12200), "demo-app");

server.register("com.example.HelloService", new HelloServiceImpl());
server.afterPropertiesSet();     // InitializingAware 生命周期：标记就绪
```

### 3.3 客户端：获取代理并调用

```java
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;

RpcClient client = RpcClient.createClient("sofa", registry,
        RpcConsumerConfig.auto(), "demo-app");

HelloService hello = client.get(HelloService.class);
System.out.println(hello.sayHello("SOFA"));   // hello, SOFA

client.close();   // unRefer 全部 ConsumerConfig
server.close();   // unExport 全部 ProviderConfig
```

> **注意**：`local` 注册中心按应用名生成本地注册文件（`sofa-rpc-local-<appName>.data`）。服务端与客户端**必须使用同一个 `appName`**，否则本机跨进程也发现不了对方。

---

## 4. 配置项

本模块**没有** `application.yml` / `application.properties` 配置键（依据：模块内无 Spring 自动配置与 `@ConfigurationProperties`，全部配置经构造参数传入）。配置分三层：

### 4.1 注册中心：`RpcRegistryConfig`

| 字段 | 本实现是否消费 | 说明 |
| --- | --- | --- |
| `protocol` | 是 | 注册中心协议；`local` 表示 SOFA 本地文件注册中心，`zookeeper` / `nacos` / `redis` / `multicast` / `direct` 由 SOFA 侧支持 |
| `address` | 是 | 注册中心地址 |
| `timeout` | 是 | 连接超时（毫秒） |
| `username` / `password` / `port` / `sessionTimeout` / `group` / `version` / `check` / `dynamic` / `register` / `subscribe` / `weight` / `parameters` / `file` | 否 | 契约模型字段，SOFA 实现未消费 |

### 4.2 协议：`RpcProtocolConfig` → SOFA `ServerConfig`

| `RpcProtocolConfig` 字段 | 映射到 `ServerConfig` |
| --- | --- |
| `name` | `setProtocol(...)` |
| `host` | `setHost(...)` |
| `port` | `setPort(...)` |
| `threads` | `setCoreThreads(...)` |
| `maxThreads` | `setMaxThreads(...)` |
| `ioThreads` | `setIoThreads(...)` |
| `accepts` | `setAccepts(...)` |
| `queues` | `setQueues(...)` |
| `alive` | `setAliveTime(...)` |
| `idleTimeout` | `setAliveTime(...)` |
| `payload` | `setPayload(...)` |
| `keepAlive = true` | `setDaemon(false)` |

> 其余字段（`buffer`、`serialization`、`codec`、`transporter`、`dispatcher`、`threadpool`、`heartbeat`、`ssl`、`register`、`charset`、`coreThreads`）SOFA 实现未消费；不想逐项配置时直接用 `RpcProtocolConfig.auto("sofa", port)`。

### 4.3 消费者：`RpcConsumerConfig` → SOFA `ConsumerConfig`

| `RpcConsumerConfig` 字段 | 映射到 `ConsumerConfig` |
| --- | --- |
| `check` | `setCheck(...)` |
| `timeout` | `setTimeout(...)` |
| `retries` | `setRetries(...)` |
| `loadBalance` | `setLoadBalancer(...)` |
| `connections` | `setConnectionNum(...)` |
| `serialization` | `setSerialization(...)` |
| `cluster` | `setCluster(...)` |
| `async` | `true` → `setInvokeType("future")`，否则 `"sync"` |
| `version` | `setVersion(...)` |
| `group` | `setUniqueId(...)` |
| `connectTimeout` | `setConnectTimeout(...)` |
| `sticky` | `true` → `setSticky(true)` |

> 其余字段（`inline`、`token`、`url`、`lazy`、`accessLog`、`retryEnabled`、`retryDelay`、`interfaceClass`、`interfaceName`、`invokeType` 直填、`auto`）为契约层通用模型，SOFA 实现未逐一消费。

### 4.4 系统属性（仅 `local` 注册中心生效）

| 属性 | 说明 |
| --- | --- |
| `sofa.rpc.registry.file.dir` | 本地注册文件目录。优先级：该属性 → `~/.sofa-rpc`（用户目录）→ `java.io.tmpdir`。文件名为 `sofa-rpc-local-<appName>.data`；`appName` 为空时退化为 `default`。服务端与客户端按同一应用名计算同一路径才能互相发现 |

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，不是新扩展点的定义方。

### 5.1 实现的扩展点与注册文件

| 扩展点接口 | SPI 名 | 实现 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.network.rpc.RpcServer` | `sofa` | `com.chua.rpc.support.sofa.SofaRpcServer` | `META-INF/extensions/com.chua.common.support.network.rpc.RpcServer` |
| `com.chua.common.support.network.rpc.RpcClient` | `sofa` | `com.chua.rpc.support.sofa.SofaRpcClient` | `META-INF/extensions/com.chua.common.support.network.rpc.RpcClient` |

获取方式（等价于 `RpcServer.createService(...)` / `RpcClient.createClient(...)` 的底层形式）：

```java
RpcServer server = ServiceProvider.of(RpcServer.class)
        .getNewExtension("sofa", registryConfigs, protocolConfig, appName);
```

### 5.2 生命周期

| 阶段 | 服务端 `SofaRpcServer` | 客户端 `SofaRpcClient` |
| --- | --- | --- |
| 构造 | 装配 `ApplicationConfig`（appName）、`RegistryConfig` 列表、`ServerConfig` | 装配 `ApplicationConfig`、`RegistryConfig` 列表，持有 `RpcConsumerConfig` |
| 使用 | `register(name, bean)`：构建 `ProviderConfig` 并 `export()` | `get(Class)`：按接口缓存 `ConsumerConfig` 并 `refer()` |
| 就绪 | `afterPropertiesSet()`（`InitializingAware`） | — |
| 关闭 | `close()`：逐个 `unExport()` 并清空 | `close()`：逐个 `unRefer()` 并清空缓存 |

### 5.3 GraalVM Native Image

`src/main/resources/META-INF/native-image/com.chua/utils-support-rpc-sofa-starter/` 内置 `reflect-config.json`（对 `SofaRpcServer` / `SofaRpcClient` 的构造器、方法、字段全量开放）与 `resource-config.json`，native 编译无需额外配置。

---

## 6. 构建与依赖

### 6.1 构建

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -pl utils-support-network-parent/utils-support-rpc-sofa-starter -am -DskipTests package
```

### 6.2 依赖清单（来自 `pom.xml`）

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | `RpcServer` / `RpcClient` 契约、`Rpc*Config` 配置模型、SPI 与 `Wraps` 等工具 |
| `com.alipay.sofa:sofa-rpc-all` | `5.14.3` | SOFA-RPC 全量协议栈（`ProviderConfig` / `ConsumerConfig` / `RegistryConfig` / `ServerConfig`） |
| `org.msgpack:msgpack` | `0.6.12` | `sofa-rpc-all` 内嵌 msgpack codec（`sofa-rpc-codec-msgpack`）在 `SerializerFactory` 启动时即需旧版 `org.msgpack.MessagePack` API，缺此依赖抛 `NoClassDefFoundError` |
| `org.slf4j:slf4j-api` | 由父 POM 管理 | 日志门面 |
| `ch.qos.logback:logback-classic` | `1.2.13` | `sofa-rpc` 内嵌 logback space logger（`com.alipay.sofa.rpc.log.logback`）依赖 logback 1.2.x 的 `ContextInitializer.configureByResource(URL)` API（1.3+ 已移除）；显式对齐避免上层解析为 slf4j 2.0 + logback 1.5 后 `NoSuchMethodError`。2026-10-30 由 1.2.12 升为 1.2.13（1.2.x 最终修复版，API 约束不变） |

> **版本对齐提醒**：宿主项目若引入 slf4j 2.x / logback 1.5.x，需注意与上述约束冲突；SOFA 侧要求 slf4j 1.7.x + logback 1.2.x。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `RpcServer` / `RpcClient` / `RpcRegistryConfig` / `RpcProtocolConfig` / `RpcConsumerConfig` / `RpcMetrics` / `RpcConnectionInfo` 等契约与模型 | `utils-support-common-starter`（`com.chua.common.support.network.rpc`） |
| 其它 RPC 协议实现：`dubbo` / `json` / `zmq` | 同仓 `utils-support-rpc-dubbo-starter` / `utils-support-rpc-json-starter` / `utils-support-rpc-zmq-starter` |
| 自研 native RPC 实现 | `utils-support-common-starter`（`NativeRpcServer` / `NativeRpcClient`） |
| 注册中心服务器（ZooKeeper / Nacos / Redis 等） | 需自行部署；本模块只做客户端接入 |
| Spring 自动装配、`@RpcService` 注解扫描等宿主集成 | 本模块不含；`RpcService` / `RpcServiceScanner` 等位于 common-starter 契约层，宿主集成由上层模块实现 |
| 服务治理能力（`token` 令牌校验、`inline` 同 JVM 直调、`url` 直连等 `RpcConsumerConfig` 高级字段） | 契约层已定义，但 SOFA 实现未消费；需要时请选用对应支持的协议实现 |
