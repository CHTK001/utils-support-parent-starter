# utils-support-prometheus-starter

> Prometheus 监控中间件集成模块，提供链式查询客户端、Engine 引擎与自动装配：基于 JDK 内置 `HttpClient` 封装 Prometheus HTTP API，支持即时查询、范围查询、序列/标签发现、目标/规则/告警查询，并以 SPI 方式接入 DataSource Engine 体系。
>
> 版本：`4.0.0.51`（继承父 POM `utils-support-middleware-parent`，本模块 API 自 `4.0.0.42` 引入）｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.prometheus.support`

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
    <artifactId>utils-support-prometheus-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Prometheus HTTP API 的**只读查询**客户端与 Engine 集成 | 不是 Prometheus Server，也不做指标采集与暴露 |
| 客户端 | 基于 JDK 内置 `HttpClient` 的链式查询客户端（`PrometheusClient`） | 不依赖 OkHttp / Apache HttpClient 等第三方 HTTP 栈 |
| 集成 | 以 SPI 注册为 `Engine("prometheus")`（`PrometheusEngine`）；提供 Spring Boot 自动装配 | 不含 SQL 执行器、Lambda 增删改与元数据能力 |
| 依赖 | 仅依赖 common / datasource 底层模块与 `spring-boot-autoconfigure` | 不依赖任何上层业务 starter |

### 1.3 包结构

| 包 | 内容 |
| --- | --- |
| `client` | `PrometheusClient` 链式查询客户端（含 `QueryOperation` / `RangeQueryOperation` / `SeriesOperation` / `Builder`） |
| `engine` | `PrometheusEngine`，实现 `com.chua.common.support.lang.datasource.engine.Engine`，SPI 名 `prometheus` |
| `datasource` | `PrometheusDataSource`，实现 `EngineDataSource<PrometheusClient>` |
| `config` | `PrometheusAutoConfiguration`、`PrometheusProperties`（前缀 `prometheus`） |
| `model` | `QueryResult`、`PrometheusMetric`（含 `Sample`）、`PrometheusTarget`、`PrometheusRule`、`PrometheusAlert` |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 即时查询 | `client.query(promql).execute()` 或 `engine.queryInstant(promql)` | 对应 `/api/v1/query`；`.time(epochSec)` 可做时间旅行查询 |
| 范围查询 | `client.queryRange(promql).lastMinutes(30).step(60).execute()` | 对应 `/api/v1/query_range`；默认最近 1 小时、步长 60 秒 |
| 取首个值 | `client.query(promql).firstValue()` | 返回 `Double`，无数据返回 `null` |
| 序列发现 | `client.series().match("up").list()` | 对应 `/api/v1/series`，返回 `List<Map<String,String>>` |
| 标签名 / 标签值 | `client.labels()` / `client.labelValues("job")` | 对应 `/api/v1/label/names`、`/api/v1/label/{name}/values` |
| 抓取目标 | `client.targets()` | 对应 `/api/v1/targets`，`PrometheusTarget#isUp()` 判断健康 |
| 告警规则 / 当前告警 | `client.rules()` / `client.alerts()` | 对应 `/api/v1/rules`、`/api/v1/alerts` |
| 多实例管理 | `engine.addDataSource(name, url)` + `engine.client(name)` | 按名称管理多个 Prometheus 实例 |

### 2.2 PrometheusClient

