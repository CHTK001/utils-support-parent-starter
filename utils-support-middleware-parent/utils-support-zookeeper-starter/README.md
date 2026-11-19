# utils-support-zookeeper-starter

> utils 体系的 **ZooKeeper 集成模块**：一套链式客户端 + 配置中心实现 + 服务发现实现 + 中间件管理面实现，全部基于 Apache Curator，按 `com.chua` SPI 体系装配。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.zookeeper.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API](#2-核心-api)
  - [2.1 ZookeeperClient（链式客户端）](#21-zookeeperclient链式客户端)
  - [2.2 ZookeeperConfigCenter（配置中心）](#22-zookeeperconfigcenter配置中心)
  - [2.3 ZookeeperServiceDiscovery（服务发现）](#23-zookeeperservicediscovery服务发现)
  - [2.4 ZookeeperClientProvider（中间件管理面）](#24-zookeeperclientprovider中间件管理面)
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
    <artifactId>utils-support-zookeeper-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 客户端 | `ZookeeperClient`：Builder + 链式 API 封装 Curator，覆盖节点 CRUD 与存在性/子节点查询 | 不是对 ZooKeeper 原生 API 的重新发明，底层仍是 Curator `CuratorFramework` |
| 配置中心 | `ZookeeperConfigCenter`：把 znode 当配置存储，自动解析 YAML / Properties / 纯文本 | 不是 Spring 的 `@ConfigurationProperties` 数据源，本模块没有任何 Spring 依赖 |
| 服务发现 | `ZookeeperServiceDiscovery`：临时节点注册 + Watch 递归监听 + 监听器回调 | 不是注册中心服务端，只是客户端实现 |
| 管理面 | `ZookeeperClientProvider`：连接测试、znode 树浏览、只读查询（`ls` / `get` / `stat`） | 管理面**只读**：create / setData / delete 一律不实现 |
| 装配 | 通过 `META-INF/extensions/` 以 SPI 名称 `zookeeper` 装配 | 不是 Spring Boot starter，没有自动配置类 |

### 1.3 规模

- Java 源文件 **4** 个，包 `com.chua.zookeeper.support` 下 4 个子包：`client`、`config`、`discovery`、`spi`。
- SPI 注册文件 **4** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`、`resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 节点 CRUD | `ZookeeperClient.create("127.0.0.1:2181")` | `create() / getData() / setData() / delete() / checkExists() / getChildren()` 链式操作 |
| 连接调优 | `ZookeeperClient.builder()` | 会话/连接超时、指数退避重试、namespace |
| 读配置 | `new ZookeeperConfigCenter(ConfigCenterSetting)` | `start()` 后 `get(dataId)`，YAML / Properties 自动解析 |
| 服务注册 | `new ZookeeperServiceDiscovery(option)` | `registerService(path, discovery)`，临时节点，断连自动摘除 |
| 服务订阅 | `discovery.subscribe(serviceName, listener)` | 节点增删回调（`Event.ADD` / `Event.REMOVE`） |
| 连接测试 | `ServiceProvider.of(MiddlewareClientProvider.class).getExtension("zookeeper")` | `test(request)`，返回 zxid 与子节点数 |
| 树浏览 | `provider.listTopNodes(request)` / `listChildren(request, key)` | 按层展开，单次列举上限 500 |
| 只读查询 | `provider.query(request, "ls /path", limit)` | 支持 `ls` / `get` / `stat` 三种表达式 |

---

## 2. 核心 API

### 2.1 ZookeeperClient（链式客户端）

`com.chua.zookeeper.support.client.ZookeeperClient`，实现 `AutoCloseable`，全功能封装 Curator。

```java
ZookeeperClient client = ZookeeperClient.create("127.0.0.1:2181");

client.create().path("/app/config").data("hello").forPath();     // 创建（默认持久节点，自动建父目录）
String  value   = client.getData().path("/app/config").forString();
client.setData().path("/app/config").data("world").forPath();
client.delete().path("/app/config").forPath();

boolean exists       = client.checkExists().path("/app/config").forBool();
List<String> children = client.getChildren().path("/app").forList();
int count             = client.getChildren().path("/app").forCount();
int dataLength        = client.getData().path("/app/config").forStat();   // -1 表示节点不存在

client.start();                       // 显式启动（create() 工厂不自动 start）
client.blockUntilConnected();         // 等待连接就绪
client.getCurator();                  // 取底层 CuratorFramework
client.close();                       // AutoCloseable
```

工厂与 Builder：

| API | 说明 |
| --- | --- |
| `create(String connectString)` | 快速创建，connectString 默认 `127.0.0.1:2181` |
| `create(String connectString, int sessionTimeoutMs)` | 指定会话超时 |
| `builder()` | 完整构建器：`connectString`、`sessionTimeoutMs`（默认 30000）、`connectionTimeoutMs`（默认 15000）、`retryBaseSleepMs`（默认 1000）、`retryMaxRetries`（默认 3）、`namespace` |

节点创建模式（`CreateBuilder`）：`persistent()`（默认）/ `persistentSequential()` / `ephemeral()`（断连自动删除）/ `ephemeralSequential()`（自动追加递增序号）/ `forEphemeral(boolean)`（兼容旧 API），另有 `creatingParentsIfNeeded(boolean)`（默认 true）与 `withACL(Map<String, byte[]>)`。删除（`DeleteBuilder`）支持 `deletingChildrenIfNeeded(boolean)` 级联删除与 `quiet(boolean)` 静默模式。

> 类头 Javadoc 中还给出过 `getData().path(...).watch((type, event) -> {...}).forPath()` 的 Watch 示例；该链式方法在当前源码中**未提供**，请以 `getCurator()` 暴露的 Curator 原生 Watch 能力为准。

### 2.2 ZookeeperConfigCenter（配置中心）

`com.chua.zookeeper.support.config.ZookeeperConfigCenter`，继承 `AbstractConfigCenter`，SPI 名称 `zookeeper`。

- 配置路径规则：`/{dataId}` 或 `/group/{dataId}`；`get(dataId, group)` 等价于 `get(group + ":" + dataId)`。
- 内容解析自动识别三种格式：
  - **YAML**（含冒号且含换行/缩进）→ SnakeYAML 解析并 `flattenMap` 展平成 `a.b.c` 键；
  - **Properties**（含等号且含换行）→ 逐行解析，跳过空行与 `#` 注释；
  - 其他 → 作为单个值返回，键为 `value`。
- `start()` 使用 `ConfigCenterSetting`：`ExponentialBackoffRetry(1000, retryCount, 10000)`；`profile` 非空时作为 Curator `namespace` 隔离环境；`username`/`password` 非空时以 `digest` 方式认证（`username:password`）；连接建立有超时保护，超时抛 `RuntimeException("连接 ZooKeeper 超时: " + address)`。
- 节点不存在或数据为空时返回空 Map 并告警日志，不抛异常；读取失败（IO/会话）抛 `RuntimeException`。

```java
ZookeeperConfigCenter center = new ZookeeperConfigCenter(
        ConfigCenterSetting.builder()
                .address("127.0.0.1:2181")
                .profile("dev")              // 可选：命名空间隔离
                .build());
center.start();

Map<String, Object> conf = center.get("app.yml");          // 读 /app.yml
Map<String, Object> conf2 = center.get("app.yml", "dev");  // 读 /dev:app.yml

center.close();
```

### 2.3 ZookeeperServiceDiscovery（服务发现）

`com.chua.zookeeper.support.discovery.ZookeeperServiceDiscovery`，继承 `AbstractServiceDiscovery`，SPI 名称 `zookeeper`。

- **注册**：`registerService(path, discovery)` 在 `root + 集群前缀 + path + "/" + host + ":" + port` 创建 **EPHEMERAL** 节点（`creatingParentContainersIfNeeded`），节点内容为 `Discovery` 的 JSON；会话断开节点自动摘除。
- **根路径**：取 `DiscoveryOption.root`，为空时默认 `/discovery`。
- **监听**：`start()` 后对根路径递归 `getChildren().usingWatcher(...)`，`NodeChildrenChanged` 时重拉实例并比对 `serverId` 差集，回调 `ServiceDiscoveryListener.listen(path, discovery, Event.ADD / Event.REMOVE)`。
- **订阅**：`isSupportSubscribe()` 返回 `true`；`subscribe(serviceName, listener)` / `unsubscribe(serviceName, listener)` 按路径登记。
- **注销/更新**：`unregister` 支持仅凭 `serverId` 反查 host/port 后删除；删除前会快照当前实例列表用于回调比对；`update` 为 `setData` 覆盖 JSON。
- **连接**：`RetryNTimes(3, 1000)`，最多等待 10 秒，超时抛 `RuntimeException("Zookeeper connect timeout")`。
- `close()` 仅在 `CuratorFrameworkState.STARTED` 时关闭客户端。

```java
DiscoveryOption option = new DiscoveryOption();
option.setAddress("127.0.0.1:2181");

ZookeeperServiceDiscovery discovery = new ZookeeperServiceDiscovery(option);
discovery.start();

discovery.registerService("/users", Discovery.builder()
        .serverId("node-1")
        .host("192.168.1.10")
        .port(8080)
        .weight(1.0)
        .protocol("http")
        .uriSpec("/users")
        .env("prod")
        .build());

discovery.subscribe("/users", (path, d, event) ->
        System.out.println(event + " " + d.getHost() + ":" + d.getPort()));

discovery.close();
```

### 2.4 ZookeeperClientProvider（中间件管理面）

`com.chua.zookeeper.support.spi.ZookeeperClientProvider`，实现 `com.chua.datasource.support.middleware.MiddlewareClientProvider`，`@Spi(value = "zookeeper", order = 200)`。

| 方法 | 行为 |
| --- | --- |
| `name()` | `"zookeeper"`（与数据源 `type` 列对应） |
| `displayName()` | `"ZooKeeper 配置中心"` |
| `defaultPort()` | `2181` |
| `capability()` | 声明支持树浏览、只读查询；查询语法 `ls /path`、`get /path`、`stat /path` |
| `test(request)` | 建连测试：`checkExists("/")`，成功返回 `zxid` 与根节点子节点数 |
| `listTopNodes(request)` | 列根节点 `/` 的子节点 |
| `listChildren(request, parentKey)` | 按层展开子节点，单次上限 500，附带子节点数量 |
| `query(request, expression, limit)` | 只读查询：`ls` 返回子节点路径列；`get` 返回 path/data（超 64KB 截断）；`stat` 返回 czxid/mzxid/ctime/mtime/version/cversion/dataLength/numChildren/ephemeralOwner |
| `document(request, scope, key)` | 读取单个 znode，附 Stat 属性（czxid、mzxid、version、numChildren、dataLength） |

- 连接参数：会话超时 8000ms、连接超时 5000ms、`ExponentialBackoffRetry(500, 2)`。
- 认证：username 非空时使用 ZooKeeper SASL/DIGEST（`DigestLoginModule`）授权；管理面只读，不涉及 ACL 提供者。
- **只读定位**：create / setData / delete 一律不实现 —— 管理面是「看清现状」的。
- 失败均以结果对象返回（`MiddlewareTestResult.fail` / `MiddlewareQueryResult.fail`），不向宿主抛异常；`KeeperException` 会附上错误码。

---

## 3. 快速开始

最短可运行示例（本机 ZooKeeper：`127.0.0.1:2181`）：

```java
import com.chua.zookeeper.support.client.ZookeeperClient;

// 1) 链式客户端：创建 → 读 → 改 → 删
try (ZookeeperClient client = ZookeeperClient.create("127.0.0.1:2181")) {
    client.create().path("/demo/config").data("hello").forPath();
    String value = client.getData().path("/demo/config").forString();
    client.setData().path("/demo/config").data("world").forPath();
    boolean exists = client.checkExists().path("/demo/config").forBool();
    client.delete().path("/demo/config").deletingChildrenIfNeeded(true).forPath();
}
```

```java
import com.chua.common.support.config.center.ConfigCenterSetting;
import com.chua.zookeeper.support.config.ZookeeperConfigCenter;
import java.util.Map;

// 2) 配置中心：znode 内容自动解析为键值
try (ZookeeperConfigCenter center = new ZookeeperConfigCenter(
        ConfigCenterSetting.builder().address("127.0.0.1:2181").build())) {
    center.start();
    Map<String, Object> conf = center.get("demo/app.yml");
    System.out.println(conf);
}
```

```java
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.zookeeper.support.discovery.ZookeeperServiceDiscovery;

// 3) 服务注册与订阅
DiscoveryOption option = new DiscoveryOption();
option.setAddress("127.0.0.1:2181");

ZookeeperServiceDiscovery discovery = new ZookeeperServiceDiscovery(option);
discovery.start();
discovery.registerService("/demo", Discovery.builder()
        .serverId("node-1").host("127.0.0.1").port(8080)
        .weight(1.0).protocol("http").env("dev")
        .build());
discovery.subscribe("/demo", (path, d, event) -> System.out.println(event + " " + d.getServerId()));
discovery.close();
```

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.middleware.*;

// 4) 管理面：连接测试 + 只读查询
MiddlewareClientProvider provider =
        ServiceProvider.of(MiddlewareClientProvider.class).getExtension("zookeeper");
MiddlewareConnectRequest request = MiddlewareConnectRequest.of("127.0.0.1", 2181, "", "", "");
System.out.println(provider.test(request).isConnected());
System.out.println(provider.query(request, "ls /", 100));
```

---

## 4. 配置项

本模块**没有 Spring 自动配置与配置文件**（无 `application.yml`、无 `spring.factories`/`AutoConfiguration.imports`），全部参数通过构造入参对象传入：

| 入参对象 | 用于 | 字段（默认值） |
| --- | --- | --- |
| `ZookeeperClient.Builder` | `ZookeeperClient` | `connectString`（`127.0.0.1:2181`）、`sessionTimeoutMs`（30000）、`connectionTimeoutMs`（15000）、`retryBaseSleepMs`（1000）、`retryMaxRetries`（3）、`namespace`（无） |
| `ConfigCenterSetting` | `ZookeeperConfigCenter` | `address`、`profile`、`username`、`password`、`connectionTimeout`（3000）、`readTimeout`（5000）、`retryCount`（3）、`sslEnabled`（false） |
| `DiscoveryOption` | `ZookeeperServiceDiscovery` | `address`、`root`（`discovery`）、`user`、`password`、`database`、`connectionTimeoutMillis`（10000）、`sessionTimeoutMillis`（10000）、`deferToInterface`（false）、`extra` |
| `MiddlewareConnectRequest` | 管理面 | 由宿主从数据源行解析透传：`host`、`port`、`database`、`username`、`password`；`address()` 即 `host:port` |

除上述对象字段外，本模块没有其他可配置项。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，通过 `META-INF/extensions/` 注册，名称统一为 `zookeeper`：

| SPI 接口 | 注册文件 | 实现类 |
| --- | --- | --- |
| `com.chua.datasource.support.middleware.MiddlewareClientProvider` | 同名文件 | `ZookeeperClientProvider` |
| `com.chua.common.support.config.center.ConfigCenter` | 同名文件 | `ZookeeperConfigCenter` |
| `com.chua.common.support.network.discovery.ServiceDiscovery` | 同名文件 | `ZookeeperServiceDiscovery` |
| `com.chua.common.support.network.discovery.AbstractServiceDiscovery` | 同名文件 | `ZookeeperServiceDiscovery` |

宿主侧统一通过 `com.chua.common.support.spi.ServiceProvider` 装配：

```java
// 管理面（无状态，getExtension 即取）
MiddlewareClientProvider p = ServiceProvider.of(MiddlewareClientProvider.class).getExtension("zookeeper");

// 配置中心 / 服务发现：实现类构造器需要入参，用 getNewExtension 透传构造参数
ConfigCenter center = ServiceProvider.of(ConfigCenter.class)
        .getNewExtension("zookeeper", configCenterSetting);
```

本模块**不提供**新的 SPI 扩展点；不需要再写注册文件来使用它。

---

## 6. 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `org.apache.curator:curator-framework` | 5.9.0 | 连接管理、节点操作 |
| `org.apache.curator:curator-client` | 5.9.0 | Curator 底层客户端 |
| `org.apache.zookeeper:zookeeper` | 3.9.6 | 原生 API（管理面读 `Stat`：czxid/mzxid/version 等） |
| `org.yaml:snakeyaml` | 2.7 | 配置中心 YAML 解析 |
| `com.chua:utils-support-common-starter` | 4.0.0.51 | SPI、JSON、集合、工具 |
| `com.chua:utils-support-datasource-starter` | 4.0.0.51 | 中间件管理面契约 `MiddlewareClientProvider` 等 |

父模块：`com.chua:utils-support-middleware-parent:4.0.0.51`（中间件集成父模块）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去向 / 说明 |
| --- | --- |
| Spring Boot 自动装配、`application.yml` 配置绑定 | 不在本模块；本模块无任何 Spring 依赖，参数全部代码传入 |
| 配置中心的管理控制台 / 写入配置 | 管理面只读；写 znode 请用 `ZookeeperClient`（`create()` / `setData()` / `delete()`） |
| Curator 高级配方（Leader 选举、分布式锁、Barrier 等） | 本模块未引入 `curator-recipes`，源码未提供 |
| 节点 Watch 的链式封装 | 类头 Javadoc 示例中的 `watch(...)` 链式方法源码未提供；需要 Watch 请经 `getCurator()` 使用 Curator 原生 API |
| 其他中间件（Kafka / Redis / Nacos / Apollo …） | 各自由 `utils-support-middleware-parent` 下对应 starter 提供，本模块仅含 ZooKeeper |
| 注册中心服务端 | 本模块只是客户端：注册、发现、订阅的实现方，不内嵌 ZooKeeper 服务器 |
