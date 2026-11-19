# utils-support-rpc-dubbo-starter

> utils 体系 RPC 子系统的 **Apache Dubbo 协议实现模块**：把 Dubbo 3.x 适配为 `utils-support-common-starter` 定义的 `RpcServer` / `RpcClient` SPI 契约，提供服务暴露、远程代理获取、连接查询与调用指标采集。同类的 SOFA / JSON-RPC 实现位于 `utils-support-rpc-sofa-starter` / `utils-support-rpc-json-starter`，native 实现位于 common 模块。
>
> 版本：`4.0.0.51`（继承父模块 `utils-support-network-parent`）｜JDK：`25`｜包名：`com.chua.rpc.support.dubbo`｜Dubbo：`3.3.6`（根 pom `dubbo.version`）｜源码类均 `@since 4.0.0.42`

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
    <artifactId>utils-support-rpc-dubbo-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `RpcServer` / `RpcClient` 契约的 `dubbo` SPI 实现，适配 Apache Dubbo 3.x | 不是 Spring Boot starter：`pom.xml` 未声明任何 Spring 制品，无自动装配 |
| 服务端 | `DubboRpcServer`：注册/暴露服务、查询连接与服务数、输出调用指标 | 不提供 `@DubboService` / `@DubboReference` 注解编程模型 |
| 客户端 | `DubboRpcClient`：按接口获取远程代理，带引用缓存 | 不绑定具体注册中心：仅随带 multicast，其余注册中心需按 Dubbo 3.x 拆分模块自行引入 |
| 指标 | `DubboRpcMetricsFilter`（provider 侧拦截）+ `DubboRpcMetricsHolder` 计数 | 不含监控端点/上报后端，只提供 `RpcMetrics` 快照 |

### 1.3 类清单

| 类 | 可见性 | 职责 |
| --- | --- | --- |
| `DubboRpcServer` | public | `RpcServer` 实现（`@Spi("dubbo")`）：服务暴露、连接/指标查询、关闭释放 |
| `DubboRpcClient` | public | `RpcClient` 实现（`@Spi("dubbo")`）：按接口获取远程代理、关闭释放 |
| `DubboConfigs` | 包私有 | 同应用名共享 `ApplicationConfig` 的引用计数缓存；创建时统一关闭 QoS |
| `DubboRpcMetricsHolder` | public | 协议层调用指标计数器（总数/成功/失败/在途 + 方法级统计） |
| `DubboRpcMetricsFilter` | public | Dubbo `Filter`（`@Activate(group = PROVIDER)`），每次服务端调用前后更新指标 |

---

## 2. 核心 API 与速查表

### 2.1 常用入口速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建 Dubbo 服务端 | `RpcServer.createService("dubbo", registryConfigs, protocolConfig, appName)` | SPI 名 `dubbo` → `DubboRpcServer`；另有单注册中心重载 |
| 暴露一个服务 | `server.register("com.example.HelloService", impl)` | 内部构造 `ServiceConfig` 并 `export()`；返回 `this` 可链式 |
| 暴露对象实现的全部接口 | `server.register(impl)` | `RpcServer` 的 default 方法，自动扫描全部接口逐一注册 |
| 创建 Dubbo 客户端 | `RpcClient.createClient("dubbo", registryConfigs, consumerConfig, appName)` | SPI 名 `dubbo` → `DubboRpcClient` |
| 获取远程代理 | `client.get(HelloService.class)` | 按接口缓存 `ReferenceConfig`；失败抛 `IllegalStateException` |
| 查看当前连接 | `server.getConnections()` | `List<RpcConnectionInfo>`，来自 `DubboProtocol` 的 server/channel |
| 查看调用指标 | `server.getMetrics()` | `RpcMetrics`：调用计数 + 方法级统计 + 连接 + 服务数 |
| 释放资源 | `server.close()` / `client.close()` | `unexport()` / `destroy()`，并释放共享 `ApplicationConfig` |

