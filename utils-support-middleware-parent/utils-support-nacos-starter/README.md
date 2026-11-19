# utils-support-nacos-starter

> Nacos **服务发现 + 配置中心 + 管理面只读查询**三合一集成模块：既提供封装 `ConfigService` / `NamingService` 的链式全功能客户端 `NacosClient`，也提供 `ConfigCenter`、`ServiceDiscovery`、`MiddlewareClientProvider` 三个 SPI 的 `nacos` 实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 preview）｜包名：`com.chua.nacos.support`（子包 `client` / `config` / `discovery` / `spi`）

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
    <artifactId>utils-support-nacos-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 客户端 | `NacosClient`：链式封装 Nacos `ConfigService` + `NamingService`，配置读写/监听 + 服务注册/发现/订阅一步到位 | 不是 Spring Cloud Alibaba 封装，没有 `@NacosValue` 之类的注解体系 |
| 配置中心 | `NacosConfigCenter`：`ConfigCenter` SPI 的 `nacos` 实现，支持 YAML / properties 自动识别、键值发布、变更监听 | 不做本地配置文件缓存持久化，也不替代 Spring 的 `Environment` 抽象 |
| 服务发现 | `NacosServiceDiscovery`：`ServiceDiscovery` SPI 的 `nacos` 实现，注册 / 注销 / 更新 / 订阅 | 不含负载均衡策略、不含 Feign/Dubbo 集成 |
| 管理面 | `NacosClientProvider`：`MiddlewareClientProvider` SPI 的 `nacos` 实现，走 Nacos OpenAPI 做**只读**查询（命名空间 / 配置 / 服务 / 实例） | 不提供发布配置 / 删除配置 / 注册实例等写操作（源码注释明示“只读”） |
| 依赖 | 依赖 `nacos-client`、`snakeyaml` 与本仓 `utils-support-common-starter`、`utils-support-datasource-starter` | 不依赖 Spring，不依赖任何上层业务 starter |

**设计口径**：运行时能力（配置、注册、发现、订阅）走官方 `nacos-client` SDK；管理面（`NacosClientProvider`）刻意**只走 HTTP OpenAPI**（`/nacos/v1/console/**`、`/nacos/v1/cs/**`、`/nacos/v1/ns/**`），避免把整套注册中心客户端依赖拖进每个使用方（见该类 Javadoc）。

---

## 2. 核心 API 与速查表

### 2.1 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 配置 + 服务一体化操作 | `NacosClient.builder()...build()` | 链式客户端，`config()` / `naming()` 两个门面 |
| 读 / 写 / 删 / 监听配置 | `client.config().dataId("app.yaml").get()` / `.put(k, v)` / `.remove()` / `.onChange(...)` | `group` 默认 `DEFAULT_GROUP` |
| 注册 / 注销服务实例 | `client.naming().serviceName("svc").ip(...).port(...).register()` / `.deregister()` | 支持权重、临时实例、metadata |
| 发现 / 择优实例 | `client.naming().serviceName("svc").selectInstances()` / `.selectOne()` | `selectOne()` 随机取一个健康实例 |
| 配置中心 SPI | `ConfigCenter` 的 `nacos` 实现：`NacosConfigCenter` | YAML / properties 自动识别，键值发布转 YAML 存储 |
| 服务发现 SPI | `ServiceDiscovery` 的 `nacos` 实现：`NacosServiceDiscovery` | 注册 / 注销 / 更新 / 订阅（ONLINE / OFFLINE 事件） |
| 管理面只读 SPI | `MiddlewareClientProvider` 的 `nacos` 实现：`NacosClientProvider` | HTTP OpenAPI，`order=200` |
| 管理面查询表达式 | `query(request, expr, limit)`：`namespaces` / `services` / `instances <service>` / `configs <dataId>` | 只读，单次最多返回 200 行 |

### 2.2 NacosClient（`com.chua.nacos.support.client`）

链式全功能客户端，`implements AutoCloseable`，`@Getter` 暴露各字段只读访问器（含底层 `ConfigService` / `NamingService`）。

