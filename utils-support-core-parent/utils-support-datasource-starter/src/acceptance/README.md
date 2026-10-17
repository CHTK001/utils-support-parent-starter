# 引擎验收 harness（Engine / KvEngine / SeriesEngine）

本目录是对 `utils-support-*` 各数据引擎的**可复跑验收程序**，不是单元测试框架：
每个程序自带断言、自行写出 UTF-8 报告，并以**进程退出码**表达结果
（`0` = 全部断言通过，非 `0` = 通过数即失败数之和）。

## 为什么放在 `src/acceptance/`

- `src/acceptance/` **不是** Maven 的标准源码根（`src/main/java`），构建时完全忽略，
  因此不污染任何模块产物；沿用 `spring-support-monitor-starter/src/acceptance/` 的既有约定。
- harness 需要 **700 余个 jar** 的完整运行期 classpath（含 spring / lettuce / monitor），
  无法用模块自身的依赖声明表达，故自带 `pom.xml` 仅用于解析 classpath
  （`dependency:build-classpath`），不参与 reactor 构建。
- 历史上这类工具只留在 `.tmp/`，没有版本控制，随会话结束即丢失。**工具必须入库。**

## 九阶段与覆盖范围

| 阶段 | 程序 | 断言数 | 覆盖内容 |
|---|---|---|---|
| 一 | `EngineAcceptance` | 288 | 24 个 SPI 注册引擎的**契约面**：`name/url/dialect/dataSource 注册/能力开关/生命周期/异常语义` |
| 二 | `EngineFunctional` | 132 | 5 个**嵌入式 SQL 引擎**（memory / file / h2 / sqlite / duckdb）的深度功能面：DDL、DML、条件、分页、聚合、类型化查询、事务边界 |
| 三 | `EngineSeriesKv` | 75 | `KvEngine` ×3、`SeriesEngine` ×2，外加 **mysql**（真实 3306）的 SQL 面 |
| 四 | `EngineEmbedded` | 24 | 嵌入式**非 SQL** 引擎：parquet / lucene / nitrite / tablesaw，以及 rocksdb 的文档与全文面 |
| 五 | `EngineGaps` | 39 | 余下子类：`RedisKvEngine`（Spring `StringRedisTemplate`，真实读写）、`InfluxdbSeriesEngine`（无服务，仅契约）、`NoopSeriesEngine`（经 monitor 工厂驱动）+ monitor 装配路径 |
| 六 | `EngineRemaining` | 45 | **非 SPI 注册**的两个公开 `Engine` 实现者：`HttpDatalakeQueryEngine`（只读契约面，不触网）、`ReactorFlywayBridge`（桥接契约 + 真实 SQLite 读写） |
| 七 | `EngineQueryBuilder` | 37 | 新增的 `Engine.query()` **非 ORM SQL 文本构建器**：子句渲染顺序、标识符白名单、参数绑定顺序、守卫，以及在内存 / H2 / SQLite 三个真实引擎上的端到端执行（含 JOIN / GROUP BY / HAVING / VIEW / 分页 / 类型化映射 / 异步终端） |
| 八 | `EnginePrometheus` | 35 | `PrometheusEngine` + `PrometheusClient` 对**本机 HTTP 桩**的真实执行：8 个 `/api/v1/*` 端点、Basic 认证头、scalar/vector/matrix 解析、HTTP 500 / 非 JSON / `status=error` / 不支持 resultType 的失败语义、关闭语义 |
| 九 | `EngineInfluxdb` | 26 | `InfluxdbSeriesEngine` 注入桩客户端后的**引擎逻辑**真实执行：标识符白名单、单点写入行协议（毫秒→纳秒）、指标发现 `SELECT * ... LIMIT 1`、多指标歧义拒绝、结果展平与 RFC3339 时间解析、窗口均值降采样、`DELETE` 清理返回 -1、关闭语义 |

合计 **701 条断言**。

## 子类清单与覆盖状态（权威口径）

Engine / KvEngine / SeriesEngine 的全部实现者及其覆盖情况，见同目录
`DELIVERY-engine.md` 第 2 节。要点：