```java
PrometheusClient client = PrometheusClient.builder()
        .baseUrl("http://localhost:9090")   // 默认 http://localhost:9090，自动去尾部斜杠
        .timeoutMs(5000)                    // 默认 5000 毫秒，必须大于 0
        .basicAuth("user", "pass")          // 可选，HTTP Basic 认证
        .build();

// 即时查询：链式设置评估时间点（可选），执行后返回 QueryResult
QueryResult result = client.query("up").execute();
QueryResult at    = client.query("up").time(1700000000L).execute();
Double first      = client.query("up").firstValue();       // 首个值，无数据返回 null
PrometheusMetric m = client.query("up").first();           // 首个序列，无数据返回 null

// 范围查询：默认 start=now-1h、end=now、step=60s
QueryResult range = client.queryRange("up")
        .lastMinutes(30)              // 或 .range(startSec, endSec)
        .step(60)
        .execute();

// 序列 / 标签
List<Map<String, String>> series = client.series().match("up").list();
List<String> labelNames = client.labels();
List<String> jobValues  = client.labelValues("job");

// 目标 / 规则 / 告警
List<PrometheusTarget> targets = client.targets();
List<PrometheusRule> rules     = client.rules();
List<PrometheusAlert> alerts   = client.alerts();

client.baseUrl();     // 规整后的基础地址
client.isClosed();    // 是否已关闭
client.close();       // 幂等关闭，释放 JDK HttpClient 线程资源
```

### 2.3 PrometheusEngine（SPI 名 `prometheus`）

```java
PrometheusEngine engine = new PrometheusEngine();

// 四种添加数据源方式
engine.addDataSource("prod", "http://prometheus.prod:9090");                    // 地址字符串
engine.addDataSource("test", "http://prometheus.test:9090", "user", "pass");    // 地址 + Basic 认证
engine.addDataSource("cli", prometheusClient);                                  // 已有客户端
engine.addDataSource("ds", engineDataSource);                                   // EngineDataSource 封装

engine.setDefaultDataSourceName("prod");   // 设置默认数据源
QueryResult r  = engine.queryInstant("up");                 // 即时查询（走默认数据源）
PrometheusClient prod = engine.client("prod");              // 取指定实例客户端
Set<String> names = engine.dataSourceNames();               // 已注册名称快照

engine.close();       // 幂等关闭，逐个释放数据源底层客户端
engine.isClosed();
```

### 2.4 查询结果模型

| 类型 | 关键字段 / 方法 | 说明 |
| --- | --- | --- |
| `QueryResult` | `getResultType()`、`getResult()`、`hasData()` | `resultType` 为 `vector` / `matrix` / `scalar` |
| `PrometheusMetric` | `getMetric()`（标签 Map，含 `__name__`）、`getValue()`（vector 即时值）、`getTimestamp()`（epoch 秒）、`getValues()`（matrix 采样列表）、`getName()`、`getLabel(key)` | 内嵌 `record Sample(long timestamp, double value)` |
| `PrometheusTarget` | `getScrapeUrl()`、`getJob()`、`getInstance()`、`getHealth()`（up/down/unknown）、`getLastError()`、`getLastScrape()`（epoch 毫秒）、`getScrapeDuration()`（秒）、`isUp()` | 对应 `activeTargets` 元素 |
| `PrometheusRule` | `getName()`、`getQuery()`、`getType()`（alerting/recording）、`getState()`（firing/pending/inactive）、`getRuleGroup()`、`getRuleFile()`、`getHealth()`、`getDuration()`（for 阈值秒）、`getEvaluationTime()`（秒）、`getLastEvaluation()`（epoch 毫秒）、`getLabels()`、`getAnnotations()`、`isAlerting()` | 对应 `/api/v1/rules` 的 rules 元素 |
| `PrometheusAlert` | `getLabels()`、`getAnnotations()`、`getState()`（firing/pending/inactive）、`getValue()`（字符串，可能为 NaN/+Inf）、`getActiveAt()`（epoch 毫秒）、`getAlertName()`（取 `labels.alertname`）、`getSeverity()`（取 `labels.severity`）、`getLabel(key)` | 对应 `/api/v1/alerts` 的 alerts 元素 |

### 2.5 失败语义