```java
NacosClient client = NacosClient.builder()
        .serverAddr("127.0.0.1:8848")   // 默认 127.0.0.1:8848
        .namespace("dev")               // 可选
        .username("nacos")              // 可选
        .password("nacos")              // 可选
        .timeout(30_000)                // 长轮询超时 ms，默认 30000
        .build();

client.start();        // 创建 ConfigService + NamingService，返回 this；抛 NacosException
client.shutdown();     // 移除配置监听器并关闭两个服务，返回 this
client.close();        // 等价于 shutdown()（AutoCloseable）

// 也可用静态工厂
NacosClient c1 = NacosClient.create("127.0.0.1:8848");
NacosClient c2 = NacosClient.create("127.0.0.1:8848", "dev");
```

**配置操作 `client.config()` → `ConfigOperation`**：

| 方法 | 说明 |
| --- | --- |
| `dataId(String)` / `group(String)` | 指定 dataId 与分组，`group` 默认 `DEFAULT_GROUP` |
| `timeout(long ms)` | 单次读取超时，默认 5000ms |
| `get()` | 读取配置原文，失败抛 `NacosClientException` |
| `getOrDefault(String)` | 读取配置，不存在返回默认值 |
| `getProperty(String)` / `getProperty(String, String)` | 按键读取 properties 格式配置 |
| `publish(String content)` | 发布配置原文 |
| `put(String key, String value)` | 读取现有配置合并键值后发布（properties 格式） |
| `remove()` | 删除该 dataId |
| `onChange(Consumer<String>)` | 注册配置变更监听，回调收到新配置原文 |

**服务操作 `client.naming()` → `NamingOperation`**：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `serviceName(String)` | — | 服务名（必填） |
| `ip(String)` / `port(int)` | `127.0.0.1` / `8080` | 实例地址 |
| `weight(double)` | `1.0` | 权重 |
| `healthy(boolean)` | `true` | 初始健康状态 |
| `ephemeral(boolean)` | `true` | 是否临时实例 |
| `metadata(Map)` / `metadata(k, v)` | 空 | 元数据 |
| `register()` / `deregister()` | — | 注册 / 注销当前实例 |
| `selectInstances()` | — | 健康实例列表 |
| `selectAllInstances()` | — | 全部实例（含不健康） |
| `selectOne()` | — | 随机取一个健康实例 |
| `subscribe(Consumer<List<Instance>>)` | — | 订阅实例变更，回调拿到最新健康实例列表 |

失败统一抛内部异常类 `NacosClient.NacosClientException`（`RuntimeException` 子类）。

### 2.3 NacosConfigCenter（`com.chua.nacos.support.config`）

`ConfigCenter` SPI 的 `nacos` 实现（`@Spi("nacos")`），继承 common-starter 的 `AbstractConfigCenter`，构造参数为 `ConfigCenterSetting`。

```java
NacosConfigCenter center = new NacosConfigCenter(setting);
center.start();                                        // 建立 ConfigService
Map<String, Object> cfg = center.get("app.yaml");      // 展平为键值 Map，空配置返回空 Map
Map<String, Object> cfg2 = center.get("app.yaml", "MY_GROUP");
center.publish("app.yaml", "DEFAULT_GROUP", "timeout", "3000");  // 合并后以 YAML 存储
center.remove("app.yaml", "timeout");                 // 仅作用 DEFAULT_GROUP，删除后重新发布剩余键
center.addListener("app.yaml", listener);              // 变更时逐键通知 update / delete
center.close();
```

行为要点（均来自源码）：