### 2.2 DubboRpcServer

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| 构造 | `DubboRpcServer(List<RpcRegistryConfig>, RpcProtocolConfig, String name)` | 常经 `RpcServer.createService(...)` 调用；`name` 为空时经 `DubboConfigs.get` 抛 `IllegalArgumentException` |
| `register` | `RpcServer register(String name, Object bean)` | 以接口全限定名暴露服务，立即 `export()` |
| `afterPropertiesSet` | `void afterPropertiesSet()` | `InitializingAware` 生命周期方法，置启动状态 |
| `getProtocol` | `String getProtocol()` | 固定返回 `"dubbo"` |
| `getServiceCount` | `int getServiceCount()` | 优先取 `DubboProtocol` 已导出服务数；异常时回退本地 `ServiceConfig` 计数 |
| `getConnections` | `List<RpcConnectionInfo> getConnections()` | 遍历 `DubboProtocol` 各 `RemotingServer` 的 channel；state 为 `ACTIVE` / `CLOSED`（channel 未连接时 `DISCONNECTED`） |
| `getMetrics` | `RpcMetrics getMetrics()` | `DubboRpcMetricsHolder.snapshot("dubbo")` + 服务数 + 连接列表 |
| `close` | `void close()` | 逐个 `unexport()` 后清空，并 `DubboConfigs.release(appName)` 释放共享配置 |

**注册中心字段映射**（`RpcRegistryConfig` → Dubbo `RegistryConfig`，服务端消费）：`protocol`、`port`、`username`、`password`、`timeout`、`sessionTimeout→session`、`group`、`version`、`check`、`address`、`file`、`register`、`dynamic`、`parameters`。`weight`、`subscribe` 未被服务端消费。

**协议字段映射**（`RpcProtocolConfig` → Dubbo `ProtocolConfig`，全量消费）：`name`、`port`、`host`、`payload`、`buffer`、`threads`、`accepts`、`ioThreads→iothreads`、`alive`、`queues`、`serialization`、`codec`、`transporter`、`dispatcher`、`threadpool`、`heartbeat`、`ssl→sslEnabled`、`keepAlive`、`charset`、`coreThreads→corethreads`。注意两处源码行为：`maxThreads` 实际写入 `setThreads(...)`；`idleTimeout` 实际写入 `setAlive(...)`；`register` 仅在显式传 `false` 时才 `setRegister(false)`。

### 2.3 DubboRpcClient

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| 构造 | `DubboRpcClient(List<RpcRegistryConfig>, RpcConsumerConfig, String name)` | 常经 `RpcClient.createClient(...)` 调用；`name` 为空时抛 `IllegalArgumentException` |
| `get` | `<T> T get(Class<T> targetType)` | 按接口在 `ConcurrentHashMap` 中缓存 `ReferenceConfig` 并取出代理；超时/重试/负载均衡等从 `RpcConsumerConfig` 透传 |
| `close` | `void close()` | 逐个 `destroy()` 引用并清空缓存；`DubboConfigs.release(appName)` 释放共享配置 |

**注册中心字段映射**（客户端消费）：`address`、`protocol`、`port`、`username`、`password`、`timeout`、`sessionTimeout→session`、`file`、`check`、`register`、`subscribe`、`dynamic`、`group`、`version`、`parameters`。`weight` 未被消费。

**消费者字段消费**：Dubbo 实现仅消费 `RpcConsumerConfig` 的 `check`、`async`、`timeout`、`retries`、`loadBalance→loadbalance`、`connections`、`cluster`、`sticky`、`version`、`group`（同时作用于 `ConsumerConfig` 与每个 `ReferenceConfig`）。契约上的其余字段——`serialization`、`inline`、`connectTimeout`、`retryEnabled`、`retryDelay`、`accessLog`、`lazy`、`url`、`invokeType`、`interfaceClass`、`interfaceName`、`token`、`auto`——在本模块的 Dubbo 实现中未被消费。

### 2.4 DubboConfigs（包私有，内部机制）

- **为什么存在**：Dubbo 3.x STRICT 配置模式下，同一应用名重复注册不同的 `ApplicationConfig` 会抛 `IllegalStateException: Duplicate Configs found for ApplicationConfig`；服务端与客户端同进程运行时必须共享同一实例。
- `get(name)`：按应用名取/建共享实例，引用计数 +1；`name` 为 `null`/空串抛 `IllegalArgumentException`。
- `release(name)`：引用计数 -1，归零时移除缓存项（服务端/客户端 `close()` 时调用，避免静态缓存泄漏）。
- `clear()`：清空全部缓存（进程级静态缓存，供测试或极端场景强制释放）。
- 创建时固定 `setQosEnable(false)` 并追加参数 `qos.enable=false`，避免 QoS 端口冲突。

