# utils-support-skywalking-starter

> Apache SkyWalking **可观测性数据源**模块：实现 common 层的 `OtnlEngine` SPI（名称 `skywalking`），通过 OAP 的 HTTP GraphQL 接口查询服务 / 实例 / 端点 / 链路 / 拓扑 / 指标 / 告警 / 日志 / 性能剖析数据。**不引入任何 `org.apache.skywalking` 官方依赖**。
>
> 版本：`4.0.0.51`｜JDK：`25`（根仓 `maven.compiler.release=25`）｜包名：`com.chua.skywalking.support`

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
    <artifactId>utils-support-skywalking-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

所属父模块：`utils-support-middleware-parent`（中间件集成父模块）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 数据面 | SkyWalking OAP 的 **HTTP GraphQL 查询客户端**（默认 `http://127.0.0.1:12800`，端点路径 `/graphql`） | 不是 SkyWalking Agent，不做埋点、采样、数据上报 |
| 引擎 | `OtnlEngine` 的只读 SPI 实现（SPI 名 `skywalking`） | 不是可写数据源：`addDataSource` / `store` / `insert` 一律拒绝 |
| 依赖 | 只依赖 JDK `HttpClient` + Jackson + `utils-support-common-starter` + `spring-boot-autoconfigure` | 不依赖 SkyWalking 官方 jar，不会与 agent 版本打架 |
| 装配 | Spring Boot 自动配置 + SPI 惰性创建**双通道** | 不注册 `OtnlEngine` Bean（避免「两个实例、两份 HTTP 连接池」） |
| 解析策略 | GraphQL 响应「宽容解析」：按字段名取 `JsonNode`，不生成强类型 POJO | 不锁死 OAP schema；9.x / 10.x / 11.x 字段增删只导致单个字段取不到，不让整条查询炸掉 |

### 1.3 模块规模

- Java 源文件 **9** 个，分三个子包：
  - `client`（6 个）：`SkywalkingOapClient`、`SkywalkingQueries`、`SkywalkingSettings`、`SkywalkingTimes`、`SkywalkingVersion`、`SkywalkingException`
  - `config`（2 个）：`SkywalkingAutoConfiguration`、`SkywalkingProperties`
  - `engine`（1 个）：`SkywalkingOtnlEngine`
- SPI 登记：`src/main/resources/META-INF/extensions/com.chua.common.support.apm.OtnlEngine`
- 自动配置登记：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿 APM 引擎（推荐） | `ServiceProvider.of(OtnlEngine.class).getExtension("skywalking")` | SPI 惰性创建，连接参数来自 `SkywalkingSettings` |
| 直连 OAP | `new SkywalkingOapClient(endpoint, username, password, timeoutMs)` | 无参构造则读进程级设置 |
| 执行 GraphQL | `client.data(query)` / `client.data(query, variables)` | 返回 `data` 节点（`JsonNode`）；网络失败 / 非 2xx / GraphQL errors 抛 `SkywalkingException` |
| 探测 OAP 版本 | `client.version()`（容错，失败返回 `unknown`）/ `client.versionChecked()`（失败抛异常） | 查询 `query { version }` |
| 构建 `Duration` 变量 | `SkywalkingTimes.duration(start, end, step)` | 生成 `{start, end, step}` 变量 Map |
| GraphQL 语句常量 | `SkywalkingQueries.SERVICES` / `BASIC_TRACES` / `ALARMS` 等 | 与 apache/skywalking-query-protocol 对齐，含 legacy 兜底语句 |
| 版本能力判断 | `SkywalkingVersion.parse(text).supportsNodeLayers()` 等 | 按版本直接选对语句，避免「先发一条必然报错的查询再回落」 |
| 引擎健康 | `engine.status()` | 返回 `OtnlStatus`：可用性、OAP 版本与地址、失败原因（带缓存） |

### 2.2 引擎能力清单

`SkywalkingOtnlEngine.capabilities()` 返回：`services`、`instances`、`endpoints`、`traces`、`topology`、`metrics`、`runtime`、`alarms`、`alerts`、`logs`、`profiles`。

主要查询方法（均实现 / 覆盖自 `OtnlEngine`）：

| 方法 | 对应 OAP GraphQL |
| --- | --- |
| `services(OtnlQuery)` | `listServices`（9.x metadata v2），失败回落 `getAllServices`（8.x v1） |
| `instances(serviceName, OtnlQuery)` | `listInstances`，回落 `getServiceInstances` |
| `endpoints(serviceName, OtnlQuery)` | `findEndpoint`，回落 `searchEndpoint` |
| `traces(OtnlQuery)` / `trace(traceId)` | `queryBasicTraces` / `queryTrace` |
| `topology(OtnlQuery)` | `getGlobalTopology` / `getServiceTopology(ByName)` |
| `metrics(OtnlMetricQuery)` | metrics-v2 `readMetricsValues`（单值聚合用 `readMetricsValue`） |
| `alarms(OtnlQuery)` | 11.0.0 起用 `queryAlarms`，更早用 `getAlarm` |
| `logs(OtnlQuery)` | `queryLogs` |
| `health(OtnlQuery)` | 服务数 + 告警数 + `all_p99` 聚合（SkyWalking 无全局端点/QPS 指标） |
| `profileTasks` / `profiledSegments` / `profileAnalysis` | `getProfileTaskList` / `getProfileTaskSegments` / `getSegmentsProfileAnalyze` |
| `multiTopology(serviceNames, OtnlQuery)` | 11.0.0 起 `getServicesTopologyByNames` 一次请求；更早回落逐个服务查再合并 |