- **未 `start()` 就调用 `get` / `publish` / `remove` 会抛 `IllegalStateException`**。
- 解析规则：dataId 以 `.yaml` / `.yml` 结尾，或内容“含 `:` 且含换行/双空格”按 YAML 解析（snakeyaml + `MapUtils.flattenMap`）；dataId 以 `.properties` 结尾，或内容“含 `=` 且含换行”按 properties 解析（`PropertiesConfigParser`）；其余按纯文本，返回单键 `value`。
- 发布时把键值 Map 通过 `MapUtils.unflattenFromProperties` 还原为嵌套结构，再以 **YAML** 写回 Nacos。
- `addListener` 向 Nacos 注册监听后，新旧内容逐键比对：值变化触发 update、被删除的键触发 delete。
- 地址支持协议前缀：`grpc://host:port` 会额外设置 `nacos.remote.client.rpc.tls.enable=true`。

### 2.4 NacosServiceDiscovery（`com.chua.nacos.support.discovery`）

`ServiceDiscovery` SPI 的 `nacos` 实现（`@Spi("nacos")`），继承 common-starter 的 `AbstractServiceDiscovery`。

```java
NacosServiceDiscovery discovery = new NacosServiceDiscovery(option);        // 或 (option, clusterName)
discovery.start();                                                          // clusterName 作为 namespace
Discovery svc = Discovery.builder()
        .host("127.0.0.1").port(8080).protocol("http").serverId("n1").build();
discovery.registerService("/my-service", svc);                              // 注册，metadata 写入 protocol/serverId/uriSpec
discovery.subscribe("my-service", listener);                                // 实例上下线回调 ONLINE / OFFLINE
discovery.close();                                                          // 清缓存并关闭 NamingService
```

行为要点：

- `isSupportSubscribe()` 返回 `true`；订阅基于 Nacos `NamingEvent`，对每个实例按 `isHealthy()` 回调 `Event.ONLINE` / `Event.OFFLINE`。
- 查询实例时把 Nacos `Instance` 转成 common-starter 的 `Discovery`（host / port / weight / metadata，并从 metadata 取 `protocol`、`uriSpec`、`serverId`）。
- 指定了 `clusterName` 时用 `getAllInstances(clusterName, name)`，否则 `getAllInstances(name)`；`clusterName` 在 `start()` 里映射为 Nacos 的 namespace。
- `needsPathPrefixIsolation()` 返回 `false`；更新实例 = 先注销旧实例再注册新实例。

### 2.5 NacosClientProvider（`com.chua.nacos.support.spi`）

`MiddlewareClientProvider` SPI 的 `nacos` 实现（`@Spi(value = "nacos", order = 200)`），**管理面只读**，基于 JDK `HttpClient` 调 Nacos OpenAPI（连接超时 / 请求超时 8s）。

| 方法 | 行为 |
| --- | --- |
| `name()` / `displayName()` | `nacos` / `Nacos 注册配置中心` |
| `defaultPort()` | `8848` |
| `capability()` | 声明支持命名空间、配置、服务、实例四类只读能力 |
| `test(request)` | `GET /nacos/v1/console/health/readiness`，HTTP ≥ 400 判失败，成功返回“Nacos 控制台可达” |
| `listTopNodes(request)` | 命名空间列表（`/nacos/v1/console/namespaces`），空 id 显示为 `public` |
| `listChildren(request, parentKey)` | 指定命名空间下的配置列表（`/nacos/v1/console/configurations`，`pageSize=200`） |
| `query(request, expr, limit)` | 见下方查询表达式 |
| `document(request, scope, key)` | `scope=config`：`key = namespaceId|dataId|group`，`show=all` 取配置原文；`scope=service`：返回实例清单文本 |

查询表达式（不区分大小写，均只读）：

| 表达式 | 返回列 | 说明 |
| --- | --- | --- |
| `namespaces` | `namespace`、`id` | 命名空间列表 |
| `services` / `services <ns>` | `service`、`clusters` | 服务列表，`ignoreEmptyService=true`，最多 200 |
| `instances <service>` | `service`、`ip`、`port`、`healthy` | 实例列表，`healthyOnly=false` |
| `configs <dataId>` | `dataId`、`content` | 配置内容，`group=DEFAULT_GROUP`、`show=all` |

鉴权：`MiddlewareConnectRequest.password()` 非空时作为 `accessToken` 查询参数附加；地址按 `http://<address>` 拼接。