### 2.5 调用指标（DubboRpcMetricsFilter + DubboRpcMetricsHolder）

- `DubboRpcMetricsFilter` 通过 Dubbo SPI `@Activate(group = PROVIDER)` 在 provider 调用链自动生效；登记名 `dubboRpcMetricsFilter`。
- 方法键 = 接口全限定名 + `"."` + 方法名；调用前 `onStart(methodKey)`，正常返回 `onSuccess(methodKey, duration)`，`Result.hasException()` 或抛 `RuntimeException` 时 `onFailure(methodKey, duration, error)`，`finally` 中 `onComplete()`。
- `DubboRpcMetricsHolder` 公开方法：`onStart` / `onSuccess` / `onFailure` / `onComplete` / `snapshot(protocol)` / `reset()`。
- 统计维度：累计调用总数、成功数、失败数、当前在途数；方法级 `MethodStat`（total / success / failure / totalDuration / maxDuration / minDuration / lastDuration / lastTime / lastResult / lastError）。
- `snapshot("dubbo")` 生成 `RpcMetrics`，方法级统计按调用总数降序排列；`reset()` 重置全部计数（供测试或运维使用）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rpc-dubbo-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最小可运行示例

```java
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;

import java.util.Collections;

public class Demo {

    // 1. 服务接口与实现（接口类需在服务端与客户端两侧的 classpath 上）
    public interface HelloService {
        String sayHello(String name);
    }

    public static class HelloServiceImpl implements HelloService {
        @Override
        public String sayHello(String name) {
            return "hello, " + name;
        }
    }

    public static void main(String[] args) {
        // 2. 注册中心：multicast 无需外部服务，适合本机/局域网
        RpcRegistryConfig registry = new RpcRegistryConfig();
        registry.setProtocol("multicast");
        registry.setAddress("224.5.6.7:1234");

        // 3. 创建 Dubbo 服务端（SPI 名 "dubbo"），暴露服务
        RpcServer server = RpcServer.createService("dubbo",
                Collections.singletonList(registry),
                RpcProtocolConfig.auto("dubbo", 20880),
                "demo-app");
        server.register(HelloService.class.getName(), new HelloServiceImpl());
        server.afterPropertiesSet();

        // 4. 创建 Dubbo 客户端，获取远程代理并调用
        RpcClient client = RpcClient.createClient("dubbo",
                Collections.singletonList(registry),
                RpcConsumerConfig.auto(),
                "demo-app");
        HelloService hello = client.get(HelloService.class);
        System.out.println(hello.sayHello("world"));

        // 5. 释放
        client.close();
        server.close();
    }
}
```

要点：

- 协议名固定传 `"dubbo"`，SPI 据此命中 `DubboRpcServer` / `DubboRpcClient`。
- 服务端与客户端使用相同 `appName`（示例为 `demo-app`）时，`DubboConfigs` 会共享同一个 `ApplicationConfig`，避免 Dubbo 3.x STRICT 模式的重复配置报错。
- `register(bean)`（不传接口名）可一次暴露该对象实现的全部接口。
- 多注册中心用 `createService(name, List<RpcRegistryConfig>, protocolConfig, appName)` 重载。

---

## 4. 配置项

**无。** 依据：

- 本模块 5 个源码文件中没有任何 Spring `@ConfigurationProperties` / `@Value` / `Environment` 读取，也不读取 application.properties / yaml 键；`pom.xml` 亦未引入 Spring。
- 全部配置通过编程式配置对象传入：`RpcRegistryConfig`（注册中心）、`RpcProtocolConfig`（协议，record）、`RpcConsumerConfig`（消费者），字段映射见 2.2 / 2.3。
- 便捷工厂：`RpcProtocolConfig.auto(name, port)` 按 CPU 核数与 JVM 堆内存自动调优（host 固定 `0.0.0.0`、payload 8MB、threadpool `cached`、heartbeat 30000ms、charset `UTF-8`、keepAlive true、idleTimeout 30000ms，线程/队列/缓冲/连接数按核数与堆大小推导）；`RpcConsumerConfig.auto()` / `autoConfig()` 自动推导 timeout、connectTimeout、connections、retryDelay。
- 模块内唯一写死的行为：`DubboConfigs` 创建 `ApplicationConfig` 时关闭 QoS（`setQosEnable(false)` 且追加参数 `qos.enable=false`）。