### 2.3 关键行为语义（读源码前必读）

- **降级约定**：任何后端异常在方法内吞掉并返回空集合，原因通过 `status()` 暴露；`SkywalkingException` 绝不冒泡到 Web 层把整页打成 500。
- **列表补齐上限**：列表页的聚合列（调用量 / 平均耗时 / P99 / 错误率）需逐实体查询，只为前 **20** 条补齐；拓扑边最多补齐 **40** 条；补齐线程池并发度 **4**（线程名前缀 `apm-skywalking-`）。
- **指标窗口切片**：OAP 对单次 `readMetricsValues` 有 **500 个步长**的硬上限（超出整条查询失败，不是截断），引擎按窗口自动切片再合并。「最近 24 小时 + 分钟级」= 1440 点，必然触发切片。
- **`normal=true` 必须显式携带**：OAP 的 `Entity` 缺省命中「非正常实体」，指标安静地返回 0 且不报错。
- **版本能力**（GraphQL 校验是整条查询全有或全无，选错字段整页无数据）：`Node.layers` 10.0.0、`AlarmMessage.name` 10.2.0、`queryAlarms` / `getServiceTopologyByName` / `recoveryTime` 11.0.0。版本解析不出时按**最低能力**处理。
- **时间格式**：OAP 的 `Duration` 不是时间戳，而是按步长格式化的字符串（OAP 服务端本地时区；本模块按查询方 JVM 默认时区格式化）：

| Step | 格式 | 示例 |
| --- | --- | --- |
| `SECOND` | `yyyy-MM-dd HHmmss` | `2024-05-01 103055` |
| `MINUTE` | `yyyy-MM-dd HHmm` | `2024-05-01 1030` |
| `HOUR` | `yyyy-MM-dd HH` | `2024-05-01 10` |
| `DAY` | `yyyy-MM-dd` | `2024-05-01` |

- 其它事实：SLA 指标满值 `10000 = 100%`；链路 `BasicTrace.start` 是 epoch 毫秒（与指标 id 里的紧凑时间桶不是一回事）；Span 的 `serviceCode` 是 `base64(服务名) + "." + layerId`，引擎会解码还原服务名。

---

## 3. 快速开始

### 3.1 Spring Boot 环境（自动配置）

1. 引入依赖（坐标见 1.1）。
2. 配置 `application.yml`：

```yaml
skywalking:
  url: http://127.0.0.1:12800
  # username: admin        # OAP 开启 Basic 鉴权时填写
  # password: admin
  timeout: 5000            # 单次查询超时（毫秒）
  enabled: true
```

3. 启动时 `SkywalkingAutoConfiguration` 会把 `skywalking.*` 写入进程级设置 `SkywalkingSettings`，并在开启时打印 `[SkyWalking] OAP 地址: ...`。
4. 业务代码通过 SPI 拿引擎查询（引擎由 SPI 惰性创建，**不要**自己再注册 `OtnlEngine` Bean）：

```java
import com.chua.common.support.apm.OtnlEngine;
import com.chua.common.support.apm.OtnlQuery;
import com.chua.common.support.apm.model.OtnlService;
import com.chua.common.support.spi.ServiceProvider;

OtnlEngine engine = ServiceProvider.of(OtnlEngine.class).getExtension("skywalking");

// 最近 30 分钟的服务列表（含调用量 / 平均耗时 / P99 / 错误率聚合列）
List<OtnlService> services = engine.services(OtnlQuery.lastMinutes(30));

// 引擎健康与版本
System.out.println(engine.status().version());
```

### 3.2 纯 SPI / 无 Spring 环境

`SkywalkingSettings` 回落到系统属性与环境变量（优先级：系统属性 `skywalking.url` > 环境变量 `SW_OAP_ADDRESS` > 默认 `http://127.0.0.1:12800`；用户名 / 密码对应 `SW_OAP_USERNAME` / `SW_OAP_PASSWORD`）：

```java
// 方式一：写进程级设置（volatile，HTTP 请求间即时生效，不需重启）
SkywalkingSettings.apply("http://10.0.0.10:12800", "", "", 5000, true);

try (SkywalkingOapClient client = new SkywalkingOapClient()) {
    String version = client.version();                       // 容错版，失败返回 "unknown"
    JsonNode data = client.data(SkywalkingQueries.SERVICES); // 直接执行 GraphQL
}

// 方式二：构造时显式给连接参数
SkywalkingOapClient client = new SkywalkingOapClient("http://10.0.0.10:12800", "admin", "admin", 5000);
```