---

## 3. 快速开始

最短可运行示例（`NacosClient` 一条链路覆盖配置与服务）：

```java
import com.chua.nacos.support.client.NacosClient;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.List;

public class NacosDemo {
    public static void main(String[] args) throws Exception {
        try (NacosClient client = NacosClient.builder()
                .serverAddr("127.0.0.1:8848")
                .namespace("dev")
                .build()) {

            client.start();

            // 1) 配置：读取 / 写入键值 / 监听变更
            String config = client.config().dataId("app.yaml").group("DEFAULT_GROUP").get();
            client.config().dataId("app.yaml").put("timeout", "3000");
            client.config().dataId("app.yaml").onChange(c -> System.out.println("配置变更: " + c));

            // 2) 服务：注册实例 / 发现实例 / 择优
            client.naming().serviceName("my-service")
                    .ip("127.0.0.1").port(8080)
                    .metadata("version", "1.0")
                    .register();

            List<Instance> instances = client.naming().serviceName("my-service").selectInstances();
            Instance one = client.naming().serviceName("my-service").selectOne();
        }   // close() 自动注销监听并关闭 ConfigService / NamingService
    }
}
```

走 SPI 装配时（三个 SPI 名称均为 `nacos`）：

```java
// 配置中心：构造参数为 common-starter 的 ConfigCenterSetting
ConfigCenter center = ServiceProvider.of(ConfigCenter.class).getNewExtension("nacos", setting);
center.start();
Map<String, Object> cfg = center.get("app.yaml");
center.publish("app.yaml", "DEFAULT_GROUP", "timeout", "3000");

// 服务发现：构造参数为 common-starter 的 DiscoveryOption
ServiceDiscovery discovery = ServiceProvider.of(ServiceDiscovery.class).getExtension("nacos");
discovery.start();
discovery.registerService("/my-service",
        Discovery.builder().host("127.0.0.1").port(8080).protocol("http").serverId("n1").build());
```

---

## 4. 配置项

**本模块没有 Spring 自动装配、没有 `application.yml`、没有 `spring.factories` / `AutoConfiguration.imports`**（资源目录仅含 `META-INF/extensions/` 与 `META-INF/native-image/`）。所有参数通过构造器 / Builder / SPI 装配传入：

| 参数 | 载体 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `serverAddr` | `NacosClient.Builder` | `127.0.0.1:8848` | Nacos 服务端地址 |
| `namespace` | `NacosClient.Builder` | 无（不设置该属性） | 命名空间隔离 |
| `username` / `password` | `NacosClient.Builder` | 无 | 用户名 / 密码认证 |
| `timeout` | `NacosClient.Builder` | `30000`ms | 写入 `CONFIG_LONG_POLL_TIMEOUT` |
| `dataId` / `group` | `NacosClient.config()` | — / `DEFAULT_GROUP` | 配置定位 |
| 读取超时 | `NacosClient.config()` | `5000`ms | 单次 `getConfig` 超时 |
| `ip` / `port` | `NacosClient.naming()` | `127.0.0.1` / `8080` | 实例地址 |
| `weight` / `healthy` / `ephemeral` | `NacosClient.naming()` | `1.0` / `true` / `true` | 实例属性 |
| `address` | `ConfigCenterSetting`（`NacosConfigCenter`） | — | 服务端地址，支持 `grpc://` 前缀（开启 `nacos.remote.client.rpc.tls.enable=true`） |
| `profile` | `ConfigCenterSetting` | 无 | 映射为 Nacos namespace |
| `username` / `password` | `ConfigCenterSetting` | 无 | 认证 |
| `readTimeout` | `ConfigCenterSetting` | — | 长轮询超时（`CONFIG_LONG_POLL_TIMEOUT`） |
| `retryCount` | `ConfigCenterSetting` | — | 重试次数（`CONFIG_RETRY_TIME`） |
| `address` | `DiscoveryOption`（`NacosServiceDiscovery`） | — | 服务端地址；构造时的 `clusterName` 映射为 namespace |
| `address` / `password` | `MiddlewareConnectRequest`（`NacosClientProvider`） | — | 地址按 `http://<address>` 拼接；`password` 作为 `accessToken` |

