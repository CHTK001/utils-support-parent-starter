# utils-support-rpc-json-starter

> 体系内 **JSON-RPC 2.0 协议的 RPC 实现 starter**：基于 JDK 内建 `com.sun.net.httpserver.HttpServer` + jsonrpc4j 提供 `RpcServer` / `RpcClient` 的 `json` SPI 实现，HTTP POST 直连、多服务路由、请求头服务治理、请求指标统计。
>
> 版本：`4.0.0.51`（`JsonRpcServer` / `JsonRpcClient` 自 `4.0.0.42` 起）｜JDK：`25`｜包名：`com.chua.rpc.support.json`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 线上协议约定（请求体与请求头）](#4-线上协议约定请求体与请求头)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `com.chua.common.support.network.rpc.RpcServer` / `RpcClient` 的 **JSON-RPC 2.0 over HTTP** SPI 实现（SPI 名均为 `json`） | 不是 RPC 抽象层本身——接口与配置模型都在 `utils-support-common-starter` |
| 传输 | 服务端用 JDK 内建 `com.sun.net.httpserver.HttpServer` 监听 HTTP POST；客户端用 jsonrpc4j 的 `JsonRpcHttpClient` 发 HTTP POST，两者天然互通 | 不是 TCP 长连接 RPC，没有心跳与持久连接 |
| 路由 | 请求体携带 `service` 字段（接口全限定名）做多服务路由；仅注册一个服务时兼容「裸方法名」调用 | 不做注册中心寻址——客户端直连第一个配置地址 |
| 治理 | 读取 `@RpcService` 的 `version` / `group` / `token`，与请求头 `X-RPC-Version` / `X-RPC-Group` / `X-RPC-Token` 比对，不匹配拒绝（HTTP 403） | 不做认证体系、鉴权框架，只有这三种请求头校验 |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `com.github.briandilley.jsonrpc4j:jsonrpc4j:1.7` | 不依赖 Spring / Spring Boot，无自动装配 |

---

## 2. 核心 API 与速查表

模块只有两个实现类（`com.chua.rpc.support.json` 包）：

| 类 | SPI 名 | 实现接口 | 职责 |
| --- | --- | --- | --- |
| `JsonRpcServer` | `json` | `RpcServer`（含 `RpcMonitor`、`InitializingAware`、`AutoCloseable`） | 启动 HTTP 服务端，注册服务、路由、治理校验、指标统计 |
| `JsonRpcClient` | `json` | `RpcClient`（含 `AutoCloseable`） | 为目标接口生成动态代理，发起 JSON-RPC 调用并按配置重试 |

最常来查的入口：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 起一个 JSON-RPC 服务端 | `RpcServer.createService("json", registryConfig, protocolConfig, appName)` | 返回 `JsonRpcServer`；随后必须调 `afterPropertiesSet()` 才开始监听 |
| 暴露一个服务 | `server.register(接口全限定名, 实现对象)` | 每个服务名对应一个独立 jsonrpc4j 处理器；也有按 Bean 全部接口批量注册的 `register(Object bean)` |
| 创建客户端 | `RpcClient.createClient("json", registryConfig, consumerConfig, appName)` | 返回 `JsonRpcClient`，地址取 `registryConfig` 列表的第一个 |
| 拿远程代理 | `client.get(目标接口.class)` | 返回该接口的动态代理，方法调用即远程调用 |
| 多服务路由 | 请求体 JSON 中的 `"service"` 字段 = 接口全限定名 | 未带 `service` 且只注册了一个服务时，按该唯一服务处理 |
| 服务治理 | 实现类标 `@RpcService(version=…, group=…, token=…)`；客户端经 `RpcConsumerConfig` 的同名 setter 携带请求头 | 任一字段在服务端配置了而请求不匹配 → HTTP 403 |
| 看运行状态 | `server.getMetrics()` / `server.getConnections()` / `server.getServiceCount()` / `server.getProtocol()` | 指标含总请求数、成功/失败/在途计数与来源地址集合 |
| 区分失败类型 | 捕获 `RpcException` 后调 `isBusiness()` / `isTransport()` | 业务异常不重试；传输异常按消费者配置重试 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rpc-json-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例（服务端 + 客户端同进程演示）

```java
import com.chua.common.support.network.rpc.*;
import com.chua.common.support.function.InitializingAware;

public class JsonRpcDemo {

    // ① 服务接口（用户侧代码，任意接口均可）
    public interface UserService {
        String getUser(String id);
    }

    // ② 服务实现；需要治理时才加 @RpcService，不加则全部放行（向后兼容）
    @RpcService(version = "1.0.0")
    public static class UserServiceImpl implements UserService {
        @Override
        public String getUser(String id) {
            return "user-" + id;
        }
    }

    public static void main(String[] args) {
        // ③ 服务端：创建 → 注册 → 启动
        RpcServer server = RpcServer.createService("json",
                new RpcRegistryConfig(),                 // json 服务端不消费注册中心配置
                RpcProtocolConfig.auto("json", 8080),    // json 实际只消费 port / threads
                "demo");
        server.register(UserService.class.getName(), new UserServiceImpl());
        ((InitializingAware) server).afterPropertiesSet();   // 启动 HTTP 监听（幂等）

        // ④ 客户端：直连服务端地址，拿代理
        RpcRegistryConfig target = new RpcRegistryConfig();
        target.setAddress("http://127.0.0.1:8080");
        RpcConsumerConfig consumer = new RpcConsumerConfig();
        consumer.setVersion("1.0.0");                        // 与 @RpcService 对应，作为 X-RPC-Version 头发送

        try (RpcClient client = RpcClient.createClient("json", target, consumer, "demo")) {
            UserService userService = client.get(UserService.class);
            System.out.println(userService.getUser("1"));    // 输出 user-1
        }

        // ⑤ 关闭服务端（停 HTTP、关线程池、清空注册表）
        try {
            server.close();
        } catch (Exception e) {
            // AutoCloseable 声明的受检异常，按需处理
        }
    }
}
```

### 3.3 重试与超时（客户端可选）

```java
RpcConsumerConfig consumer = new RpcConsumerConfig();
consumer.setConnectTimeout(3000);   // 连接超时（毫秒），不设时默认 3000
consumer.setTimeout(10000);         // 读超时（毫秒），不设时默认 10000
consumer.setRetryEnabled(true);     // 显式开启重试
consumer.setRetries(2);             // 最多额外重试 2 次
consumer.setRetryDelay(200);        // 重试间隔（毫秒），不设时默认 100
```

重试语义（`JsonRpcClient.RpcInvoker`）：

- **业务异常不重试**：若异常因果链中不含 `java.io.IOException` 且本身不是 `RpcException`，判定为服务端业务失败，立即以 `RpcException.business(...)` 抛出——避免放大非幂等接口的副作用。
- **传输异常可重试**：连接失败、超时、HTTP 非 2xx、响应解析失败（均以 `IOException` 形态出现）按 `retries` 次数重试，每次间隔 `retryDelay`；重试耗尽后以 `RpcException.transport(...)` 抛出。

---

## 4. 线上协议约定（请求体与请求头）

`JsonRpcServer` 与 `JsonRpcClient` 之间的约定（两类的包内常量一致）：

| 项 | 值 | 说明 |
| --- | --- | --- |
| 传输 | HTTP POST，`Content-Type: application/json; charset=utf-8` | 非 POST → HTTP 405 |
| 路由字段 | 请求体 JSON 中的 `"service"` = 目标接口全限定名 | 服务端按它选择对应的 jsonrpc4j 处理器；找不到 → HTTP 404 |
| 裸方法名兼容 | 请求体不含 `service` 且服务端恰好只注册了 1 个服务 | 按该唯一服务处理；注册了多个则无法唯一确定 → HTTP 404 |
| 版本头 | `X-RPC-Version` | 与 `@RpcService#version` 比对 |
| 分组头 | `X-RPC-Group` | 与 `@RpcService#group` 比对 |
| 令牌头 | `X-RPC-Token` | 与 `@RpcService#token` 比对 |
| 治理失败 | HTTP 403 | 仅当服务端对应字段非空且与请求头不一致时拒绝；未配置则放行 |
| 空请求体 | HTTP 400 | — |
| 处理异常 | HTTP 500 | jsonrpc4j 处理器抛错时 |
| 成功 | HTTP 200，响应体为 JSON-RPC 2.0 响应 | — |

服务端行为补充：

- 监听地址为 `new InetSocketAddress(port)`（所有网卡的通配地址），线程池为固定 `threads` 个守护线程（线程名前缀 `json-rpc-http`），等待队列容量 1024，队列满时由调用线程执行（`CallerRunsPolicy`）。
- `afterPropertiesSet()` 幂等（原子状态位防重复启动）；`close()` 停止 HTTP 服务、关闭线程池并清空服务注册表。
- `getMetrics()` 返回的 `RpcMetrics` 含：启动时间、服务数、总调用数、成功数、失败数、在途数、连接总数与来源地址列表（HTTP 短连接场景下用请求来源 IP 近似连接信息）。

---

## 5. 配置项

本模块**不是 Spring Boot starter**：源码中没有自动装配类，也没有 `application.yml` 配置前缀（源码未提供）。全部配置通过构造参数传入的三个配置对象承载，json 实现实际消费的键如下。

### 5.1 服务端 `JsonRpcServer`（消费 `RpcProtocolConfig`）

| 配置项 | 来源 | 默认值 |
| --- | --- | --- |
| 监听端口 | `RpcProtocolConfig.port()` | `8080` |
| HTTP 工作线程数 | `RpcProtocolConfig.threads()` | `8` |

- `RpcProtocolConfig` 其余字段（host、payload、serialization、ssl 等）在 json 实现中**不消费**。
- `RpcRegistryConfig` 列表与应用名仅用于满足 SPI 构造契约，json 服务端不使用。

### 5.2 客户端 `JsonRpcClient`（消费 `RpcRegistryConfig` + `RpcConsumerConfig`）

| 配置项 | 来源 | 默认值 |
| --- | --- | --- |
| 目标地址 | `RpcRegistryConfig.address`（取列表第一个；未以 `http` 开头时自动补 `http://`） | `http://localhost:8080` |
| 连接超时（毫秒） | `RpcConsumerConfig.connectTimeout` | `3000` |
| 读超时（毫秒） | `RpcConsumerConfig.timeout` | `10000` |
| 重试开关 | `RpcConsumerConfig.retryEnabled` | 不设置时按 `retries` 处理；显式设为 `false` 则彻底关闭重试 |
| 重试次数 | `RpcConsumerConfig.retries` | `0`（不重试） |
| 重试间隔（毫秒） | `RpcConsumerConfig.retryDelay` | `100` |
| 版本 / 分组 / 令牌 | `RpcConsumerConfig.version` / `group` / `token` → 请求头 `X-RPC-Version` / `X-RPC-Group` / `X-RPC-Token` | 不设置则不发送对应请求头 |

> `RpcConsumerConfig` 与 `RpcProtocolConfig` 各自提供 `auto()` / `autoConfig()` 按 CPU 与堆内存自动调优；json 实现只读取上表列出的字段，其余调优项对本协议无效。

---

## 6. SPI 扩展点

- 本模块是 **`json` 协议的 SPI 提供方**，不是可扩展载体：两个实现类均标注 `@Spi("json")`，并通过 `META-INF/extensions/` 登记：

  ```text
  src/main/resources/META-INF/extensions/com.chua.common.support.network.rpc.RpcServer
  json=com.chua.rpc.support.json.JsonRpcServer

  src/main/resources/META-INF/extensions/com.chua.common.support.network.rpc.RpcClient
  json=com.chua.rpc.support.json.JsonRpcClient
  ```

- 使用方通过 `RpcServer.createService("json", …)` / `RpcClient.createClient("json", …)`（内部走 `ServiceProvider` 的 `getNewExtension`，构造参数透传）拿到本模块实现。
- 想换协议时无需改业务代码：同仓还有 `utils-support-rpc-dubbo-starter`、`utils-support-rpc-sofa-starter`、`utils-support-rpc-zmq-starter` 等同名 SPI 实现，把 `"json"` 换成对应协议名即可。
- GraalVM native-image：`src/main/resources/META-INF/native-image/com.chua/utils-support-rpc-json-starter/` 内置 `reflect-config.json`（登记 `JsonRpcServer` / `JsonRpcClient` 的全部构造器、方法、字段）与 `resource-config.json`。

---

## 7. 构建与依赖

Maven 坐标与依赖（见本模块 `pom.xml`）：

```xml
<parent>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-network-parent</artifactId>
    <version>4.0.0.51</version>
</parent>
<artifactId>utils-support-rpc-json-starter</artifactId>
<name>Utils Support RPC JSON Starter</name>
<description>JSON-RPC 实现模块</description>

<dependencies>
    <dependency>
        <groupId>com.chua</groupId>
        <artifactId>utils-support-common-starter</artifactId>  <!-- RPC SPI 契约、SPI 框架、代理工具 -->
    </dependency>
    <dependency>
        <groupId>com.github.briandilley.jsonrpc4j</groupId>
        <artifactId>jsonrpc4j</artifactId>                     <!-- JSON-RPC 2.0 编解码与 HTTP 客户端 -->
        <version>1.7</version>
    </dependency>
</dependencies>
```

- 仓库位置：`utils-support-parent-starter/utils-support-network-parent/utils-support-rpc-json-starter`。
- 编译随父工程进行：`mvn -o -q compile -DskipTests`（JDK 25）。
- 依赖方向：本模块 → `utils-support-common-starter`；**不依赖任何 spring-\* 模块**。

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 / 说明 |
| --- | --- |
| `RpcServer` / `RpcClient` 接口、`RpcProtocolConfig` / `RpcRegistryConfig` / `RpcConsumerConfig`、`@RpcService`、`RpcException`、`RpcMetrics` 等契约 | `utils-support-common-starter`（`com.chua.common.support.network.rpc`） |
| Dubbo / SOFA / ZeroMQ 协议实现 | 同仓 `utils-support-rpc-dubbo-starter` / `utils-support-rpc-sofa-starter` / `utils-support-rpc-zmq-starter` |
| 注册中心集成（ZooKeeper / Nacos / Redis 等） | 本模块不做：服务端不消费注册中心配置，客户端只直连第一个地址。相关配置字段在 common-starter 的 `RpcRegistryConfig` 中，由其它协议实现消费 |
| 负载均衡、集群容错、粘滞连接等 `RpcConsumerConfig` 高级项 | 本模块不消费 `loadBalance` / `cluster` / `sticky` / `connections` 等字段；json 客户端是单地址直连 |
| SSL/TLS | 本模块不消费 `RpcProtocolConfig.ssl()`；服务端为 JDK `HttpServer` 明文 HTTP |
| 同 JVM 直调（Inline）、降级到本地 Bean | `RpcConsumerConfig.inline` 与 `@RpcService#degrade` 的语义由其它实现/上层处理，json 实现不读取这些字段 |
| 服务注解的 `delay` / `retry` / `async` / `timeout` / `cache` 属性 | `@RpcService` 中定义，但 `JsonRpcServer` 只校验 `version` / `group` / `token` 三个字段 |
| Spring Boot 自动装配、配置前缀 | 源码未提供；本模块是纯 SPI 实现模块 |