---

## 5. SPI 扩展点

本模块作为**实现方**登记了三类 SPI：

| SPI 接口 | 登记文件 | key → 实现 |
| --- | --- | --- |
| `com.chua.common.support.network.rpc.RpcServer` | `META-INF/extensions/com.chua.common.support.network.rpc.RpcServer` | `dubbo` → `DubboRpcServer` |
| `com.chua.common.support.network.rpc.RpcClient` | `META-INF/extensions/com.chua.common.support.network.rpc.RpcClient` | `dubbo` → `DubboRpcClient` |
| `org.apache.dubbo.rpc.Filter`（Dubbo SPI） | `META-INF/dubbo/internal/org.apache.dubbo.rpc.Filter` | `dubboRpcMetricsFilter` → `DubboRpcMetricsFilter`（`@Activate(group = PROVIDER)`） |

**扩展开发**（新增一种 RPC 协议，参照 common 的 SPI 模式）：

1. 实现 `RpcServer` / `RpcClient`，实现类标注 `@Spi("名称")`。
2. 在 `src/main/resources/META-INF/extensions/<接口全限定名>` 登记一行 `名称=实现类全限定名`。
3. 调用方 `RpcServer.createService("名称", ...)` / `RpcClient.createClient("名称", ...)` 即可切换协议，无需改代码。

**GraalVM native-image**：`META-INF/native-image/com.chua/utils-support-rpc-dubbo-starter/` 内置 `reflect-config.json`（`DubboRpcClient` / `DubboRpcServer` 全构造器/方法/字段反射注册）与 `resource-config.json`（包含 `META-INF/extensions/.*`）。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译。

```bash
mvn -pl utils-support-network-parent/utils-support-rpc-dubbo-starter -am clean package
```

### 6.2 运行期依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` | RPC 契约（`RpcServer` / `RpcClient` / 三个配置对象 / `RpcMetrics` / `RpcConnectionInfo`）与 SPI 框架 |
| `org.apache.dubbo:dubbo` | `3.3.6`（`${dubbo.version}`） | Dubbo 核心 |
| `org.apache.dubbo:dubbo-registry-multicast` | `3.3.6` | 组播注册中心：无需外部服务，用于本机/局域网直连 |
| `com.google.code.gson:gson` | 由父 pom 管理 | dubbo-common 的 JsonUtils/状态上报使用 Gson 序列化，dubbo 自身未声明该依赖 |
| `com.alibaba:fastjson` | `2.0.66` | dubbo-common 的 FastJsonSerialization 启动即引用 `com.alibaba.fastjson.JSON`，缺此 jar 抛 `NoClassDefFoundError`（2.x 即 fastjson2、兼容旧 API；2026-10-30 由 2.0.65 升为 2.0.66） |

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Spring Boot 自动装配 | 不在本模块：无 spring-boot 依赖、无 `AutoConfiguration.imports`；“starter” 仅是 utils 体系对 SPI 实现模块的命名 |
| `@DubboService` / `@DubboReference` 注解模型 | 不在本模块：服务暴露与引用获取均通过 `RpcServer.register(...)` / `RpcClient.get(...)` 编程式完成 |
| zookeeper / nacos / redis 注册中心客户端 | 不随本模块提供：Dubbo 3.x 已将 registry 实现拆分为独立模块，本模块仅随带 `dubbo-registry-multicast` |
| 监控端点与指标上报 | 不在本模块：仅提供 `getMetrics()` / `snapshot(protocol)` 快照与 `reset()`；接入监控由使用方完成 |
| SOFA / JSON-RPC / native RPC | 分别在 `utils-support-rpc-sofa-starter`、`utils-support-rpc-json-starter` 与 `utils-support-common-starter`（native） |
| 直连 URL、同 JVM inline 直调、令牌鉴权、调用模式等消费者能力 | `RpcConsumerConfig` 契约上存在这些字段，但本模块的 Dubbo 实现未消费（见 2.3） |

---

## 文档索引

| 文档 | 路径 |
| --- | --- |
| RPC 契约层（SPI 接口与配置对象） | [../../utils-support-core-parent/utils-support-common-starter/README.md](../../utils-support-core-parent/utils-support-common-starter/README.md) |

---

## 许可证

Apache License 2.0