---

## 5. SPI 扩展点

本模块通过 common-starter 的 SPI 机制注册三个实现，登记文件位于 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | SPI 名称 | 实现 | 登记文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.config.center.ConfigCenter` | `nacos` | `NacosConfigCenter` | `...config.center.ConfigCenter` |
| `com.chua.common.support.network.discovery.ServiceDiscovery` | `nacos` | `NacosServiceDiscovery` | `...network.discovery.ServiceDiscovery` |
| `com.chua.datasource.support.middleware.MiddlewareClientProvider` | `nacos` | `NacosClientProvider`（`order=200`） | `...middleware.MiddlewareClientProvider` |

获取方式：

```java
ServiceProvider.of(ConfigCenter.class).getNewExtension("nacos", setting);
ServiceProvider.of(ServiceDiscovery.class).getExtension("nacos");
ServiceProvider.of(MiddlewareClientProvider.class).getExtension("nacos");
```

GraalVM：`META-INF/native-image/com.chua/utils-support-nacos-starter/` 内置 `reflect-config.json`（注册 `NacosConfigCenter`、`NacosServiceDiscovery` 的全部构造器 / 方法 / 字段反射）与 `resource-config.json`（打包 `META-INF/extensions/.*`）。

---

## 6. 构建与依赖

```bash
# 仓库根目录（utils-support-parent-starter）
mvn -o -q compile -DskipTests

# 只编译本模块
mvn -o -q compile -DskipTests -pl utils-support-middleware-parent/utils-support-nacos-starter
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.alibaba.nacos:nacos-client` | `3.2.4`（根 pom `dependencyManagement` 统一管理） | 配置 / 命名服务官方客户端 |
| `org.yaml:snakeyaml` | `2.7` | `NacosConfigCenter` 的 YAML 解析与发布 |
| `com.chua:utils-support-common-starter` | 随父 pom | SPI 注解、`ServiceProvider`、`AbstractConfigCenter`、`AbstractServiceDiscovery`、工具类 |
| `com.chua:utils-support-datasource-starter` | 随父 pom | 管理面契约：`MiddlewareClientProvider` 及请求 / 结果模型 |

父链：`utils-support-nacos-starter` → `utils-support-middleware-parent` → `utils-support-parent-starter`（均为 `4.0.0.51`）。

---

## 7. 边界：这些能力不在本模块

- **没有 Spring Boot starter**：无自动装配、无 `@ConfigurationProperties`、无 `spring.factories`；接入方式只有直接 new 或 SPI 装配。
- **不包含 Nacos Server**：本模块只是客户端 / SPI 实现，需要自行部署 Nacos 服务端。
- **管理面只读**：`NacosClientProvider` 不提供发布配置、删除配置、注册实例（源码 Javadoc 明示“发布配置 / 删除配置 / 注册实例一律不提供”）；写操作请用 `NacosClient` 或 `NacosConfigCenter`。
- **`NacosConfigCenter.remove(dataId, key)` 只作用 `DEFAULT_GROUP`**：按 key 删除并重发剩余 YAML，不删除整个 dataId，也不支持指定分组。
- **抽象契约不在本模块**：`ConfigCenter` / `ServiceDiscovery` / `MiddlewareClientProvider` 等接口与抽象类分别定义在 `utils-support-common-starter` 与 `utils-support-datasource-starter`，本模块只提供 `nacos` 实现。
- **不含其它注册 / 配置中心**：Apollo、ZooKeeper、Consul 等由同仓对应 starter（如 `utils-support-apollo-starter`、`utils-support-zookeeper-starter`）提供。
- **不含负载均衡、熔断、灰度路由**等服务治理能力，也不提供 Feign / Dubbo / Spring Cloud 集成。