> 地址会先做规范化：去尾部斜杠；若误带了 `/graphql` 后缀会自动剥离。

---

## 4. 配置项

前缀 `skywalking`（`SkywalkingProperties`）：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `skywalking.enabled` | `boolean` | `true` | 是否启用 SkyWalking 数据源。置 `false` 时自动配置仍会进容器（无 `@ConditionalOnProperty`），把「用户主动关闭」写进设置，前端因此能区分「没部署」和「被关掉」 |
| `skywalking.url` | `String` | `http://127.0.0.1:12800` | OAP HTTP 地址（不含 `/graphql`） |
| `skywalking.username` | `String` | 空串 | OAP Basic 鉴权用户名（未开启鉴权留空） |
| `skywalking.password` | `String` | 空串 | OAP Basic 鉴权密码（未开启鉴权留空） |
| `skywalking.timeout` | `long` | `5000` | 单次查询超时（毫秒） |

无 Spring 环境的等价入口：

| 来源 | 键 | 说明 |
| --- | --- | --- |
| 系统属性 | `skywalking.url` | 优先级最高 |
| 环境变量 | `SW_OAP_ADDRESS` | 与 SkyWalking 官方 agent 同名；支持 `host:port` 形式（自动补 `http://`） |
| 环境变量 | `SW_OAP_USERNAME` / `SW_OAP_PASSWORD` | Basic 鉴权 |

**就这些。** 本模块只负责「怎么连 OAP」的连接参数；APM 模块的整体启停由上层 `plugin.apm.*` 决定，不属于本模块的配置项。

---

## 5. SPI 扩展点

- **本模块实现的 SPI**：`com.chua.common.support.apm.OtnlEngine`（接口与 `OtnlQuery` / `OtnlMetricQuery` / `OtnlScope` / `OtnlTag` / `OtnlTraceState` 及 `Otnl*` 模型都在 `utils-support-common-starter`）。
- 登记文件 `src/main/resources/META-INF/extensions/com.chua.common.support.apm.OtnlEngine`：

```text
skywalking=com.chua.skywalking.support.engine.SkywalkingOtnlEngine
```

- 实现类标注 `@Spi("skywalking")`，由 `ServiceProvider.of(OtnlEngine.class).getExtension("skywalking")` 惰性创建；`SkywalkingAutoConfiguration` **不**注册 `OtnlEngine` Bean，只把配置写进 `SkywalkingSettings`，让 SPI 引擎读到同一份连接参数。
- **本模块不定义新的 SPI 扩展点**：模块内 9 个类均为具体实现 / 工具，没有留出供第三方实现的接口。想接别的 APM 后端，请在自己的模块实现 `OtnlEngine` 并用别的 SPI 名登记。

---

## 6. 构建与依赖

依赖（`pom.xml`）：

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `OtnlEngine` SPI、`ServiceProvider`、`StringUtils` 等契约与工具 |
| `com.fasterxml.jackson.core:jackson-databind` | compile | OAP GraphQL 响应的宽容解析（不生成强类型 POJO） |
| `org.springframework.boot:spring-boot-autoconfigure` | compile | 运行时反射 `@AutoConfiguration` / `@ConfigurationProperties` |
| `org.springframework.boot:spring-boot-configuration-processor` | provided | 配置元数据生成 |
| `org.projectlombok:lombok` | provided | `@Getter` / `@Setter` / `@Slf4j` |

编译与验证：

```bash
mvn -o -q compile -DskipTests
```

> 本模块没有任何 SkyWalking 官方依赖，也不需要本地部署 agent；只要网络可达 OAP 的 HTTP 端口（默认 12800）即可工作。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去哪找 |
| --- | --- |
| 埋点、采样、链路上报（Java agent / 探针） | SkyWalking 官方 agent；本模块只是**查询端** |
| `OtnlEngine` 接口、`OtnlQuery` / `OtnlMetricQuery` 等查询条件与 `Otnl*` 结果模型 | `utils-support-common-starter`（`com.chua.common.support.apm`） |
| OpenTelemetry 数据源 | `utils-support-opentelemetry-starter`（同父模块） |
| APM 模块的整体启停、`plugin.apm.*` 配置 | 上层 Spring APM 模块；本模块只落地 `skywalking.*` 连接参数 |
| `OtnlEngine` Bean 的注册 | **没有**。引擎只经 SPI 惰性创建；自动配置仅同步连接参数到 `SkywalkingSettings` |
| 指标批量查询入口 | OAP metrics-v2 的 `readMetricsValues` 只接受单个 condition（不存在复数签名），`metricsBatch` 走接口默认实现逐条查询，单条失败只丢那一条 |
| 写入 / DDL / 数据源管理 | APM 引擎是只读观测通道：`addDataSource` / `store` / `insert` 抛 `UnsupportedOperationException`，`update` / `delete` 包装器执行时同样拒绝 |
