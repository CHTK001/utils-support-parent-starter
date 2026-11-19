# utils-support-influxdb-starter

> utils 数据源体系里的 **InfluxDB 时序数据库接入模块**：一条 `SeriesEngine` SPI 实现（基于 `influxdb-java` 官方客户端的时序写入 / 曲线降采样 / 保留期清理），一条 `MiddlewareClientProvider` SPI 实现（JDK `HttpClient` 直连 InfluxDB v2 / v3 HTTP 管理面，零额外客户端依赖），外加一个继承 `AbstractEngine` 的内存过滤引擎与 InfluxQL 方言。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.influxdb.support`

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
    <artifactId>utils-support-influxdb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块描述（`pom.xml`）：`InfluxDB时序数据库ORM`，隶属 `utils-support-datasource-parent`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 时序引擎 | `SeriesEngine` SPI 的 `influxdb` 实现：写入、指标发现、窗口均值降采样曲线、保留期清理 | 不做监控采集、告警、面板渲染 |
| 管理面 | `MiddlewareClientProvider` SPI 的 `influxdb` 实现：连通性测试、桶/度量浏览、只读查询、桶详情（v2 Flux 与 v3 SQL 双支持） | 不做 Bucket 生命周期等管理写操作 |
| 查询引擎 | `Engine` SPI 的 `influxdb` 实现：内存数据 `store` + WHERE 内存过滤 + 分页排序 | 不把 SQL 下推给 InfluxDB，也不是 JDBC 数据源 |
| 方言 | `Dialect` SPI 的 `influxdb` 实现：InfluxQL，支持 LIMIT、无 OFFSET（深分页由引擎侧内存截取兜底） | 不提供 SQL 解析器本体（契约在 common / datasource-starter） |
| 依赖形态 | 纯 SPI starter：无 Spring 自动配置、无 `application.yml` 配置绑定 | 不是 Spring Boot starter，不引入 web/orm 容器能力 |

### 1.3 源码构成

5 个 Java 类（均在 `com.chua.influxdb.support` 下，`@since 4.0.0.42`，作者 `CH`）+ 4 个 SPI 注册文件 + 1 个方言环境文件 + GraalVM native-image 配置，见 [SPI 扩展点](#5-spi-扩展点)。

| 类 | 子包 | SPI 名称 | 职责 |
| --- | --- | --- | --- |
| `InfluxdbClientProvider` | `spi` | `MiddlewareClientProvider` 的 `influxdb`（`order = 200`） | HTTP 管理面：`test` / `listTopNodes` / `listChildren` / `query` / `document` |
| `InfluxdbSeriesEngine` | `engine` | `SeriesEngine` 的 `influxdb` | 时序写入与查询（官方 `InfluxDB` 客户端），实现 `AutoCloseable` |
| `InfluxDbEngine` | `engine` | `Engine` 的 `influxdb` | 内存数据过滤查询引擎，继承 `AbstractEngine` |
| `InfluxDbDialect` | `dialect` | `Dialect` 的 `influxdb` | InfluxQL 方言（配置驱动，读取 `META-INF/dialect-env/influxdb.env`） |
| `InfluxDbEngineDataSource` | `datasource` | —（实现 `EngineDataSource<InfluxDB>`，非 SPI） | 持有官方 `InfluxDB` 客户端的数据源封装 |

---

## 2. 核心 API 与速查表

时序契约 `SeriesEngine` 由 `utils-support-common-starter` 提供（`com.chua.common.support.lang.datasource.series.SeriesEngine`）；管理面契约 `MiddlewareClientProvider` 与连接参数 `MiddlewareConnectRequest` 由 `utils-support-datasource-starter` 提供（`com.chua.datasource.support.middleware`，本模块对其为 `provided` 依赖）。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 写一个时序点 | `InfluxdbSeriesEngine#writePoint(target, monitorId, metricName, value, timestamp)` | measurement = target，tag = `monitor_id`，每个指标名一个 field，毫秒时间戳；失败必须抛异常，不静默丢点 |
| 查均值曲线 | `InfluxdbSeriesEngine#series(target, monitorId, metricName, hours, window)` | `SELECT` 拉原始点后 Java 侧按窗口均值降采样（`SeriesEngine.downsample`），单次返回上限 1440 点，返回 `[[ts, value], ...]` 升序 |
| 发现指标名 | `InfluxdbSeriesEngine#metrics(target, monitorId)` | 用 `LIMIT 1` 探测该 `(target, monitorId)` 实际有数据的 field（比 `SHOW FIELD KEYS` 精确），字典序返回 |
| 保留期清理 | `InfluxdbSeriesEngine#purge(target, monitorId, metricName, beforeMillis)` | InfluxQL `DELETE FROM ... WHERE time < ?ms`；InfluxDB 不返回删除条数，**如实返回 `-1`**（已清理、无法统计；`0` 才代表不支持） |
| 连通性测试 | `InfluxdbClientProvider#test(request)` | v2 走 `/health`（失败再试 `/ping`），v3 走 `/ping`；返回版本与模式描述，失败返回 `connected=false` 而不抛异常 |
| 浏览桶 / 度量 | `InfluxdbClientProvider#listTopNodes(request)` / `#listChildren(request, parentKey)` | v2：`/api/v2/buckets` 列桶，Flux `schema.measurements` 列度量；v3：库即顶层节点，SQL 查 `_measurement` 列度量 |
| 只读查询 | `InfluxdbClientProvider#query(request, expression, limit)` | 表达式支持 `buckets` / `measurements` / `flux <Flux 语句>` / `sql <InfluxDB v3 SQL 语句>` |
| 内存过滤查询 | `new InfluxDbEngine().store(name, data)` + `query(Class).eq(...).list()` | 数据存 JVM 内存，WHERE 由 `MemoryWhereParser` 内存执行，limit/offset 内存截取 |