| 异常 | 触发场景 |
| --- | --- |
| `RemoteExecutionException`（`com.chua.common.support.exception`） | 连接超时、HTTP 非 2xx、响应非 JSON、Prometheus 返回 `status=error`、响应缺少 `data`、值对元素不足、时间/数值字段无法解析 |
| `IllegalArgumentException` | 入参不合法：地址为空/非 http(s)/缺主机名、超时不大于 0、promql 为空、标签名不合法（需匹配 `[a-zA-Z_][a-zA-Z0-9_]*`）、范围查询 `start > end` 或 `step <= 0`、series 未指定 match、数据源名为空等 |
| `IllegalStateException` | 客户端或引擎已关闭后调用、引擎未注册任何数据源就取客户端 |
| `UnsupportedOperationException` | 不支持的 `resultType`（仅支持 vector/matrix/scalar）、`store`/`execute`/Lambda 查询更新删除/`meta` 等不支持的能力 |

说明：只有语义上允许为空的字段（远端未返回可选字段、查询无匹配序列）才返回 `0` 或空集合，例如时间字段缺失返回 `0` 表示远端未返回；数值/时间字段存在却无法解析时显式抛异常，不静默归零。

---

## 3. 快速开始

### 3.1 纯 Java 客户端（最短可运行示例）

```java
import com.chua.prometheus.support.client.PrometheusClient;
import com.chua.prometheus.support.model.PrometheusMetric;
import com.chua.prometheus.support.model.QueryResult;

public class Demo {
    public static void main(String[] args) {
        try (PrometheusClient client = PrometheusClient.builder()
                .baseUrl("http://localhost:9090")
                .timeoutMs(5000)
                .build()) {

            // 即时查询：所有实例的 up 值
            QueryResult result = client.query("up").execute();
            for (PrometheusMetric metric : result.getResult()) {
                System.out.println(metric.getName() + " " + metric.getMetric() + " = " + metric.getValue());
            }

            // 范围查询：最近 30 分钟，步长 60 秒
            QueryResult range = client.queryRange("sum(up)").lastMinutes(30).step(60).execute();
            System.out.println("resultType=" + range.getResultType() + ", hasData=" + range.hasData());

            // 抓取目标与告警
            client.targets().forEach(t -> System.out.println(t.getJob() + " " + t.getInstance() + " up=" + t.isUp()));
            client.alerts().forEach(a -> System.out.println(a.getAlertName() + " " + a.getState()));
        }
    }
}
```

### 3.2 Spring Boot 自动装配

引入本模块后，Spring Boot 自动注册 `PrometheusClient` 与 `PrometheusEngine`（引擎内置名为 `default` 的数据源）：

```yaml
prometheus:
  url: http://localhost:9090
  username: admin          # 可选
  password: admin123       # 可选
  timeout-ms: 5000
```

```java
@Service
public class MonitorService {

    private final PrometheusClient client;

    public MonitorService(PrometheusClient client) {
        this.client = client;
    }

    public double upCount() {
        Double first = client.query("sum(up)").firstValue();
        return first == null ? 0.0 : first;
    }
}
```

### 3.3 多实例（Engine）

```java
try (PrometheusEngine engine = new PrometheusEngine()) {
    engine.addDataSource("prod", "http://prometheus.prod:9090")
          .addDataSource("test", "http://prometheus.test:9090")
          .setDefaultDataSourceName("prod");

    QueryResult prodUp = engine.queryInstant("up");   // 走 prod
    PrometheusClient test = engine.client("test");    // 手动取 test 实例
}
```

---

## 4. 配置项

前缀 `prometheus`（`PrometheusProperties`）：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `prometheus.enabled` | `boolean` | `true` | 是否启用自动装配；`false` 时不注册 `PrometheusClient` 与 `PrometheusEngine` |
| `prometheus.url` | `String` | `http://localhost:9090` | 服务地址，必须包含 http/https 协议；尾部斜杠可有可无（自动规整） |
| `prometheus.username` | `String` | 无 | Basic 认证用户名（可选） |
| `prometheus.password` | `String` | 无 | Basic 认证密码（可选，配合 username 使用） |
| `prometheus.timeoutMs` | `int` | `5000` | 请求超时（毫秒），同时用于连接超时与单次请求读取超时，必须大于 0 |

补充：

