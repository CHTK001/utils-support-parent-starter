# utils-support-apollo-starter

> utils 体系的 **Apollo 集成模块**：一个链式客户端 `ApolloClient`，加三个按名字 `apollo` 注册的 SPI 实现——配置中心（`ApolloConfigCenter`）、服务发现（`ApolloServiceDiscovery`）、中间件管理面（`ApolloClientProvider`），对 Apollo 全部只读消费。
>
> 版本：`4.0.0.51`（类级 `@since 4.0.0.42`）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.apollo.support`（子包：`client` / `config` / `discovery` / `spi`）

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-apollo-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Apollo（携程开源配置中心）在 utils 体系中的统一接入层：链式客户端 + 配置中心 SPI + 服务发现 SPI + 中间件管理面 SPI | 不是 Apollo 服务端；也不是对官方 `apollo-client`（2.5.0）的替换——客户端能力底层就是它 |
| 读写能力 | 读：配置项、命名空间、应用列表、服务发现条目、变更实时推送 | 写：配置发布/删除、服务注册/注销/更新一律不支持（仅本地缓存生效并打告警日志） |
| 装配方式 | 纯 SPI：4 份 `META-INF/extensions` 注册文件，按名字 `apollo` 取实现 | 无 Spring Boot 自动装配（无 `spring.factories` / `AutoConfiguration.imports`），模块自身零配置文件 |
| 依赖 | 仅 `apollo-client 2.5.0`、`utils-support-common-starter`、`utils-support-datasource-starter` | 不依赖 Spring，不依赖任何业务 starter |

---

## 2. 核心 API 与速查表

### 2.1 类型一览

| 类型 | 包 | 职责 | SPI |
| --- | --- | --- | --- |
| `ApolloClient` | `com.chua.apollo.support.client` | Apollo 全功能链式客户端：封装 `ConfigService`，提供配置获取与监听的链式 API；实现 `AutoCloseable` | 否（直接使用） |
| `ApolloConfigCenter` | `com.chua.apollo.support.config` | 配置中心实现：继承 `AbstractConfigCenter`，`dataId` 对应 Apollo 命名空间，支持变更监听 | `@Spi("apollo")` |
| `ApolloServiceDiscovery` | `com.chua.apollo.support.discovery` | 服务发现实现：继承 `AbstractServiceDiscovery`，把 Apollo 配置项当作服务目录（值为可解析为 `Discovery` 的 JSON）；只读 | `@Spi("apollo")` |
| `ApolloClientProvider` | `com.chua.apollo.support.spi` | 中间件管理面只读 SPI：不经 `apollo-client`，直接用 JDK `HttpClient` 调 Apollo REST 接口，读应用/命名空间/配置项 | `@Spi(value = "apollo", order = 200)` |

内部类型：`ApolloClient.Builder`（构建器）、`ApolloClient.ConfigOperation`（配置操作构建器，默认命名空间 `application`）、`ApolloClient.ApolloClientException`（运行时异常）。

### 2.2 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `ApolloClient.builder().appId("...").meta("...").namespaces("application").build().start()` | `appId` 必填（为空抛 `IllegalArgumentException`）；`meta` 可选；命名空间缺省 `application`；也可用 `ApolloClient.create(appId, meta)` |
| 读单个配置 | `client.config().namespace(ns).getProperty(key)` / `.getProperty(key, 默认值)` | 返回 `String` |
| 读指定类型 | `.getProperty(key, 默认值, Integer.class)` | 统一走 `Converter.convertIfNecessary` 做类型转换 |
| 读全部配置 | `.getProperties()` / `.getPropertiesMap()` / `.containsProperty(key)` | `Properties` / `Map<String, String>` / 是否存在 |
| 监听变更 | `.onChange(Consumer<ConfigChangeEvent>)`、`.onChange(key, Consumer<String>)`、`.onChangeKeys(Consumer<Map<String, String>>)` | 注册到 Apollo `Config` 的变更监听 |
| 配置中心接入 | `ConfigCenter` 的 `apollo` 实现 → `start()` / `get(dataId)` / `addListener(...)` | `dataId` = 命名空间；`get(dataId, group)` 忽略 `group` |
| 服务发现（只读） | `ServiceDiscovery` 的 `apollo` 实现 → `start()` / `subscribe(...)` | 命名空间缺省 `discovery`；`isSupportSubscribe()` 为 `true` |
| 中间件管理面查询 | `query(request, "apps" \| "namespaces" \| "configs <appId>\|<cluster>\|<namespace>", limit)` | 只读；另有连通测试 `test`、拓扑 `listTopNodes` / `listChildren`、详情 `document` |

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-apollo-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 链式客户端：读取并监听配置（最短可运行示例）

```java
// 1. 创建并启动客户端（start() 设置 app.id / apollo.meta 系统属性，并预加载全部命名空间）
ApolloClient client = ApolloClient.builder()
        .appId("my-app")
        .meta("http://apollo-server:8801")
        .namespaces("application", "database")
        .build()
        .start();