批量写入 `writeBatch(target, monitorId, Map<String, Double>, timestamp)` 来自 `SeriesEngine` 的 default 方法，逐点委托 `writePoint`，无需在 InfluxDB 侧单独实现。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-influxdb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

传递依赖会带入 `influxdb-java`（官方客户端）与 `okhttp`；`retrofit` 已被排除，运行期由上层已解析的版本提供（见 [构建与依赖](#6-构建与依赖)）。

### 3.2 时序读写（SeriesEngine SPI）

按 SPI 配置构造引擎（`InfluxdbSeriesEngine` 的 `Properties` 构造器，`url` / `database` 必填）：

```java
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Properties;

Properties props = new Properties();
props.setProperty("url", "http://127.0.0.1:8086");
props.setProperty("database", "monitor");        // 必填
props.setProperty("username", "admin");          // InfluxDB 1.x 可选；2.x 填 token
props.setProperty("password", "secret");         // InfluxDB 1.x 可选；2.x 留空

SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("influxdb", props);
```

写入、发现、查询、清理、释放：

```java
// 写入：measurement = target，tag = monitor_id，field = 指标名，毫秒时间戳
engine.writePoint("ms_monitor_metric_cpu", 1L, "cpu_usage", 25.5, System.currentTimeMillis());

// 指标发现：该 (target, monitorId) 下实际有数据的指标名
java.util.List<String> metrics = engine.metrics("ms_monitor_metric_cpu", 1L);

// 曲线：近 1 小时、5 分钟窗口均值降采样，返回 [[ts, value], ...] 升序，可直接喂折线图
java.util.List<java.util.List<Object>> curve =
        engine.series("ms_monitor_metric_cpu", 1L, "cpu_usage", 1, "5m");

// 保留期清理：删除 7 天前的点；InfluxDB 无法统计条数，返回 -1
int removed = engine.purge("ms_monitor_metric_cpu", 1L, "cpu_usage",
        System.currentTimeMillis() - 7L * 24 * 3600 * 1000);

// 引擎实现了 AutoCloseable；关闭后 isAvailable() 恒为 false
engine.close();
```

注意两点契约（`SeriesEngine` 接口约定，本实现严格遵守）：

- `metricName` 传 `null` 时，仅当该 `(target, monitorId)` 下**恰好只有一个**指标才返回曲线；存在多个指标会抛 `IllegalArgumentException` 并列出候选指标名，绝不把多个指标混成一条曲线。
- `isAvailable()` 只回答「已装配且未关闭」，不探测后端实时可达性；后端宕机由 `writePoint` / `series` 抛 `IllegalStateException` 来体现。

### 3.3 中间件管理面（MiddlewareClientProvider SPI）

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;

// 引依赖即生效：META-INF/extensions 中已注册 influxdb 实现
MiddlewareClientProvider influx =
        ServiceProvider.of(MiddlewareClientProvider.class).getExtension("influxdb");

influx.name();          // "influxdb"
influx.displayName();   // "InfluxDB 时序数据库"
influx.defaultPort();   // 8086

// 连接参数：host、port、database、username、password（不可变对象）
MiddlewareConnectRequest request =
        MiddlewareConnectRequest.of("127.0.0.1", 8086, "monitor", "admin", "my-token");

// 连通性测试（失败返回失败结果对象，不抛异常）
System.out.println(influx.test(request));

// 浏览：顶层节点 = 存储桶（v2）；子节点 = 度量
influx.listTopNodes(request).forEach(node -> System.out.println(node.label()));
influx.listChildren(request, "monitor").forEach(node -> System.out.println(node.label()));

// 只读查询：buckets / measurements / flux <语句> / sql <语句>
System.out.println(influx.query(request, "buckets", 100).rows());
System.out.println(influx.query(request, "flux from(bucket: \"monitor\") |> range(start: -1h) |> limit(n: 5)", 5).rows());
System.out.println(influx.query(request, "sql SELECT * FROM monitor", 10).rows());
```

v2 / v3 的区分：建数据源时把 `database` 字段写成 `v3` 即按 InfluxDB v3（SQL，`/api/v3/query_sql`）处理，否则按 v2（Flux，`/api/v2/query`）处理；不做版本探测。v2 查询的 org 参数取 `username`，认证头为 `Authorization: Token <password>`。

### 3.4 内存过滤查询（Engine SPI）

`InfluxDbEngine` 继承 `AbstractEngine`，数据放在 JVM 内存里，WHERE 条件在内存执行：

```java
import com.chua.influxdb.support.engine.InfluxDbEngine;

import java.util.List;

InfluxDbEngine engine = new InfluxDbEngine();
engine.store("user", List.of(                                  // 按表名存；首次 store 成为默认表
        new User("张三", 25),
        new User("李四", 31)));

// query(Class) 返回 LambdaQueryWrapper：条件方法 eq/ne/gt/ge/lt/le/like/in/between/orderBy...，
// 终端方法 list() / one() / count()；LIMIT/OFFSET 由方言拼接，内存截取兜底
List<User> adults = engine.query(User.class)
        .ge("age", 18)
        .orderByAsc("age")
        .limit(10)
        .list();

engine.close();   // 继承自 AbstractEngine：关闭后 dataStores / dataSources 清空，操作幂等
```

`store(List)` 单参重载等价于存到默认表名 `default`。更新 / 删除默认走内存实现（`executeUpdate` / `executeDelete`），插入在无 JDBC 执行器时直接追加实体到内存表。

### 3.5 直接持有官方客户端（InfluxDbEngineDataSource）

```java
import com.chua.influxdb.support.datasource.InfluxDbEngineDataSource;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;

InfluxDB client = InfluxDBFactory.connect("http://127.0.0.1:8086", "admin", "secret");
InfluxDbEngineDataSource ds = new InfluxDbEngineDataSource(
        "influx-main", "http://127.0.0.1:8086", "admin", "secret", "monitor", client);

ds.getSource();     // 官方 org.influxdb.InfluxDB 客户端
ds.getDialect();    // 默认 Dialect.getExtension("influxdb")，可 setDialect 替换
ds.database();      // "monitor"
ds.close();         // 关闭底层客户端
```

`setSource(...)` 运行期不支持替换，会抛 `UnsupportedOperationException`（需重新注册新数据源）。

---

## 4. 配置项

### 4.1 时序引擎（`InfluxdbSeriesEngine` 的 `Properties` 键）

| 键 | 必填 | 说明 |
| --- | --- | --- |
| `url` | 是 | InfluxDB 服务地址，如 `http://127.0.0.1:8086`；缺失时引擎不可用（打印 `[SeriesEngine] InfluxdbSeriesEngine 不可用：url/database 未配置`） |
| `database` | 是 | 数据库名；缺失时引擎不可用 |
| `username` | 否 | InfluxDB 1.x 用户名；2.x 把 token 填在这里 |
| `password` | 否 | InfluxDB 1.x 密码；2.x 留空 |

也支持直接注入客户端的构造器：`new InfluxdbSeriesEngine(InfluxDB)`（database 默认 `monitor`）、`new InfluxdbSeriesEngine(InfluxDB, String database)`、`new InfluxdbSeriesEngine(String url, String database, String username, String password)`。

### 4.2 管理面连接参数（`MiddlewareConnectRequest` 字段）

| 字段 | 说明 |
| --- | --- |
| `host` / `port` | 服务地址；拼接为 `address()`，模块内按 `http://<address>` 访问（仅 HTTP，源码未提供 HTTPS 切换） |
| `database` | 默认逻辑库 / 桶；写 `v3`（不区分大小写）即按 InfluxDB v3 实例处理 |
| `username` | v2 查询的 org 参数；空表示无认证 |
| `password` | Token 认证值，请求头 `Authorization: Token <password>`；空则不带认证头 |
| `datasourceId` / `datasourceName` | 数据源行主键与名称，仅透传（日志 / 缓存键用） |

HTTP 超时 8 秒（`HTTP_TIMEOUT`），响应体上限 2 MB（`MAX_BODY`）。

### 4.3 方言环境文件 `META-INF/dialect-env/influxdb.env`

`InfluxDbDialect` 构造时按协议名 `influxdb` 读取，当前内容：

| 键 | 值 | 说明 |
| --- | --- | --- |
| `protocol` | `influxdb` | 协议名 |
| `driver` | （空） | 无 JDBC 驱动 |
| `url` | `http://<IP>:<PORT>` | 地址模板 |
| `query-lang` | `influxql` | 查询语言 |
| `supports-native-pagination` | `true` | 支持原生分页（LIMIT） |
| `supports-limit` | `true` | InfluxQL 支持 LIMIT |
| `pagination-sql` | `{sql} LIMIT {limit}` | 分页模板；InfluxDB 1.x 无 OFFSET，深分页由引擎侧内存截取兜底 |
| `table-name-template` | `{entity_class}` | 实体表名默认映射 |

### 4.4 Spring Boot 配置绑定

无。本模块是 utils 层 SPI starter，源码未提供 Spring Boot 自动配置类与 `application.yml` / `spring.factories` / `AutoConfiguration.imports` 配置绑定；装配统一走 `ServiceProvider` SPI（宿主引依赖即生效）。

---

## 5. SPI 扩展点

本模块通过 `META-INF/extensions/` 向 4 个扩展点注册 `influxdb` 实现，宿主用 `ServiceProvider` 按名装配：

| 扩展点（注册文件名） | 注册项 | 实现类 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.series.SeriesEngine` | `influxdb` | `com.chua.influxdb.support.engine.InfluxdbSeriesEngine` |
| `com.chua.common.support.lang.datasource.engine.Engine` | `influxdb` | `com.chua.influxdb.support.engine.InfluxDbEngine` |
| `com.chua.common.support.lang.datasource.dialect.Dialect` | `influxdb` | `com.chua.influxdb.support.dialect.InfluxDbDialect` |
| `com.chua.datasource.support.middleware.MiddlewareClientProvider` | `influxdb` | `com.chua.influxdb.support.spi.InfluxdbClientProvider` |

实现类上的 `@Spi` 注解与注册文件一致（`InfluxdbClientProvider` 额外声明 `order = 200`）。GraalVM native-image 配置：`reflect-config.json` 对 `InfluxDbEngine` 开放全部构造器 / 方法 / 字段反射，`resource-config.json` 打包 `META-INF/extensions/.*` 资源。

新增或替换实现时，在**自己模块**的 `META-INF/extensions/<扩展点全限定名>` 里按 `<名称>=<实现类全限定名>` 登记，不修改本模块。

---

## 6. 构建与依赖

依赖树（`pom.xml`）：

```
utils-support-influxdb-starter
├── com.chua:utils-support-common-starter          （compile：SPI、SeriesEngine、工具）
├── com.chua:utils-support-datasource-starter      （provided：Engine/Middleware 契约，由宿主提供）
├── org.influxdb:influxdb-java:2.25                （官方客户端；已排除自带的 retrofit）
├── com.squareup.retrofit2:converter-moshi:2.9.0   （已排除 retrofit）
└── com.squareup.okhttp3:okhttp:4.12.0
```

- `retrofit` 统一由上层已解析的版本提供，本模块不重复引入（避免版本冲突）。
- 管理面实现用 JDK 自带 `java.net.http.HttpClient`，不引入额外 HTTP 依赖。
- 测试进程回收：`influxdb-java` / OkHttp 存在非守护线程，surefire 配置 `forkedProcessExitTimeoutInSeconds=15`、`surefire.timeout=120`，测试完成后强制回收 fork 进程。
- 编译验证（交付口径）：在本模块目录执行 `mvn -o -q compile -DskipTests`，要求 `EXIT=0`。

---

## 7. 边界：这些能力不在本模块

- **不是 JDBC 数据源**：`InfluxDbEngine` 是内存过滤引擎（数据 `store` 在 JVM 内存，WHERE 内存执行），不连接 InfluxDB 执行 SQL；真正的时序读写走 `InfluxdbSeriesEngine` 里的官方客户端。
- **管理面只读**：`InfluxdbClientProvider` 只实现连通性测试、节点浏览、只读查询与桶详情；不提供建桶、删桶、用户管理等管理写操作。
- **清理不可精确统计**：InfluxDB 的 `DELETE` 不返回受影响行数，`purge` 如实返回 `-1`（已清理、条数未知）；且 InfluxQL 的 `DELETE` 只能按 measurement 整体删除，`metricName` 不参与删除条件（仅出现在日志中）。
- **不探测版本**：v2 / v3 由数据源 `database` 字段是否为 `v3` 约定区分，模块内不做版本探测接口调用。
- **无 HTTPS 管理面**：`InfluxdbClientProvider` 的基址固定为 `http://<address>`。
- **无 Spring 自动配置、无监控采集 / 告警 / 可视化**：这些能力分别属于宿主工程与监控体系，本模块只提供上述 SPI 实现。