- `timeoutMs` 为 Java 字段名，Spring Boot 宽松绑定下 `prometheus.timeout-ms` 与 `prometheus.timeoutMs` 等价。
- 自动装配时若 `prometheus.url` 为空、超时不大于 0 等配置非法，会在**启动阶段直接抛出**，不延后到首次查询。
- 除上述配置外，本模块无其它配置项；`PrometheusProperties` 仅含 `enabled`、`url`、`username`、`password`、`timeoutMs` 五个字段。

---

## 5. SPI 扩展点

### 5.1 Engine SPI

`PrometheusEngine` 标注 `@Spi("prometheus")`，通过 `META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine` 注册：

```text
prometheus=com.chua.prometheus.support.engine.PrometheusEngine
```

由 common-starter 的 SPI 体系按名称 `prometheus` 装配为 `Engine` 实现；也可以像上文「快速开始」那样直接 `new PrometheusEngine()` 使用。

### 5.2 Spring Boot 自动装配

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册了：

```text
com.chua.prometheus.support.config.PrometheusAutoConfiguration
```

- `@ConditionalOnProperty(prefix = "prometheus", name = "enabled", havingValue = "true", matchIfMissing = true)`：默认启用，`prometheus.enabled=false` 可整体关闭。
- 两个 Bean（`PrometheusClient`、`PrometheusEngine`）均标注 `@ConditionalOnMissingBean` 且声明 `destroyMethod = "close"`：可自定义同名 Bean 覆盖默认实现，容器关闭时自动释放底层 HTTP 客户端线程。
- 引擎默认注册名为 `default` 的数据源，指向配置的 `prometheus.url`。

### 5.3 GraalVM Native Image

`META-INF/native-image/com.chua/utils-support-prometheus-starter/` 内置：

- `reflect-config.json`：注册本模块全部类型（引擎、客户端、数据源、配置、5 个模型类及 `PrometheusMetric$Sample`）的反射元数据；
- `resource-config.json`：包含 `META-INF/extensions/.*`，保证 SPI 声明文件在 native 镜像中可用。

---

## 6. 构建与依赖

### 6.1 依赖清单（pom.xml）

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | JSON（`Json`）、异常（`RemoteExecutionException`）、集合（`Wraps`）、`StringUtils` 等基础能力 |
| `com.chua:utils-support-datasource-starter` | compile | `Engine` / `EngineDataSource` / `Dialect` SPI 契约 |
| `org.springframework.boot:spring-boot-autoconfigure` | compile | `@AutoConfiguration`、`@ConfigurationProperties`、条件注解 |
| `org.springframework.boot:spring-boot-configuration-processor` | provided | 配置元数据生成 |
| `org.projectlombok:lombok` | provided | 样板代码 |

### 6.2 编译

在 `utils-support-parent-starter` 仓库根目录执行：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 本模块行为 |
| --- | --- |
| SQL 执行 | 不支持。`PrometheusEngine#getExecutor(...)` 恒返回 `null`（已 `@Deprecated`，请用 `supportsSql()` 判断能力），`getDialect(...)` 恒返回 `null` |
| 元数据 | 不支持。`supportsMeta()` 恒为 `false`，`meta()` 直接抛 `UnsupportedOperationException` |
| 数据写入 | 不支持。`store(...)`、`execute(...)` 抛 `UnsupportedOperationException`——Prometheus 为只读数据源 |
| Lambda 增删改查 | 不支持。`query(Class)` / `update(Class)` / `delete(Class)` 抛 `UnsupportedOperationException`，查询请改用 `queryInstant(promql)` |
| 其它 resultType | 查询结果仅支持 `vector` / `matrix` / `scalar`，其余类型抛 `UnsupportedOperationException` |
| 指标采集与暴露 | 不提供。本模块只是 Prometheus 的查询客户端，不含埋点、Micrometer 桥接或 `/metrics` 端点 |
| Prometheus Server 管理 | 不提供。本模块不负责 Prometheus Server 的安装、启停与配置管理 |