// 2. 读取配置
String host = client.config().namespace("application").getProperty("db.host");
Integer port = client.config().namespace("application").getProperty("db.port", 3306, Integer.class);
Map<String, String> all = client.config().namespace("database").getPropertiesMap();

// 3. 监听变更
client.config().namespace("database").onChange("db.host",
        newValue -> System.out.println("db.host -> " + newValue));

// 4. 关闭
client.close();
```

### 3.3 配置中心 SPI（`ConfigCenter`）

```java
ConfigCenterSetting setting = /* 由使用方构建；getProfile() 决定命名空间，空则 application */;
ConfigCenter center = new ApolloConfigCenter(setting);
center.start();                                          // 启动并注册 Apollo 变更监听
Map<String, Object> configs = center.get("application"); // dataId = 命名空间，返回全部 key/value
```

- 未调用 `start()` 就 `get(...)` 会抛 `IllegalStateException("Apollo 未初始化…")`；
- `addListener(dataId, listener)` 会把 Apollo 变更映射为 `ConfigListener` 的 `onChange` / `onUpdate` / `onDelete` 回调（新值为 `null` 视为删除）；
- `publish(...)` / `remove(...)` 直接返回 `false` 并告警：Apollo 作为只读配置源，写入需使用 Apollo 管理 API。

### 3.4 服务发现 SPI（`ServiceDiscovery`，只读）

```java
DiscoveryOption option = /* getDatabase() 决定命名空间，空则使用默认命名空间 discovery */;
ApolloServiceDiscovery discovery = new ApolloServiceDiscovery(option);
discovery.start();   // 加载该命名空间全部条目并监听后续变更
```

- 每个配置项的值必须是能被 `Json.fromJson(json, Discovery.class)` 解析的 JSON，解析失败仅告警跳过；
- `registerService` 只写本地缓存并告警（只读），`doUnregister` / `doUpdate` 为 no-op；
- `subscribe(serviceName, listener)` 在配置变更时以 `Event.UPDATE` 回调监听器；
- `close()` 清空本地缓存。

### 3.5 中间件管理面 SPI（`MiddlewareClientProvider`，只读）

```java
MiddlewareClientProvider provider = ServiceProvider.of(MiddlewareClientProvider.class)
        .getExtension("apollo");

MiddlewareTestResult ok      = provider.test(request);                 // 连通性探测
List<MiddlewareTreeNode> apps = provider.listTopNodes(request);        // 顶层节点 = 应用（key = appId）
List<MiddlewareTreeNode> nss  = provider.listChildren(request, appId); // 子节点 = 命名空间（key = appId|cluster|namespaceName）
MiddlewareQueryResult rows    = provider.query(request, "apps", 100);  // 只读查询
```

`query` 支持的表达式（命令词大小写不敏感）：

| 表达式 | 结果 |
| --- | --- |
| `apps` | 应用列表（列：`appId`、`name`） |
| `namespaces` | 命名空间列表（列：`appId`、`key`、`cluster`、`namespace`、`comment`） |
| `configs <appId>\|<cluster>\|<namespace>`，如 `configs 1234\|default\|application` | 该命名空间的配置项 key/value 表 |

`document(request, scope, key)`：`scope` 取 `config`（命名空间完整配置 JSON，走 Config Service `/configs/{appId}/{cluster}/{namespace}`）或 `namespace`（命名空间元信息，走 Admin Service `/v1/apps/{appId}/clusters/{cluster}/namespaces/{namespace}`）；`key` 格式为 `appId|cluster|namespace`。

依赖的 REST 接口（均为 GET，只读）：`/v1/apps?pageNo=&pageItems=`、`/v1/apps/{appId}/clusters/{cluster}/namespaces`、`/configs/{appId}/{cluster}/{namespace}`、`/services/config?appId=&ip=`。

---

## 4. 配置项

本模块**自身没有任何配置文件或配置类**（无 `application.yml`、`spring.factories`、`AutoConfiguration.imports`）。下表参数全部来自使用方传入或 Apollo SDK 的系统属性：

| 项 | 来源 | 默认 | 说明 |
| --- | --- | --- | --- |
| `app.id` | 系统属性，由 `ApolloClient.start()` 写入 | 无 | Apollo 应用 ID（`appId`） |
| `apollo.meta` | 系统属性，由 `ApolloClient.start()` 写入 | 不设置（仅当 `meta` 非空时写入） | Apollo Meta 服务地址 |
| 命名空间（客户端） | `ApolloClient.Builder.namespaces(...)` | `application` | 预加载的命名空间列表；`ConfigOperation` 默认操作 `application` |
| 命名空间（配置中心） | `ConfigCenterSetting.getProfile()` | `application` | `ApolloConfigCenter` 启动时使用的命名空间 |
| 命名空间（服务发现） | `DiscoveryOption.getDatabase()` | `discovery` | `ApolloServiceDiscovery` 读取的命名空间 |
| 管理面地址 | `MiddlewareConnectRequest.address()` | 无 | 拼接为 `http://<address>` 访问 |
| 管理面默认端口 | `ApolloClientProvider.defaultPort()` | `8080` | Apollo 管理面默认端口 |
| HTTP 超时 | 源码常量 | `5` 秒 | 管理面连接与请求超时 |
| 应用列表翻页条数 | 源码常量 `PAGE_ITEMS` | `100` | 管理面只读分页 |
| 集群列表 | 源码常量 `CLUSTER_NAMES` | `default`、`master` | 展开命名空间时逐集群查询 |