- **Engine**：SPI 注册的 24 个键对应 24 个类，全部进阶段一（注册文件已逐文件核对，
  与本 harness 的 `KEYS` 完全一致）；**非注册**的两个公开实现者
  （`ReactorFlywayBridge`、`HttpDatalakeQueryEngine`）进阶段六；
  抽象基类 `AbstractEngine` / `JdbcEngine` 经子类间接覆盖；
  `JdbcReactorEngine` 的两个 private 内部适配器无法从外部引用，如实声明未直接触达。
- **KvEngine 4 个**：`ChronicleMapKv` / `RocksDbEngine` / `RedisClient` / `RedisKvEngine` —— 全部覆盖。
- **SeriesEngine 4 个**：`DataSourceSeriesEngine` / `RedisSeriesEngine` / `InfluxdbSeriesEngine` / `NoopSeriesEngine` —— 全部覆盖（influx 无服务，仅契约面）。

### 契约断言的来源

断言**只取自接口 Javadoc 的显式契约**，并优先覆盖「文档写了、实现容易漏」的分支，例如：

- `removeDataSource(null)` 必须抛 `IllegalArgumentException`，不得静默返回；
- `querySql(null)` 必须是 `IllegalArgumentException`，不得是裸 `NullPointerException`；
- 无 `WHERE` 的 `update` / `remove` 必须被拒绝，不得更新或清空整表；
- `SeriesEngine.series(metricName=null)` 在指标不唯一时必须抛 `IllegalArgumentException`；
- `purge` 后 `metrics()` 不得再报告已被删除的指标；
- 带 TTL 的 `put` 与 `supportsTtl()` 必须自洽（覆写了任一 TTL 操作就必须报 `true`）；
- `InfluxdbSeriesEngine` 不可用时 `writePoint` 必须抛 `IllegalStateException`（拒绝静默丢弃），
  而 `series` / `metrics` 返回空列表、`purge` 返回 `0`。

### 能力适配，而非硬编码期望

各引擎能力面不同，断言按其**自身声明**适配：

- `DataSourceSeriesEngine` 是「一表一指标」，多指标断言自动降级为「不适用」；
- `ParquetEngine.supportsSql()==false`，读路径是 `executeNewQuery`（Lambda/类型化面），
  因此用 `query(Doc.class).list()` 断言，**不用** `querySql`；
- `TablesawEngine` 只依赖 `utils-support-common-starter`，读不到
  `com.chua.datasource.support.annotation.TableName`，表名一律由类名派生
  （`Doc` → `doc`），这是该引擎的**设计边界**，不是缺陷；
- `SeriesEngine` 接口本身不继承 `AutoCloseable`，只有具体实现可关闭 —— harness 按实例判定。

## 运行

```bat
cd utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat            :: 九阶段全跑
run-acceptance.bat 1 5        :: 只跑阶段一与阶段五
```

前置条件：

- **pwsh 7** 在 PATH 上（仓库禁用 PowerShell 5.1：它按 ANSI 解码无 BOM 的 UTF-8 脚本，
  会打乱非 ASCII 内容）。`run-acceptance.bat` 会先检查。
- JDK 21+（`javac` / `java` 在 PATH 上）。
- 阶段三 / 五需要本机 `127.0.0.1:3306` 的 MySQL（`root/root`，库 `engine_accept`）与
  `127.0.0.1:6379` 的 Redis。缺服务时对应套件失败，其余阶段不受影响。
- 阶段一 / 五的 `h2` / `spring` 制品来自本地 Maven 仓库，改完产品代码必须先
  `mvn install` 对应模块，否则验的是旧包（**报告 md5 就是为此存在**）。
- `prepare-classpath.ps1` 会从 `pom.xml` 解析 classpath，并在本目录生成 `lib\`
  （硬链接，失败则复制）与 `classes\`。这两者都是**生成物，不入库**。

## 产物与交付纪律

程序自行写出 UTF-8 报告（`*-report.txt`）。报告里的断言数必须与退出码一致；
**退出码比报告可信**——曾出现过程序在写出报告前被外部因素终止、留下上一轮陈旧报告的情形
（阶段三遇过一次不可复现的偶发静默退出，两次复跑均正常）。

本目录的源码是**验收工具**，不是产品代码：修复产品缺陷请在模块 `src/main/java` 内进行，
并用本 harness 回归。本轮由 harness 暴露并修复的产品缺陷清单、产物 md5 绑定、
「查证后判定为非缺陷」的说明，见同目录 `DELIVERY-engine.md`。
