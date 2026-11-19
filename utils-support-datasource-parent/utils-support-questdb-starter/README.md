# utils-support-questdb-starter

> QuestDB 时序数据库集成模块：统一时序契约 `SeriesEngine` 的 QuestDB 实现（SPI 别名 `questdb`）。写入基于 QuestDB 官方 Java 客户端 `Sender`（InfluxDB Line Protocol over HTTP），查询 / 指标发现 / 保留期清理经 QuestDB HTTP REST `/exec` 端点执行 SQL。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `java.version=25`）｜包名：`com.chua.questdb.support.engine`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. 实现机制](#5-实现机制)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-questdb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 实现 | `SeriesEngine` SPI 的 QuestDB 实现（`QuestdbSeriesEngine`，`@Spi("questdb")`），实现 `AutoCloseable` | 不是 QuestDB 服务端，不包含安装、部署、运维能力 |
| 写入 | ILP over HTTP（官方 `Sender`，`/write?precision=n`），measurement = `target`，tag = `monitor_id` | 不提供 JDBC / SQL 写入通道 |
| 查询 | HTTP REST `/exec` 执行 SQL，结果按 JSON 解析 | 不含 SQL 方言转换层、ORM 或查询构建器 |
| 依赖 | 只依赖 `utils-support-common-starter`、`utils-support-datasource-starter`（provided）、`org.questdb:questdb`、`jackson-databind` | 不依赖 Spring，不提供自动装配 |

**判断口径**：需要 QuestDB 作为监控时序后端时引入本模块；时序契约与其它后端（DataSource / Redis / InfluxDB 等）的实现分别在 `utils-support-common-starter` 与各自 starter 中。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎 | `ServiceProvider.of(SeriesEngine.class).getNewExtension("questdb", props)` | `props` 至少提供 `url`；每次新建实例 |
| 写入单点 | `engine.writePoint(target, monitorId, metricName, value, timestampMillis)` | 逐点同步刷写，失败抛异常，绝不静默丢点 |
| 批量写入 | `engine.writeBatch(target, monitorId, metricsMap, timestampMillis)` | 接口默认实现，逐个委托 `writePoint` |
| 指标发现 | `engine.metrics(target, monitorId)` | 返回该目标下已写入的数值指标名，字典序升序 |
| 均值曲线 | `engine.series(target, monitorId, metricName, hours, window)` | `window` 如 `5m`；Java 侧窗口均值降采样，最多返回 1440 点 |
| 保留期清理 | `engine.purge(target, monitorId, metricName, beforeMillis)` | 删除该毫秒时间戳之前的数据；返回 `-1` 表示已清理但条数不可统计 |
| 引擎状态 | `engine.engine()` / `engine.isAvailable()` | 引擎名 `"questdb"`；是否处于可接受请求的状态（不探测后端实时可达性） |
| 释放资源 | `engine.close()` | 关闭 `Sender` 与 `HttpClient`；关闭后拒绝后续读写 |

---

## 3. 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-questdb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 通过 SPI 获取引擎并读写

```java
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.ServiceProvider;

Properties props = new Properties();
props.setProperty("url", "http://127.0.0.1:9000");   // 必填，QuestDB HTTP 地址

SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("questdb", props);

// 写入单点：target = 表名，monitorId = tag，metricName = 数值列
engine.writePoint("ms_monitor_metric_cpu", 1L, "cpu_usage", 25.5, System.currentTimeMillis());

// 查询近 1 小时、5m 窗口的均值曲线，返回 [[ts, value], ...] 升序
List<List<Object>> curve = engine.series("ms_monitor_metric_cpu", 1L, "cpu_usage", 1, "5m");

// 用完释放底层资源
engine.close();
```

### 3. 在 monitor 中启用

引擎标识与配置 `monitor.timeseries.engine` 对应，将其置为 `questdb` 即可选择本实现；monitor 侧的具体装配类与其它配置键名源码未提供。

---

## 4. 配置项

通过 SPI 的 `Properties` 传入（构造器 `QuestdbSeriesEngine(Properties)`），也支持四参构造器 `QuestdbSeriesEngine(url, token, username, password)`：

| 属性 | 必填 | 说明 |
| --- | --- | --- |
| `url` | 是 | QuestDB HTTP 地址，如 `http://127.0.0.1:9000`；未写 scheme 时补 `http://`，末尾多余 `/` 会被去除；未写端口时 Sender 侧按 `9000` 处理 |
| `token` | 否 | HTTP 鉴权 token：写入走 `Sender` 的 token 配置，`/exec` 读路径带 `Authorization: Bearer <token>` |
| `username` / `password` | 否 | HTTP 基本鉴权，成对使用：写入走 `Sender` 的用户名密码配置，`/exec` 读路径带 `Authorization: Basic base64(用户名:密码)` |
| `database` | 否 | **被忽略**：QuestDB 以表为存储单元，没有 InfluxDB 式的数据库概念；保留该属性仅为兼容 monitor 的统一装配路径，不参与寻址 |

未配置 `url` 或无法从 `url` 解析出主机地址时，引擎标记为不可用（`isAvailable()` 返回 `false`），此时写入抛 `IllegalStateException`，查询 / 清理返回空结果或 `0`。

---

## 5. 实现机制

### 5.1 写入（writePoint / writeBatch）

- 使用 QuestDB 官方 `Sender`（`Sender.Transport.HTTP`），`address(host:port)` + `autoFlushRows(1)`，内部走 `/write?precision=n`。
- 映射关系：measurement = `target`，tag 列 = `monitor_id`，每个指标名一个数值列（`doubleColumn`），时间戳按毫秒写入；设计时间戳列由 QuestDB 自动创建。
- 每次写入在 `writeLock` 内串行执行并同步 `flush()`，保证失败可见、不静默丢点；`Sender` 非线程安全，且与 `close()` 互斥。
- 写入失败：记录错误日志、重置 `Sender`（下次写入重建），并抛 `IllegalStateException`。
- 参数校验：`target` / `monitorId` / `metricName` 必填；`target` 与 `metricName` 经 `SqlName.isWord` 白名单校验，非法标识符抛 `IllegalArgumentException`。
- `writeBatch` 是 `SeriesEngine` 接口的 default 方法，逐个委托 `writePoint`，失败语义与其一致。

### 5.2 曲线查询（series）

- 经 `/exec?query=<URL 编码 SQL>` 执行 `SELECT "时间戳列", "指标列" FROM "target" WHERE "monitor_id" = ? AND 时间范围`。
- 请求超时 30 秒、连接超时 10 秒；结果按 JSON 解析（Jackson），TIMESTAMP 列以 ISO-8601 字符串返回，个别版本可能返回 epoch 微秒数字，两者均兼容。
- 指标维度是强约束：`metricName` 为 `null` 时按「唯一性规则」推断——该 `(target, monitorId)` 下恰好一个指标才继续，无指标返回空列表，多个指标抛 `IllegalArgumentException` 并列出候选。
- 曲线在 Java 侧按统一窗口均值降采样（`SeriesEngine.downsample`），与 DataSource / Redis / InfluxDB 实现保持同一聚合语义；单次返回点数上限 1440，超出时等距抽样。
- `hours` 经 `SeriesEngine.requirePositiveHours` 校验，非正数直接抛 `IllegalArgumentException`，不静默改写。

### 5.3 指标发现（metrics）

- 对 `target`（可限定 `monitorId`）执行 `SELECT * ... LIMIT 1` 探测表结构样本。
- 指标列必须同时满足：类型为数值类型（`DOUBLE` / `FLOAT` / `LONG` / `INT` / `SHORT` / `BYTE`），且样本行中该列值非空（避免把从未写入的列报成指标）。
- 返回按字典序升序；表不存在或引擎不可用时返回空列表。

### 5.4 保留期清理（purge）

- 执行 `DELETE FROM "target" WHERE "时间戳列" < 'ISO 时间'`，`monitorId` 非空时追加 `AND "monitor_id" = ?`。
- QuestDB 的 DELETE 不返回受影响行数，因此本实现如实返回 `-1`（已清理、无法精确统计），绝不返回 `0`——返回 `0` 会让调用方误以为「后端不支持清理」而放弃保留期策略。
- 表不存在（错误信息含 `does not exist`）按「无可清理数据」处理，返回 `0` 以外的语义不变：此处返回 `0` 表示没有可清理的数据。

### 5.5 时间戳列解析

按以下顺序确定设计时间戳列名：

1. `/exec` 响应中的 `timestamp` 下标（QuestDB 返回的设计时间戳列位置）；
2. 列元数据中首个 `TIMESTAMP` 类型列；
3. 以上均读不到时，使用 ILP 默认列名 `timestamp`。

表不存在时直接使用默认名 `timestamp`，让后续查询按「表不存在」的既有语义处理。

### 5.6 错误语义

- 查询失败（网络失败、后端返回错误、HTTP 非 2xx）抛 `IllegalStateException`，不降级为空结果；仅表不存在时按「无数据」容错处理。
- `isAvailable()` 只回答「本引擎是否处于可接受请求的状态」（已配置且未关闭），不探测后端实时可达性；后端宕机时它仍可能返回 `true`，故障可见性由「操作必须抛异常」保证。

---

## 6. SPI 扩展点

本模块是 `SeriesEngine` SPI 的**实现方**，不是扩展点提供方。

**注册声明**（`src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.series.SeriesEngine`）：

```text
questdb=com.chua.questdb.support.engine.QuestdbSeriesEngine
```

**实现类**：`com.chua.questdb.support.engine.QuestdbSeriesEngine`，标注 `@Spi("questdb")`。

**获取方式**：

```java
SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("questdb", props);
```

**契约方法**（定义于 `utils-support-common-starter` 的 `com.chua.common.support.lang.datasource.series.SeriesEngine`）：

| 方法 | 本模块行为 |
| --- | --- |
| `engine()` | 返回 `"questdb"` |
| `isAvailable()` | 已配置且未关闭时为 `true` |
| `writePoint(...)` | 覆写：ILP over HTTP 同步写入 |
| `writeBatch(...)` | 不覆写，走接口 default（逐个委托 `writePoint`） |
| `metrics(...)` | 覆写：`/exec` 探测数值列 |
| `series(...)` | 覆写：`/exec` 查询 + Java 侧降采样 |
| `purge(target, monitorId, metricName, beforeMillis)` | 覆写：`/exec` 执行 DELETE，返回 `-1` |
| `purge(target, monitorId, beforeMillis)` | 不覆写，接口 default 委托到带 `metricName` 的重载 |
| `close()` | 覆写：标记关闭态后释放 `Sender` 与 `HttpClient` |

如需接入新的时序后端，应另建 starter 实现 `SeriesEngine` 并在自己的 `META-INF/extensions/` 注册文件中登记别名，不要修改本模块。

---

## 7. 构建与依赖

依赖树：

```text
utils-support-questdb-starter
├── com.chua:utils-support-common-starter          // SeriesEngine 契约、SPI、工具
├── com.chua:utils-support-datasource-starter      // provided，不传递
├── org.questdb:questdb 8.0.0                      // 官方客户端，内含 ILP over HTTP/TCP Sender，无运行期传递依赖
└── com.fasterxml.jackson.core:jackson-databind    // 解析 /exec 返回 JSON，版本由 spring-boot-dependencies BOM 统一管理
```

- `questdb` 客户端版本 `8.0.0` 由仓库根 POM 的 `dependencyManagement` 统一管理。
- Surefire 已配置 `forkedProcessExitTimeoutInSeconds=15`、`surefire.timeout=120`：QuestDB `Sender` / JDK `HttpClient` 存在非守护线程，测试完成后需强制回收 fork 进程。

构建命令（在本模块目录或仓库根目录执行）：

```bash
mvn -o -q compile -DskipTests
```

---

## 8. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| `SeriesEngine` 契约、降采样与窗口解析、`requirePositiveHours` 等静态工具 | `utils-support-common-starter` |
| DataSource / Redis / InfluxDB 等其它时序后端实现 | 各自的 starter 模块 |
| JDBC / 连接池 / 多数据源等数据源能力 | `utils-support-datasource-*` 各模块 |
| Spring 自动装配、monitor 侧装配逻辑与配置绑定 | 源码未提供（本模块无 Spring 依赖） |
| QuestDB 服务端安装、部署、账号与权限管理 | QuestDB 本身，不在本模块范围 |
| ILP over TCP 等其它写入传输 | 本模块仅使用 `Sender.Transport.HTTP` |
| 写入侧批量异步缓冲 / 攒批提交 | 本模块固定 `autoFlushRows(1)` 逐点同步刷写 |