Apollo 的环境、集群、本地缓存文件等其余参数由 Apollo SDK 自身的系统属性/环境变量解析，本模块不重复定义。

---

## 5. SPI 扩展点

本模块向 utils SPI 体系注册了 4 份实现（`src/main/resources/META-INF/extensions/`）：

| SPI 接口 | 注册名 | 实现类 |
| --- | --- | --- |
| `com.chua.common.support.config.center.ConfigCenter` | `apollo` | `com.chua.apollo.support.config.ApolloConfigCenter` |
| `com.chua.common.support.network.discovery.ServiceDiscovery` | `apollo` | `com.chua.apollo.support.discovery.ApolloServiceDiscovery` |
| `com.chua.common.support.network.discovery.AbstractServiceDiscovery` | `apollo` | `com.chua.apollo.support.discovery.ApolloServiceDiscovery` |
| `com.chua.datasource.support.middleware.MiddlewareClientProvider` | `apollo` | `com.chua.apollo.support.spi.ApolloClientProvider`（`order = 200`） |

使用方式遵循 common-starter 的 SPI 约定（`com.chua.common.support.spi.ServiceProvider`）：

```java
MiddlewareClientProvider provider =
        ServiceProvider.of(MiddlewareClientProvider.class).getExtension("apollo");
```

> `ConfigCenter` / `ServiceDiscovery` 的实现带有必选构造参数（`ConfigCenterSetting` / `DiscoveryOption`），装配方式遵循 common-starter 的 SPI 装配约定；本模块源码未提供额外的装配示例。

GraalVM native-image 配置（`META-INF/native-image/com.chua/utils-support-apollo-starter/`）已为 `ApolloConfigCenter`、`ApolloServiceDiscovery`、`ApolloClientProvider` 三个类开放全部构造器/方法/字段的反射，并附带 `resource-config.json`。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（`maven.compiler.source/target=25`）。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译（`@Slf4j` / `@Getter`）。
- 从仓库根（`utils-support-parent-starter`）构建：

```bash
mvn -pl utils-support-middleware-parent/utils-support-apollo-starter -am clean package
```

### 6.2 依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.ctrip.framework.apollo:apollo-client` | `2.5.0`（属性 `apollo.version`） | `ConfigService` / `Config` / 变更监听（客户端、配置中心、服务发现） |
| `com.chua:utils-support-common-starter` | 随父 POM | SPI 框架、`Converter`、`Json`、`Wraps`、`AbstractConfigCenter`、`AbstractServiceDiscovery` 等契约 |
| `com.chua:utils-support-datasource-starter` | 随父 POM | 中间件管理面 SPI 契约（`MiddlewareClientProvider` 等） |

```
utils-support-apollo-starter
├── com.ctrip.framework.apollo:apollo-client 2.5.0
├── utils-support-common-starter
└── utils-support-datasource-starter （中间件管理面 SPI 契约）
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 配置发布 / 删除 | `ApolloConfigCenter.isSupportPublish()` 为 `false`；`publish` / `remove` 返回 `false` 并打告警日志。写 Apollo 需使用 Apollo 管理 API（本模块未封装） |
| 服务注册 / 注销 / 更新 | `ApolloServiceDiscovery` 为只读实现：`registerService` 仅写本地缓存，`doUnregister` / `doUpdate` 为 no-op，均打告警日志 |
| 其它配置中心 / 注册中心 | Nacos、ZooKeeper 等在 `utils-support-middleware-parent` 下的各自模块（如 `utils-support-nacos-starter`、`utils-support-zookeeper-starter`） |
| 中间件管理面的其它实现 | `MiddlewareClientProvider` 的其它中间件实现在各自 starter，本模块只提供 `apollo` 一个 |
| Spring Boot 自动装配 | 无；本模块是纯 utils 层 SPI 模块，不含任何 Spring 装配代码 |
| Apollo 服务端功能 | 控制台、发布审核、灰度发布等均为服务端能力，不在本模块 |
