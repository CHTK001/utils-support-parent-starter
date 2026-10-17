# 引擎验收与修复交付报告（Engine / KvEngine / SeriesEngine 全子类）

日期：2026-10-04（阶段六增补与 md5 重绑：2026-10-17；阶段七新增 `Engine.query()` 构建器：2026-10-17；
阶段八将 Prometheus 升级为本机 HTTP 桩真实执行：2026-10-17；
阶段九以桩客户端驱动 `InfluxdbSeriesEngine` 全部引擎逻辑：2026-10-17；
阶段十以本机 HTTP 桩真实驱动 `HttpDatalakeQueryEngine` 查询/执行路径：2026-10-17；
阶段十一真实嵌入式 RocksDB 验收 `RocksDbSeriesEngine`；
阶段十二以本机 HTTP 桩验收 `QuestdbSeriesEngine` 并修复其 `/exec` 鉴权缺失：2026-10-17；
阶段十三至十六：InfluxDbEngine / 5 个外部 JDBC 引擎配置层 / Solr / RediSearchEngine：2026-10-17；
阶段十七以回放型假 Transport 驱动 `ElasticsearchEngine` 真实链路并修复索引名/count/异常语义：2026-10-17；
阶段十八以动态代理假 `Driver` 驱动 `Neo4jEngine` 的真实 Cypher 生成并修复标签/count/连接所有权/异常上下文：2026-10-17；
阶段十九以假 JDBC `Driver` 驱动 `GreptimeDbEngine` 的真实 SQL/JDBC 链路：2026-10-17；
阶段二十以动态代理假 `Connection`/`Table`/`Admin` 驱动 `HBaseEngine` 的真实领域 API
（建表/写入/读取/扫描/删除）并修复「用法错误被包装成操作失败」：2026-10-17；
阶段二十一方言分页 SQL：修复 Oracle / SQL Server / Derby 从未声明 `supports-limit=false`
导致下发的 `LIMIT` 语法不被服务端接受：2026-10-17；
阶段二十二用**官方免安装二进制**在本机 `initdb` 出真实 PostgreSQL 17.6 实例（端口 55432，
非容器——本机无任何容器运行时）跑真实读写，并修复「已关闭引擎被报成不支持 SQL」
与「SQL 失败消息吞掉服务端原因」：2026-10-17；
阶段二十三用**原生 Microsoft SQL Server 2022 Express 实例**（`SQLEXPRESS`，TCP 1433）
跑真实读写，并修复「分页统计总数把带 `ORDER BY` 的 SQL 塞进派生表，SQL Server 直接拒绝」：2026-10-18）
仓库：`utils-support-parent-starter`（另用 `spring-support-parent-starter` 与 `utils-support-datalake-parent` 的制品）
已提交的产品修复：`a22f42be1`（11 文件，+346 / −38）
已提交的验收工具：`c1b77f94d`（9 文件，+1767）、`ef627b740`（阶段五 4 文件）、
`03d360448`（补回被全局 ignore 规则静默剔除的两个运行器）、本轮阶段六
验收结论：**二十三阶段共 1079 条断言全绿，0 失败（`ACCEPTANCE_RESULT=PASS`，退出码 0）**

> **本轮为何要重绑 md5**：2026-10-04 首次出报告后，本地仓库里这批制品被后续构建
> 重新产出（`common-starter` 4,563,385 → 4,743,831 B，`monitor-starter` 1,127,261 → 922,392 B 等），
> 旧表 md5 已全部失效。阶段六落地时**按新制品重跑六阶段并重新登记 md5**，
> 下表即为本次验收实际加载的那批文件（经 `cp.txt` 逐一确认为 `D:\maven-repo` 下的路径）。
>
> **阶段七再次重绑**：为加入 `Engine.query()` 构建器重新 `mvn -o clean install` 了
> `common-starter`，jar 被重新产出（`ce53c145` → `0896c47e`，大小 4,743,831 → 4,749,974 B）。
> 七阶段随即在此新制品上重跑，全绿后登记。**其余 13 个制品字节未变**（md5 与阶段六一致）。

---

## 1. 被验产物与 md5（**换包即失效，须重跑后才能引用本报告**）

| 产物 | md5（前 8 位） | 大小 | 承载的验收内容 |
|---|---|---|---|
| `com.chua:utils-support-common-starter:4.0.0.42` | `fcbc8431` | 4,753,282 B | 缺陷 1/3/4/5/6/7 所在（阶段二/三/四）+ `Engine.query()` / `SqlQueryBuilder`（阶段七）+ **缺陷 28 的关闭态错误语义**（`Engine` 默认 SQL 入口，阶段二十二） |
| `com.chua:utils-support-datasource-starter:4.0.0.42` | `7a961255` | 354,653 B | 缺陷 1/2/3/4/6/7 所在（阶段一~四）+ 阶段六 `ReactorFlywayBridge` + 缺陷 12 的 `JdbcUrl.hostPort`（阶段十四）+ **缺陷 27 的方言 `supports-limit` 声明**（阶段二十一）+ **缺陷 29 的 SQL 失败原因透出**（`JdbcSqlExecutor`，阶段二十二）+ **缺陷 30 的计数前裁剪尾部 `ORDER BY`**（阶段二十三） |
| `com.chua:utils-support-redis-starter:4.0.0.42` | `112be63d` | 134,614 B | 缺陷 8/9 所在（阶段三/一）+ 缺陷 15/16/17 的修复（`RediSearchEngine` 实体 CRUD，阶段十六） |
| `com.chua:utils-support-h2-starter:4.0.0.42` | `83ed8ee5` | 29,518 B | 阶段二/三负载 |
| `com.chua:utils-support-sqlite-starter:4.0.0.42` | `e6114489` | 43,241 B | 阶段二负载 + 阶段六 `SqliteReactorEngine` |
| `com.chua:utils-support-mysql-starter:4.0.0.42` | `892ea6da` | 140,783 B | 阶段三负载 + 缺陷 12 修复 |
| `com.chua:utils-support-rocksdb-starter:4.0.0.42` | `f5fe32c1` | 38,585 B | 阶段三/四负载 |
| `com.chua:utils-support-tablesaw-starter:4.0.0.42` | `de188bb5` | 39,304 B | 阶段一/四负载 |
| `com.chua:utils-support-parquet-starter:4.0.0.42` | `c2eda54d` | 21,207 B | 阶段一/四负载 |
| `com.chua:utils-support-lucene-starter:4.0.0.42` | `d3448cbf` | 35,486 B | 阶段一/四负载 |
| `com.chua:utils-support-nitrite-starter:4.0.0.42` | `d56620b7` | 12,604 B | 阶段一/四负载 |
| `com.chua:spring-support-redis-starter:4.0.0.42` | `c0278a4f` | 37,985 B | 阶段五负载 |
| `com.chua:spring-support-monitor-starter:4.0.0.42` | `0372fcec` | 922,392 B | 阶段五负载 |
| `com.chua:utils-support-datalake-query-starter:4.0.0.42` | `4804877f` | 12,858 B | 阶段六负载（`HttpDatalakeQueryEngine`） |
| `com.chua:utils-support-questdb-starter:4.0.0.42` | `e400dfdf` | 13,425 B | 阶段十二负载；承载缺陷 10 的修复（`/exec` 鉴权） |
| `com.chua:utils-support-prometheus-starter:4.0.0.42` | `cc5a0a50` | 53,300 B | 阶段八被测代码所在（`PrometheusEngine` / `PrometheusClient`） |
| `com.chua:utils-support-influxdb-starter:4.0.0.42` | `03bea1f4` | 25,078 B | 阶段九被测代码所在（`InfluxdbSeriesEngine`） |
| `com.chua:utils-support-chronicle-starter:4.0.0.42` | `123a7ba2` | 32,048 B | 阶段三被测代码之一（`ChronicleMapKv`） |
| `com.chua:utils-support-duckdb-starter:4.0.0.42` | `315a0324` | 18,270 B | 阶段二深度功能面（`DuckDBEngine`） |
| `com.chua:utils-support-postgresql-starter:4.0.0.42` | `b66de85e` | 94,739 B | 阶段一契约面（24 键之一 `postgresql`）+ 阶段十四配置/失败/关闭层 |
| `com.chua:utils-support-sqlserver-starter:4.0.0.42` | `edb4e3c6` | 14,044 B | 阶段一契约面（`sqlserver` / `sqlserver-legacy`）+ 缺陷 11 修复 + 阶段十四 |
| `com.chua:utils-support-oracle-starter:4.0.0.42` | `28d846c7` | 34,618 B | 阶段一契约面（`oracle`）+ 缺陷 11/12 修复 + 阶段十四 |
| `com.chua:utils-support-clickhouse-starter:4.0.0.42` | `d9763073` | 10,359 B | 阶段一契约面（`clickhouse`）+ 缺陷 12 修复 + 阶段十四 |
| `com.chua:utils-support-hbase-starter:4.0.0.42` | `fc45bff5` | 12,344 B | 阶段一契约面（`hbase`）+ **阶段二十真实领域 API 链路**（动态代理假 `Connection`/`Table`/`Admin`）+ 缺陷 26 修复 |
| `com.chua:utils-support-solr-starter:4.0.0.42` | `46b6a1c9` | 48,582 B | 阶段一契约面（`solr`）+ **阶段十五深度功能**（假 `SolrClient` 驱动真实路径）+ 缺陷 13/14 修复 |
| `com.chua:utils-support-neo4j-starter:4.0.0.42` | `a5aa736a` | 25,536 B | 阶段一契约面（`neo4j`）+ **阶段十八真实 Cypher 链路**（动态代理假 `Driver`）+ 缺陷 22/23/24/25 修复 |
| `com.chua:utils-support-elasticsearch-starter:4.0.0.42` | `6970ab98` | 56,205 B | 阶段一契约面（`elasticsearch`）+ **阶段十七真实链路**（回放型假 `Transport`）+ 缺陷 18/19/20/21 修复 |
| `com.chua:utils-support-greptimedb-starter:4.0.0.42` | `97b72d46` | 32,785 B | 阶段一契约面（`greptimedb`）+ **阶段十九真实 SQL/JDBC 链路**（假 JDBC 驱动） |

签收前须重新核对上述 md5；不一致即本报告作废（本轮已因制品被重建而重跑过一次）。

> **阶段十一/十二的 md5 口径**：为修缺陷 10，`utils-support-questdb-starter` 被重新
> `mvn -o clean install` 产出（`13,425 B`，`e400dfdf`）；除它之外**所有制品字节未变**
> （已逐个复核，md5 与上表一致）。`src/acceptance/lib/` 里实际加载的那份 jar
> `certutil -hashfile … MD5` = `e400dfdfd934e9f4df56e0e2a75c7f4c`，与仓库内一致。
>
> **本轮补登记的 3 个制品**（`prometheus` / `influxdb` / `chronicle`）：它们的代码分别在
> 阶段八 / 九 / 三中被直接执行，但此前**从未进入 md5 表**——等于「验了却没绑定」。
> 本轮按实际加载的 jar 补登记，报告的证据链才闭合。
>
> 同理补登记了**阶段一契约面涉及的 10 个制品**（duckdb / postgresql / sqlserver / oracle /
> clickhouse / hbase / solr / neo4j / elasticsearch / greptimedb）：阶段一逐个构造这些引擎并
> 断言其契约，等于执行了这些 jar 里的代码，不绑定就无法证明「签收的是哪一份」。
> 至此，**凡是本验收直接执行其代码的 28 个制品，全部绑定 md5**。

> **阶段十四再次重绑 6 个制品**：为修缺陷 11/12 重新构建了
> `datasource`（`8c9bac45` → `91c5b9e1`）、`postgresql`（`6d9e8995` → `b66de85e`）、
> `sqlserver`（`610bef4d` → `edb4e3c6`）、`oracle`（`617db9a8` → `28d846c7`）、
> `clickhouse`（`6d4d428b` → `d9763073`）、`mysql`（`5fad5192` → `892ea6da`）。
> 其余 22 个制品字节未变。上表已就地更新，且 6 个新值与 `src/acceptance/lib/` 内
> 实际加载的 jar 用 `certutil` 逐个复核一致（见提交说明）。

> **阶段十五重绑 1 个制品**：为修缺陷 13/14 重新构建了
> `solr`（`34c1847c` → `46b6a1c9`，48,582 B）。其余 27 个制品字节未变。

> **阶段十六重绑 1 个制品**：为修缺陷 15/16/17 重新构建了
> `redis`（`abc98158` → `112be63d`，134,614 B）。其余 27 个制品字节未变。

> **阶段十七重绑 2 个制品**：为修缺陷 18/19/20/21 重新构建了
> `elasticsearch`（`b2a2cb4e` → `6970ab98`，56,205 B）；另因下述原因重建了
> `common-starter`（`0896c47e` → `cfd341ed`，4,749,974 B）。其余 26 个制品字节未变。
> ES jar 内含 `ElasticsearchEngine.indexName(Class)`（可用
> `javap -p -cp … ElasticsearchEngine` 复核），`src/acceptance/lib/` 实际加载的那份
> 经 `certutil -hashfile … MD5` 与仓库一致。

> **阶段十七同步重绑 `common-starter`**：复核 md5 时发现本地仓库里的
> `utils-support-common-starter` 是 15:58 被并发会话重建的产物（`a8693223`，4,753,379 B），
> 其中含一个**当时未提交、随后被删除**的 `IpCidrUtils.class`——即该 jar 与源码树不一致，
> 不能作为签收产物。已按当前源码（与 HEAD 一致）重新 `mvn -o clean install` 产出
> `cfd341ed`（4,749,974 B，`jar tf` 已无 `IpCidrUtils`），并在此新制品上重跑全量阶段。
> 注：`0896c47e` 是阶段七登记值，其 jar 早已被后续构建覆盖，本轮以 `cfd341ed` 为准。

> **阶段十八重绑 1 个制品**：为修缺陷 22/23/24/25 重新构建了
> `neo4j`（`fdf22bed` → `a5aa736a`，25,536 B）。其余 27 个制品字节未变。
> 新 jar 内含 `Neo4jEngine.safeLabel(String)` 与 `ownsDriver` 字段（可用
> `javap -p -cp … Neo4jEngine` 复核）；`src/acceptance/lib/` 实际加载的那份
> 经 `certutil -hashfile … MD5` 与仓库一致。

> **阶段十九重绑 1 个制品**：为使 `greptimedb` 制品与源码树重新对齐、并登记本阶段
> 实际加载的文件，重新 `mvn -o clean install` 产出了
> `greptimedb`（`998edaed` → `97b72d46`，32,785 B，大小不变）。
> 源码与 HEAD 逐字节一致（`git diff -- <模块>` 无输出），字节差异仅来自 jar 内时间戳。
> 其余 27 个制品字节未变。

> **阶段二十重绑 1 个制品**：为修缺陷 26 重新构建了
> `hbase`（`7e9eb07b` → `fc45bff5`，12,262 → 12,344 B）。
> 新 jar 内含「先取连接再进入 try」的写法（可用
> `javap -c -p -cp … HBaseEngine` 复核：`conn()` 调用在 `try` 之外）。
> `src/acceptance/lib/` 实际加载的那份经 `certutil -hashfile … MD5` 与上表一致。
> 其余 27 个制品字节未变。

> **阶段二十一重绑 1 个制品**：为修缺陷 27 重新构建了
> `datasource`（`91c5b9e1` → `467f58a0`，353,340 B）。
> 8 个 `.env` 新增 `supports-limit=false`（可用
> `unzip -p …jar META-INF/dialect-env/oracle.env | findstr supports-limit` 复核）。
> `src/acceptance/lib/` 实际加载的那份经 `certutil` 与上表一致。其余 27 个制品字节未变。

> **阶段二十二重绑 2 个制品**：为修缺陷 28/29 重新构建了
> `common`（`cfd341ed` → `670cdd25`，4,749,974 → 4,750,128 B）与
> `datasource`（`467f58a0` → `fa4fb072`，353,340 → 353,554 B）。
> 复核方式：`Engine.java` 的三个 SQL 默认入口按 `isClosed()` 区分消息；
> `JdbcSqlExecutor` 的 5 处包装消息追加 ` -> <cause 消息>`。
> `src/acceptance/lib/` 实际加载的那两份经 `certutil` 与上表一致。其余 26 个制品字节未变。

> **阶段二十三重绑 2 个制品**：为修缺陷 30 重新构建了
> `datasource`（`fa4fb072` → `7a961255`，353,554 → 354,653 B）；
> 另因并发会话在 23:42 重建过 `common`（`670cdd25` → `fcbc8431`，4,750,128 → 4,753,282 B）
> 而同步重绑。`common` 的这一版是在工作树上产出的，比 HEAD 多编译进一个
> **未跟踪、且没有任何已跟踪代码引用**的 `IpCidrUtils.class`
> （源文件 `network/net/IpCidrUtils.java` 由并发会话创建，`git grep IpCidrUtils -- '*.java'` 无结果）：
> 它不参与任何被验收的引擎路径，但为使「报告 md5 = 实际加载 jar」这一条成立，
> 此处按实际加载的文件登记，并如实标注该差异。其余 26 个制品字节未变。
> 复核方式：`JdbcSqlExecutor.count(...)` 在包装前调用 `stripTrailingOrderBy(...)`
> （可用 `javap -p -cp … JdbcSqlExecutor | findstr stripTrailingOrderBy` 复核）；
> `src/acceptance/lib/` 实际加载的那两份经 `Get-FileHash -Algorithm MD5` 与 target、
> `D:\maven-repo` 三处逐个比对一致（`CHECKED=28 BAD=0`）。

## 2. 子类清单与覆盖状态（权威口径）

### 2.1 Engine —— SPI 注册的 24 个（阶段一全覆盖）

注册文件 `META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine`：

| 键 | 实现类 | 键 | 实现类 |
|---|---|---|---|
| file | `FileEngine` | mysql | `MysqlEngine` |
| memory | `InMemoryEngine` | neo4j | `Neo4jEngine` |
| clickhouse | `ClickHouseEngine` | nitrite | `NitriteEngine` |
| duckdb | `DuckDBEngine` | oracle | `OracleEngine` |
| elasticsearch | `ElasticsearchEngine` | parquet | `ParquetEngine` |
| greptimedb | `GreptimeDbEngine` | postgresql | `PostgresqlEngine` |
| h2 | `H2Engine` | rocksdb | `RocksDbEngine` |
| hbase | `HBaseEngine` | solr | `SolrEngine` |
| influxdb | `InfluxDbEngine` | sqlite | `SqliteEngine` |
| lucene | `LuceneEngine` | sqlserver | `SqlServerEngine` |
| prometheus | `PrometheusEngine` | sqlserver-legacy | `SqlServerLegacyEngine` |
| redis | `RediSearchEngine` | tablesaw | `TablesawEngine` |

**深度功能覆盖**：memory / file / h2 / sqlite / duckdb（阶段二）、parquet / lucene / nitrite /
tablesaw / rocksdb（阶段四）、mysql（阶段三，真实 3306）、prometheus（阶段八，本机 HTTP 桩）、
influxdb（阶段十三，纯内存）、redis（阶段三/五：`RedisClient`/`RedisKvEngine`/`RedisSeriesEngine`；
阶段十六：`RediSearchEngine` 的**实体 CRUD 真实读写**）、solr（阶段十五，记录型假
`SolrClient` 驱动真实查询/写入/删除路径）、elasticsearch（阶段十七，回放型假
`Transport` 驱动官方客户端的真实序列化/反序列化链路）、neo4j（阶段十八，动态代理假
`Driver` 驱动真实 Cypher 生成与结果映射）、greptimedb（阶段十九，假 JDBC `Driver` 驱动
真实 SQL 生成与结果映射）、hbase（阶段二十，动态代理假 `Connection`/`Table`/`Admin`
驱动真实的 `Put`/`Get`/`Scan`/`Delete` 构造与 `Result` 解析）；非 SPI 的 datalake-query
（阶段十，本机 HTTP 桩）与 ReactorFlywayBridge（阶段六，真实 SQLite）。
另：postgresql / sqlserver（含 legacy）/ oracle / clickhouse 在阶段十四验证了
**配置拼装、入参守卫、失败语义与关闭语义**，阶段二十一验证了**方言分页 SQL 渲染**
（ROWNUM / OFFSET-FETCH / ROW_NUMBER）；**postgresql 进一步在阶段二十二用真实
PostgreSQL 17.6 实例完成读写验收**（37 条），**sqlserver 在阶段二十三用真实
SQL Server 2022 Express 实例完成读写验收**（39 条）。oracle / clickhouse 的
**查询语义仍未验**（见 §7）。

**非 SPI 注册的 Engine 实现**（不在 `ServiceProvider` 可发现范围内）：

| 实现 | 性质 | 覆盖状态 |
|---|---|---|
| `AbstractEngine` | 抽象基类 | 经全部子类间接覆盖 |
| `JdbcEngine` | 抽象基类（同步 JDBC） | 经 9 个 JDBC 引擎子类间接覆盖 |
| `JdbcReactorEngine` / `SqliteReactorEngine` | 另一 SPI 家族（`ReactorEngine`），非 `Engine` 子类 | 阶段六经 `ReactorFlywayBridge` 以真实 SQLite 驱动其 JDBC 降级路径 |
| `JdbcReactorEngine$R2dbcEngineAdapter` / `$UnifiedEngineAdapter` | **private 内部类**，仅由宿主实例内部创建 | 无法从外部引用，**未直接触达**（如实声明） |
| `ReactorFlywayBridge` | Flyway 的「响应式→同步」桥接，`implements Engine` | **阶段六**：桥接契约（15 条 UOE/能力断言）+ 真实 SQLite 读写（11 条） |
| `HttpDatalakeQueryEngine` | datalake 模块的只读查询引擎，`implements Engine` | **阶段六**：只读契约面 16 条（写入口 UOE / 方言与数据源为 null / 关闭幂等），不触网；**阶段十**：本机 HTTP 桩真实驱动 `query`/`queryPage`/`execute`（21 条） |

> `com.chua.playwright.support.engine.JavaEngine` / `NativeEngine` **不是** datasource Engine ——
> 它们实现的是 playwright 自己的 `Engine`（`com.chua.playwright.support.engine.Engine`，
> 浏览器自动化 SPI，同包同名接口，已确认该包内存在独立 `Engine.java`）。已甄别排除。
> `com.chua.redis.support.engine.RedisEngine` 是 `RediSearchEngine` 的**基类且不实现 Engine**，同样排除。

上表的穷尽性由**全仓检索**保证：`implements Engine` / `implements com.chua…engine.Engine` /
`extends AbstractEngine` / `extends JdbcEngine` 四种写法的匹配结果，除去 playwright 的两个
（不同接口）、两个 private 内部类、两个抽象基类后，恰好等于 SPI 的 24 个键 + 阶段六的 2 个。

### 2.2 KvEngine —— 4 个（全覆盖）

| 实现 | 覆盖 | 阶段 |
|---|---|---|
| `com.chua.chronicle.support.kv.ChronicleMapKv` | 全 KV 契约 + TTL 自洽 | 三 |
| `com.chua.rocksdb.support.engine.RocksDbEngine` | 全 KV 契约 + TTL 自洽 | 三 |
| `com.chua.redis.support.client.RedisClient` | 全 KV 契约 + TTL 自洽 | 三 |
| `com.chua.starter.redis.support.kv.RedisKvEngine` | **真实读写**（Spring `StringRedisTemplate` + 本机 6379） | 五 |

### 2.3 SeriesEngine —— 6 个（全覆盖：SPI 5 个 + 非注册 1 个）

SPI 注册文件 `META-INF/extensions/com.chua.common.support.lang.datasource.series.SeriesEngine`
共 5 个键：`datasource` / `influxdb` / `questdb` / `rocksdb` / `redis`；另有 1 个非注册实现由
monitor 模块的 Bean 工厂创建。

| 实现 | 覆盖 | 阶段 |
|---|---|---|
| `com.chua.common.support.lang.datasource.series.impl.DataSourceSeriesEngine` | h2 真实读写 + 全契约 | 三 |
| `com.chua.redis.support.engine.RedisSeriesEngine` | 本机 Redis 真实读写 + 全契约 | 三 |
| `com.chua.rocksdb.support.engine.RocksDbSeriesEngine` | **真实嵌入式 RocksDB 读写**（键布局、时间戳编码、索引回收、跨实例持久化） | 十一 |
| `com.chua.questdb.support.engine.QuestdbSeriesEngine` | 本机 HTTP 桩驱动全部协议实现（ILP 写入 / `/exec` 查询与清理 / 鉴权） | 十二 |
| `com.chua.influxdb.support.engine.InfluxdbSeriesEngine` | 注入桩客户端后**引擎逻辑全量真实执行**（无真实 InfluxDB 服务） | 五、九 |
| `com.chua.starter.monitor.support.config.SeriesEngineConfiguration$NoopSeriesEngine` | 经其唯一公开入口（Bean 工厂）驱动 + 全契约 | 五 |

> **补记（本轮纠错）**：此前版本把 SeriesEngine 写成「4 个全覆盖」，遗漏了 SPI 登记在案的
> `rocksdb` 与 `questdb` 两个实现——它们此前**一次都没有被验过**，报告却声称全覆盖。
> 阶段十一/十二补上后，该表才是真正的「6 个全覆盖」。这条错误本身记录在此，因为
> 「报告说全覆盖、实际没验」比「如实声明未覆盖」危害更大。

## 3. 验证阶段与断言数

| 阶段 | 范围 | 断言 | 结果 |
|---|---|---|---|
| 一 | 24 个 SPI 引擎的契约 / 能力声明 / 生命周期 / 空参与边界 | 288 | 全绿 |
| 二 | memory / file / h2 / sqlite / duckdb 深度功能面 | 132 | 全绿 |
| 三 | KvEngine ×3 + SeriesEngine ×2 + mysql（真实 3306） | 75 | 全绿 |
| 四 | parquet / lucene / nitrite / tablesaw + rocksdb 文档面 | 24 | 全绿 |
| 五 | `RedisKvEngine` + `InfluxdbSeriesEngine` + `NoopSeriesEngine` + monitor 装配路径 | 39 | 全绿 |
| 六 | 非 SPI 注册的两个公开 `Engine` 实现者：`HttpDatalakeQueryEngine`（只读契约面，查询/执行路径见阶段十）、`ReactorFlywayBridge`（桥接契约 + 真实 SQLite 读写） | 45 | 全绿 |
| 七 | 新增 `Engine.query()` 非 ORM SQL 文本构建器：渲染 / 守卫 + 内存 / H2 / SQLite 三引擎端到端执行 | 37 | 全绿 |
| 八 | `PrometheusEngine` + `PrometheusClient`：本机 HTTP 桩真实读写（8 个 `/api/v1/*` 端点、Basic 认证头、scalar/vector/matrix、HTTP 500 / 非 JSON / status=error / 不支持 resultType 的失败语义） | 35 | 全绿 |
| 九 | `InfluxdbSeriesEngine`：注入桩客户端后的引擎逻辑真实执行（标识符白名单、行协议写入、指标发现、多指标歧义拒绝、结果展平与时间解析、降采样、清理、关闭） | 26 | 全绿 |
| 十 | `HttpDatalakeQueryEngine`：本机 HTTP 桩真实读写（`POST /query` 编码与请求体、JSON 行解析与类型映射、POJO、客户端分页、受影响行数、畸形响应拒绝） | 21 | 全绿 |
| 十一 | `RocksDbSeriesEngine`：真实嵌入式 RocksDB（装配判定、守卫、键布局与时间戳编码、降采样、范围裁剪、同戳覆盖、monitor 隔离、索引回收、清理条数、跨实例持久化） | 17 | 全绿 |
| 十二 | `QuestdbSeriesEngine`：本机 HTTP 桩（ILP 报文、逐点 flush、写入失败不静默、地址归一化、`/write`+`/exec` 双侧鉴权、探测 SQL、列收窄、查询 SQL 与 ISO 边界、微秒归一秒、坏点丢弃、`DELETE` 语义、关闭） | 30 | 全绿 |
| 十三 | `InfluxDbEngine`（SPI `influxdb`，纯内存）：能力自洽、表名解析与回退、同名覆盖、跨表隔离、条件/排序/计数、分页边界、全表守卫、带条件写真实生效 | 16 | 全绿 |
| 十四 | 5 个无服务端 JDBC 引擎的本机可验面：URL 精确拼装、入参注入串拒绝、端口 0 契约、不可达快速失败、连接池真关闭 | 45 | 全绿 |
| 十五 | Solr（SPI `solr`）：假 `SolrClient` 驱动真实路径——表名解析、q 构造、分页下推、numFound 计数、文档映射、写入/删除、失败语义、生命周期 | 28 | 全绿 |
| 16 | `RediSearchEngine`（SPI `redis`）：**真实 Redis 6379 读写**——key 前缀与 `@TableName`、store/query 一致、字段还原、回退扫描按 WHERE 过滤、条件计数、分页、能力声明、生命周期、原生命令 | 18 | 全绿 |
| 十七 | `ElasticsearchEngine`（SPI `elasticsearch`）：回放型假 `Transport` 驱动官方客户端真实链路——索引名与 `@TableName`、`match_all`/`term` 构造、`one`/`page` 下推与 total、`count()`、响应→实体映射、bulk 写入与部分失败、异常语义、生命周期与能力声明 | 21 | 全绿 |
| 十八 | `Neo4jEngine`（SPI `neo4j`）：动态代理假 `Driver` 驱动真实 Cypher 生成——label 与 `@TableName`、`WHERE` 参数化、`LIMIT`/`SKIP` 下推、`count(n)` 计数、`ORDER BY`、`UNWIND` 批量写入、`SET`/`DETACH DELETE`、属性映射、异常语义、连接所有权 | 25 | 全绿 |
| 十九 | `GreptimeDbEngine`（SPI `greptimedb`）：假 JDBC `Driver` 驱动真实 SQL/JDBC 链路——表名与 `@TableName`、列名归一化、条件参数化、排序与分页下推、`COUNT(*)` 子查询计数、结果映射、`store`/`update` 的 UOE、无 WHERE 守卫、带条件 DELETE、执行器四个入口、JDBC URL 推导、生命周期 | 30 | 全绿 |
| 二十 | `HBaseEngine`（SPI `hbase`）：动态代理假 `Connection`/`Table`/`Admin`/`ResultScanner` 驱动真实领域 API——建表幂等与列族、`Put` 行键/列族/限定符/值、`Get` 的列族过滤与全列族前缀、空结果、`Scan` 前缀起止行（含非 ASCII 字节递增）、`__row` 与空行跳过、`Delete` 行键、`store`/查询/`update`/`delete` 的 UOE、`close()` 的连接所有权与用法错误不被包装 | 23 | 全绿 |
| 二十一 | 方言分页 SQL（postgresql / clickhouse / oracle / oracle11g / oracle12c / sqlserver / sqlserver2008 / sqlserver2012 / derby / derby1014）：`supportsLimit` 能力声明、LIMIT 方言渲染、ROWNUM / OFFSET-FETCH / ROW_NUMBER 模板、ORDER BY 回退注入与幂等、四引擎实际方言接线 | 28 | 全绿 |
| 二十二 | **真实 PostgreSQL 17.6 实例**（官方免安装二进制 + `initdb`，端口 55432；非容器）：通用 JDBC 套件 28 条（建表/插入/查询/分页/count/Lambda/batch/全表写删拒绝/非法入参）+ 9 条 PostgreSQL 专有（`version()`、中文/emoji 往返、`JSONB` 的 `->>` 与 `::int`、`BIGSERIAL`+`TIMESTAMPTZ` 默认值、分页真实边界、不存在的表带服务端原因、主键冲突不留半条、方言 `supportsLimit` 与真实 `LIMIT/OFFSET` 一致、关闭后报「已关闭」） | 37 | 全绿 |
| 二十三 | **真实 SQL Server 2022 Express 实例**（原生 `SQLEXPRESS`，TCP 1433）：通用 JDBC 套件 28 条 + 11 条 T-SQL 专有（`@@VERSION`、`DB_NAME()`、`NVARCHAR` 的中文/emoji/制表符往返与 `LEN(N'中文')=2`、`IDENTITY`+`DATETIME2` 默认值、分页真实边界、`TOP`/`ISNULL`/`CAST`、**计数前裁剪尾部 `ORDER BY` 的四种场景**（窗口函数 / 字面量 / 无排序 / 带参数）、不存在的表带服务端原因（兼容中英文本地化）、主键冲突不留半条、方言 `supportsLimit=false` 与真实 `OFFSET/FETCH` 一致、关闭后报「已关闭」） | 39 | 全绿 |

**合计 1079 条断言，0 失败**（`run-acceptance.bat`：二十三阶段逐段 `PASS`，
末行 `ACCEPTANCE_RESULT=PASS`，退出码 0；日志中 `[FAIL]` 与 `[SKIP]` 均为 0 条）。

## 4. 修复的 30 处真实缺陷

| # | 缺陷 | 根因 | 影响 |
|---|---|---|---|
| 1 | **全部 JDBC 引擎 `Lambda page()` 恒失败** | `AbstractEngine.executePage` 在 `finally` 里 `wrapper.limit(origLimit)`，而 origLimit 通常为 0；`limit(int)` 对 0 抛 IAE，**在 finally 中抛出即覆盖已成功的分页结果** | 分页功能完全不可用 |
| 2 | memory/file 类型化查询不可用 | 未覆写 `querySql(ql, Class)`，接口默认实现按 `getExecutor()==null` 抛 UOE，与 `supportsSql()==true` 冲突 | 能力声明与行为不一致 |
| 3 | **memory 无 WHERE 的 `update()`/`remove()` 静默作用于全表** | 引擎求值器绕过 `buildSql()` 里的全表守卫；空条件谓词恒真 → 更新整表；`negate()` 后恒假 → **清空整表** | 静默数据破坏 |
| 4 | memory/file `MAX(age)` 等标量聚合报错 | 解析器只字面支持 `COUNT(*)`；而接口 Javadoc 明确把 `SELECT max(id)` 列为 `queryValue` 适用场景 | 文档承诺的功能缺失 |
| 5 | `removeDataSource(null)` 静默无操作 | 实现与接口契约「名称为 null 时抛 IAE」相悖 | 拼错名称伪装成「已摘除」 |
| 6 | memory/file `querySql(null)` 抛裸 NPE | 缺非空校验 | 无法区分用法错误与内部缺陷 |
| 7 | **memory/file 类型化查询把全部非 String 字段映射为 null** | `setValue` 的 `guessType` 把 Integer/Long 推断成 `int.class`/`long.class`，而 Lombok setter 形参是包装类型 → 形参不匹配 → 异常被吞 | 读路径静默数据丢失 |
| 8 | Redis Series 把契约要求的 IAE 包成 ISE | `series()` 宽 catch 吞掉 `resolveSingleMetric` 抛出的 IAE | 调用方 `catch(IAE)` 失效，参数错误显示成服务异常 |
| 9 | Redis Series `purge()` 后该 monitor 永久不可查 | 删了 ZSET 数据却未摘除指标索引 → `metrics()` 仍报「已写入」→ `series(metricName=null)` 永久抛「指标不唯一」 | 清理即自锁 |
| 10 | **`QuestdbSeriesEngine` 开启鉴权时读路径全部 401** | `token` / `username` / `password` 只交给了写入用的官方 `Sender`；查询/指标发现/清理走的 JDK `HttpClient` **一个鉴权头都没发**（`execQuery` 只设了超时） | 配了鉴权的 QuestDB 上，写入成功但 `metrics` / `series` / `purge` 全失败；而默认无鉴权的本地实例掩盖了它 |
| 11 | **`OracleEngine` / `SqlServerLegacyEngine` 缺 JDBC 入参校验** | 同目录的 postgresql / sqlserver / clickhouse 都调 `JdbcUrl.checkHost/checkPort/checkDatabase` 再拼 URL，这两个引擎直接裸拼 | `host=null` 拼出 `jdbc:oracle:thin:@//null:1521/db` 之类只在连接阶段报错的串；`database="db?autoDeserialize=true"` 这类串可改写驱动属性（`JdbcUrl` 的类注释写明了这条注入路径），属于注入面 |
| 12 | **端口 0 的「驱动默认端口」契约在 10 处拼装点上失效** | `JdbcUrl.checkPort` 的 Javadoc 明确「0 表示使用驱动默认端口」，但所有引擎无条件拼 `host + ":" + port` | 传 0 得到 `host:0`，驱动会去连 **0 端口**而不是回退默认端口；文档承诺的用法 100% 失败，且报错与其根因无关 |
| 13 | **Solr 引擎绕过 `@TableName`，`store` 与 `query` 集合名不一致** | `SolrEngine` 5 处集合名用 `entityClass.getSimpleName().toLowerCase()` 推导，绕过了 `AbstractEngine.resolveTableName`（优先 `@TableName`，否则驼峰转下划线） | `store("t_acc_user", rows)` 写入 `t_acc_user`，而 `query(AccUser.class)` 去查 `accuser`——**写进去的数据读不出来**；无注解实体也与 H2/SQLite/内存等引擎的集合名不一致 |
| 14 | **Solr `query(X).count()` 直接抛 `UnsupportedOperationException`** | `LambdaQueryWrapper.count()` 的基类实现要求引擎覆写，Solr 的匿名包装器只覆写了 `list/one/page` | ORM 的计数 API 完全不可用（尽管 Solr 响应里已经有 `numFound`）；若改用 `list().size()` 回退，还会被 1000 行上限截断 |
| 15 | **`RediSearchEngine` 绕过 `@TableName`，`store` 与 `query` 的 key 前缀不一致** | `RedisEngine.getKeyPrefix` 用 `getSimpleName().toLowerCase()` 推导，绕过 `AbstractEngine.resolveTableName`（优先 `@TableName`，否则驼峰转下划线）；`store(name, ...)` 却按调用方给的 `name` 写键 | `store("t_acc_user", rows)` 写入 `t_acc_user:1`，而 `query(AccUser.class)` 按 `accuser:*` 扫描——**写进去的数据读不出来**（回退路径静默返回空）；`@TableName` 完全失效 |
| 16 | **`FT.SEARCH` 不可用时，回退扫描丢弃 WHERE 条件** | `RediSearchEngine.executeNewQuery` 的 `catch` 直接 `return scanAll(...)`，`scanAll` 只按前缀全量拉取，不带任何条件 | 无 RediSearch 模块的 Redis（Windows 构建默认没有）上，`query(X).eq(name,"alice")` **返回全部行**；`one()` 返回任意一行，`count()`/分页全部偏大——静默的错误结果，比抛错更危险 |
| 17 | **`RediSearchEngine` 的 `query(X).count()` 抛 `UnsupportedOperationException`** | 与缺陷 14 同源：匿名包装器只覆写 `list/one/page`，未覆写基类要求引擎实现的 `count()` | 计数 API 不可用；即便补 `list().size()`，也会因缺陷 16 而算错 |
| 18 | **`ElasticsearchEngine` 绕过 `@TableName`，`store` 与 `query` 的索引名不一致** | `store(name, …)` 按调用方给的 `name` 写索引，而 `search`/`searchPage`/`doSearch` 三处用 `entityClass.getSimpleName().toLowerCase()` 读索引，绕过 `AbstractEngine.resolveTableName` | `store("t_acc_user", rows)` 写入 `t_acc_user`，`query(AccUser.class)` 却查 `accuser`——**写进去的数据读不出来**；带 `@TableName` 的实体与无注解实体（驼峰转下划线）双双失效 |
| 19 | **`ElasticsearchEngine` 的 `query(X).count()` 抛 `UnsupportedOperationException`** | 与缺陷 14/17 同源：匿名包装器只覆写 `list/one/page` | 计数 API 不可用；ES 响应里本就有 `hits.total`，属于纯粹的功能缺失 |
| 20 | **配置缺失/已关闭被包装成「查询失败」** | `search`/`searchPage` 用宽 `catch (Exception)` 把 `IllegalStateException("请先 addDataSource")` 一并包成 `RuntimeException("Elasticsearch 查询失败…")` | 未配置数据源或 `close()` 后调用时，调用方只能看到泛化的查询失败（`cause` 里才是真相），无法区分「用法错误」与「服务端故障」，与同族引擎的 ISE 契约不一致 |
| 21 | **批量索引的「部分失败」详情被二次包裹丢失** | `store` 把 `errors()` 的抛出写在 `try` 内，随后被自己的 `catch (Exception)` 重新包装，`失败条数` 只留在 `cause` 里 | 排障时只能看到「批量索引失败」，拿不到失败条数这类关键定位信息 |
| 22 | **`Neo4jEngine` 的 `query(X).count()` 抛 `UnsupportedOperationException`** | 与缺陷 14/17/19 同源：匿名包装器只覆写 `list/one/page`，未覆写基类要求引擎实现的 `count()`；引擎内部本就有 `cypherCount` | 计数 API 完全不可用；若改用 `list().size()` 回退，在大图上会把整表拉进内存 |
| 23 | **`Neo4jEngine` 用 `getSimpleName()` 推导 label，`@TableName` 与驼峰转下划线双双失效** | 5 处 label 拼装绕过 `AbstractEngine.resolveTableName` | `store("t_acc_user", rows)` 写入 `:AccUser`，无注解实体写成 `:PlainUser` 而非 `:plain_user`；与节点上已有数据的 label 不符时**写进去读不出来**（`MATCH` 命中 0 行） |
| 24 | **`close()` 无条件关闭调用方注入的 `Driver`** | 引擎不区分「自己 new 的驱动」与「`addDataSource(Driver)` 注入的驱动」，`close()` 一律 `driver.close()` | 外部容器/连接池注入的驱动被引擎擅自关闭——同一驱动被多个消费者共享时，**关闭引擎即打断他人连接**；且 `driver` 未置空，关闭后仍会拿已关闭驱动去查询 |
| 25 | **驱动异常被原样透出，丢失 Cypher 上下文** | `store`/查询路径把 `RuntimeException` 直接 `throw e`（4 处），不带任何查询信息 | 服务端报错时调用方只看到驱动原始消息（如语法错误、约束冲突），**无法定位是哪条 Cypher、哪个 label**；与同族引擎「带索引名/表名包装」的契约不一致 |
| 26 | **`HBaseEngine` 把「未配置数据源 / 已关闭」包装成操作失败** | 5 个领域 API（建表/put/get/scan/deleteRow）都写成 `try { … conn().getTable(…) … } catch (Exception e) { throw new ISE("HBase xxx 失败: …") }`——`conn()` 抛出的用法错误落进同一个 `catch` | 未配置或 `close()` 后调用时，调用方看到的是「HBase put 失败: t_user/r」，只能翻 `cause` 才知道是用法错误；与已修的 Elasticsearch（缺陷 20）、Neo4j（缺陷 24/25）**同源**，同为「用法错误与故障不分」 |
| 27 | **Oracle / SQL Server / Derby 方言从未声明 `supports-limit=false`，分页一律下发 `LIMIT`** | `SqlDialect.supportsLimit()` 读 `supports-limit`（缺省 `true`），`JdbcEngine.wrapPagination` 据此选择「追加 LIMIT/OFFSET」还是「走方言模板」；而 `oracle*.env` / `sqlserver*.env` / `derby*.env` 里虽写好了 ROWNUM / OFFSET-FETCH / ROW_NUMBER 的 `pagination-sql` 模板，却**没有**任何一个文件写过 `supports-limit=false` | 这四个引擎一旦分页（`page()` / `limit()`），下发的是 `SELECT … LIMIT n OFFSET m`——Oracle 全版本、SQL Server 全版本、Derby 都不认这条语法，**分页 100% 失败**；env 里精心写的模板成了死代码 |
| 28 | **已关闭的引擎被报成「当前引擎不支持 SQL 查询」** | `Engine` 的默认 `executeSql` / `querySql`（两个重载）在 `getExecutor()==null` 时直接抛 UOE，消息固定为「当前引擎不支持 …」；而 `close()` 之后数据源注册表被清空，`getExecutor()` 同样返回 `null`——**「已关闭」与「不支持」走了同一条分支** | 关闭后误调用任何 SQL 入口，用户看到的是「PostgresqlEngine 不支持 SQL 查询」，会去查能力声明而不是生命周期；与已修的 HBase（26）、Neo4j（24/25）、Elasticsearch（20）同属「错误语义」缺陷族 |
| 29 | **SQL 失败消息吞掉服务端原因** | `JdbcSqlExecutor` 的 5 处包装只写 `"执行查询失败: " + sql`，真正的诊断信息（`relation "t" does not exist`、`duplicate key value violates unique constraint`、`syntax error at or near`）只在 cause 链里 | 生产排障时必须逐层翻 cause 才知道「为什么失败」；阶段二十二第一次跑就撞上：断言只拿到「执行查询失败: SELECT * FROM t_acc_no_such_table」，服务端原因不可见 |
| 30 | **SQL Server 上「带 `ORDER BY` 的分页查询」整体不可用** | `JdbcSqlExecutor.count(...)` 把用户 SQL 原样包成 `SELECT COUNT(*) FROM (<sql>) t`。SQL Server 明确禁止派生表内出现 `ORDER BY`（除非同时带 `TOP` / `OFFSET` / `FOR XML`），于是统计总数这一步必失败——而「分页 SQL 带 `ORDER BY`」恰恰是分页最推荐、也最常见的写法（`pagination-requires-order-by=true` 的方言还专门要求它） | `queryPage(sql, …)` 在 SQL Server 上对任何带 `ORDER BY` 的 SQL 都抛「ORDER BY 子句在 … 派生表 … 中无效」；阶段二十三首次运行即暴露（`queryPage` 3 条断言全红，而 `Lambda page()` 因不统计总数仍通过，掩盖了面更广的问题）。计数本就不依赖顺序，属于包装方式选择错误 |

缺陷 10 的修法（`utils-support-questdb-starter`，`QuestdbSeriesEngine`）：

- 构造期由 `buildAuthHeader(token, username, password)` 一次性算出 `Authorization` 值：
  配了 token 用 `Bearer <token>`，否则配了用户名用 `Basic base64(user:pass)`，都没配为 `null`；
- `execQuery` 在请求构建处按需挂上该头，与写入端 `Sender` 的凭据语义**逐字一致**；
- 阶段十二用三条断言分别锁定：token→两侧都 `Bearer`、用户名密码→`/exec` 为预期的 `Basic`、
  未配凭据→**不发** `Authorization`（避免把空串当凭据发给服务端）。

缺陷 11 的修法（`utils-support-oracle-starter` / `utils-support-sqlserver-starter`）：

- 在两个 `addDataSource(DataSourceOptions)` 的**最前面**补上与同族引擎一致的三条校验，
  位置必须在 `new HikariDataSource()` 与 `dataSources.put(...)` 之前——否则非法入参
  会被注册成一个半成品数据源。阶段十四专门有一条断言验证「被拒绝后 `dataSourceNames()`
  里没有 poison」。

缺陷 12 的修法（`JdbcUrl.hostPort(host, port)`，10 处调用点统一替换）：

- 新增单点辅助方法：`port > 0 ? host + ":" + port : host`；
- postgresql / sqlserver / sqlserver-legacy / oracle / clickhouse / mysql 的 **同步与响应式**
  两侧共 10 处 URL 拼装全部改为调用它；
- 端口 > 0 的输出逐字节不变（无回归风险），端口 = 0 时交由驱动补默认端口，与
  `checkPort` 的 Javadoc 对齐。阶段十四对 5 个引擎各断言一次「URL 不含 `:0` 且形态精确」。

缺陷 15/16/17 的修法（`utils-support-redis-starter`）：

- **15**：`RedisEngine.getKeyPrefix` 改为 `AbstractEngine.resolveTableName(entityClass)`，
  与 Solr/JDBC/内存引擎共用同一套「`@TableName` 优先、否则驼峰转下划线」规则；
  该前缀同时用于 `store` 的键、`scanAll` 的 `prefix:*` 匹配与 `idx:` 索引名，改动一处即三处一致。
- **16**：`FT.SEARCH` 的 `catch` 分支不再直接返回 `scanAll(...)`，而是复用
  `MemoryWhereParser.parse(whereClause, params)` 得到谓词，在内存里过滤后再返回；
  无条件时仍走原来的全量返回（零额外开销）。
- **17**：匿名包装器补 `count()`，与 `list()` 走同一条已修正的路径，计数与过滤语义一致。

> 阶段十六用**隔离实验**证明这两条断言不是「为通过而写」：只修 15、不修 16 时，
> `eq(name,"alice")` 返回 3 行（期望 1 行）、`one()` 返回任意一行、条件计数为 3——
> 7 条断言失败并直接指出「回退扫描忽略了 WHERE 条件」；把 16 的修复放回后 18 条全绿。
> 同理，回退 15 的修复时 24 条断言失败（`store 写入 3 行，query 只读到 0 行`），
> 回退 17 时 `count()` 抛 UOE。

缺陷 18/19/20/21 的修法（`utils-support-elasticsearch-starter`）：

- **18**：抽出 `ElasticsearchEngine.indexName(Class<?>)`，内部直接委托
  `AbstractEngine.resolveTableName`，`search` / `searchPage` / `doSearch` 三处索引名与
  异常消息统一走它——与 Solr / Redis / JDBC / 内存引擎共用同一套表名规则；
- **19**：匿名包装器补 `count()`，`from=0`、`size=1` 走一次 search 并开启
  `track_total_hits`，取 `hits.total.value`（不做 `list().size()` 回退，避免默认 10 行上限）；
- **20**：`search` / `searchPage` 在宽 `catch` 之前先 `catch (IllegalStateException |
  UnsupportedOperationException e) { throw e; }`，把「用法错误/能力不支持」原样透出，
  其余异常仍带索引名包装；
- **21**：`client.bulk(...)` 与 `errors()` 判定拆开——只有请求本身失败才包装，
  部分失败直接抛带 `index` 与 `失败条数` 的异常。

> 阶段十七的隔离实验（**实际运行**）：把 ES 源码退回 HEAD、只 `mvn package` 产出
> `target/classes` 并将其置于 classpath 最前（不污染 `D:\maven-repo`），同一套断言
> 由 21/21 变为 **12 PASS / 9 FAIL**，失败点精确对应：
> `/accuser/_search`（18）、`/plainuser/_search`（18）、`count()` 抛 UOE（19）、
> `close()` 后仍抛泛化 `RuntimeException`（20）、`失败条数` 不在消息里（21）。
> 恢复修复后重新 `mvn -o clean install`，回到 21/21 全绿。

缺陷 22/23/24/25 的修法（`utils-support-neo4j-starter`）：

- **22**：匿名包装器补 `count()`，直接复用引擎已有的 `cypherCount`（`RETURN count(n) AS total`），
  不做 `list().size()` 回退；
- **23**：新增 `safeLabel(String)`（反引号包裹并转义），5 处 label 一律取
  `AbstractEngine.resolveTableName(entityClass)`——`@TableName` 优先、否则驼峰转下划线，
  与 Solr / Redis / ES / JDBC 共用同一套规则；
- **24**：新增 `ownsDriver` 标志——`addDataSource(Driver)` 注入的驱动视为**调用方所有**，
  `addDataSource(uri)` 与 `connect()` 建立的驱动视为**引擎所有**；`close()` 只在自有驱动时
  真正关闭，两种情形都把 `driver` 置 `null`，关闭后查询统一抛 ISE；
- **25**：删掉 4 处 `catch (RuntimeException e) { throw e; }`，统一包装为带
  `Neo4j 查询失败` + 具体 Cypher 的异常，并保留原始 `cause`。

缺陷 26 的修法（`utils-support-hbase-starter`，`HBaseEngine`）：

- 5 个领域 API 都把 `Connection c = conn();` 提到 `try` **之前**，`try` 内改用它——
  用法错误（未配置 / 已关闭 / 数据源不唯一）不再进入 `catch (Exception)`，
  而是原样抛出 `IllegalStateException("未配置 HBase 数据源")`；
- 服务端故障仍照旧包装为带表名/行键的 `HBase put 失败: …`（两类错误从此可区分）；
- 阶段二十对两侧各断言一次：`life.*` 断言**顶层消息必须精确等于**用法错误文案
  （不是「cause 里有」），`put/scan` 的故障断言仍要求带表名/行键。
- **修复前对照（实际运行）**：同一套断言在修复前为 **17 PASS / 6 FAIL**，
  其中两条正是 `HBase put 失败: t_user/r` 与 `HBase get 失败: t_user/r`；
  收紧断言并修产品后为 **23/23**。这组「先失败后通过」即断言有效性的直接证据。

缺陷 27 的修法（`utils-support-datasource-starter`，8 个方言 env）：

- `oracle.env` / `oracle11g.env` / `oracle12c.env` / `sqlserver.env` /
  `sqlserver2008.env` / `sqlserver2012.env` / `derby.env` / `derby1014.env`
  各补一行 `supports-limit=false`；
- 不改任何 Java 代码：`SqlDialect.supportsLimit()` 本就读取该键，
  `SqlDialect.processSql()` 本就实现了模板替换（`{sql}` / `{offset}` / `{limit}` /
  `{offset_clause}`）与 `pagination-requires-order-by` 回退（Oracle 12c 用
  `ORDER BY NULL`，SQL Server 用 `ORDER BY (SELECT NULL)`）——**缺的只是声明**；
- `JdbcEngine.wrapPagination` 由此在 Oracle / SQL Server / Derby 上改走模板分支：
  Oracle 19c/11g → ROWNUM 双层包装，Oracle 12c / SQL Server 2012+ → OFFSET…FETCH，
  SQL Server 2008 / Derby 10.14 → ROW_NUMBER 双层包装；
- 阶段二十一的**隔离实验（实际运行）**：把这 8 个 env 与 Java 一起 `git stash`
  （即回到 HEAD），`mvn -o clean install` 后同一套断言为 **23 PASS / 5 FAIL**，
  失败点精确为
  `oracle=true, oracle11g=true` / `sqlserver=true, sqlserver2008=true` /
  `derby=true, derby1014=true` / `Dialect.require("oracle").supportsLimit 应为 false` /
  `oracle supportsLimit=true`；恢复后重新构建回到 **28/28 全绿**。
  该实验同时证明：模板渲染本身早已正确（模板类断言在回退状态下仍通过），
  **唯一缺口就是能力声明**。

缺陷 28 的修法（`utils-support-common-starter`，`Engine` 接口的 3 个默认入口）：

- `executeSql`、`querySql(String, Object...)`、`querySql(String, Class, Object...)`
  在 `getExecutor()==null` 时，用既有的 `isClosed()` 区分两种原因：
  已关闭 → `引擎已关闭，无法执行 …`；未关闭 → 保持 `当前引擎不支持 …`；
- **异常类型不变**（仍是 `UnsupportedOperationException`），只修正消息，
  以免破坏调用方按「能力缺失」捕获的既有逻辑；
- 阶段二十二在 `sqlSuite` 关闭引擎后断言：消息必须含「已关闭」且**不得**含「不支持」。

缺陷 29 的修法（`utils-support-datasource-starter`，`JdbcSqlExecutor`）：

- 新增 `reason(Throwable)`：沿 cause 链取最深层原因消息（为空时退回异常类名）；
- 5 处包装（查询 / 类型化查询 / 更新 / 批量 / 统计）统一改为
  `"执行查询失败: " + sql + " -> " + reason(e)`，cause 仍然保留；
- 阶段二十二据此断言：查不存在的表时，消息里必须同时出现表名与服务端原因
  （PostgreSQL 为 `relation "…" does not exist`）。

缺陷 30 的修法（`utils-support-datasource-starter`，`JdbcSqlExecutor`）：

- 统计总数前先裁掉**最外层尾部** `ORDER BY`：
  `SELECT COUNT(*) FROM (<stripTrailingOrderBy(sql)>) t`；
- 扫描器跳过字符串常量、`"` / `` ` `` / `[]` 引号标识符与 `--`、`/* */` 注释，
  且只认**括号深度为 0** 的 `ORDER BY`——因此窗口函数 `OVER (ORDER BY …)`、
  子查询内排序、字面量里的 `"order by"` 都不会被误裁；
- 关键字判定要求前后是词边界，避免 `REORDER BY` 之类误判；
- 计数本就不依赖顺序，故对全部方言统一生效（不是只给 SQL Server 打补丁）；
- 阶段二十三用四条断言钉住：普通 `ORDER BY`、窗口函数、字面量、
  完全无 `ORDER BY`，另加一条带绑定参数的场景（裁剪后参数索引不受影响）。

> **契约差异（显式声明，不是缺陷）**：`store(name, rows)` 的 `name` 参数**不参与** label 推导，
> label 始终由实体类型决定。图数据库的 label 即实体类型，按任意 `name` 写入会产生
> 「只写数据」（没有读路径能解析出同一 label）。该行为由阶段十八第 5 条断言显式钉住，
> 以免后人误改成按 `name` 写标签。
>
> 阶段十八的隔离实验（**实际运行**）：把 Neo4j 源码退回 HEAD、`mvn -o clean package`
> 产出 `target/classes` 并置于 classpath 最前（**不覆盖** `D:\maven-repo`），同一套断言由
> 25/25 变为 **15 PASS / 10 FAIL**，失败点精确对应：`(:AccUser)` / `(:PlainUser)` 标签（23）、
> `count()` 抛 UOE（22）、`close()` 关闭了注入的驱动（24）、异常消息只剩
> `simulated server failure`（25）。恢复修复后重新 `mvn -o clean install`，回到 25/25 全绿。

## 5. 查证后**判定为非缺陷**的（避免后人误"修"）

1. **`ParquetEngine` 不支持 `querySql`** —— 其 `supportsSql()==false`，读路径是
   `AbstractEngine.executeNewQuery`（Lambda / 类型化查询面）。能力声明与行为**一致**，
   不是缺陷；用 `query(Doc.class).list()` 读取即正常。
2. **`TablesawEngine` 不认 `@TableName`** —— 该模块（`utils-support-tablesaw-starter`）
   只依赖 `utils-support-common-starter`，**不依赖** `utils-support-datasource-starter`，
   因此在构造上读不到 `com.chua.datasource.support.annotation.TableName`。
   表名一律由类名派生（`Doc` → `doc`）是该引擎的**模块边界**，不是漏实现。
   已在 `TablesawEngine.getTableName` 处加注释记录；若将来要与 JDBC/内存引擎共用注解约定，
   须先评估引入 `datasource-starter` 依赖对传递依赖面的影响，**不要顺手加**。
3. **`RedisEngine` 不实现 `Engine`** —— 它是 `RediSearchEngine` 的基类；
   SPI `redis` 键指向的是 `RediSearchEngine`，`RedisEngine` 本身不在 Engine 体系内。
4. **`RedisReactiveKvEngine` 不实现 `KvEngine`** —— 类 Javadoc 明示其是响应式便捷封装
   （同步接口无法在响应式链路上实现），不属于 `KvEngine` 子类。

## 6. 复跑方式

harness **已入库**（不再只留 `.tmp`，那里没有版本控制）：

```
utils-support-core-parent/utils-support-datasource-starter/src/acceptance/
├─ run-acceptance.bat          二十三阶段运行器（自动解析 classpath → 编译 → 逐阶段执行）
├─ run-postgres.bat            临时 PostgreSQL 实例（官方免安装二进制；start / stop / status）
├─ run-sqlserver.bat           本机 SQL Server Express 服务启停与 TCP 探测（start / stop / status）
├─ prepare-classpath.ps1       从 pom.xml 解析 classpath 并生成 lib\（硬链接/复制）
├─ pom.xml                     仅用于解析 classpath，不参与 reactor 构建
├─ harness/*.java              二十三个验收程序
└─ README.md                   覆盖范围、前置条件、交付纪律
```

```bat
cd D:\ch\project\utils-support-parent-starter\utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat              :: 二十三阶段全跑，末行 ACCEPTANCE_RESULT=PASS/FAIL
run-acceptance.bat 1 5          :: 只跑阶段一与阶段五
run-acceptance.bat 17           :: 只跑阶段十七（Elasticsearch）
run-acceptance.bat 18           :: 只跑阶段十八（Neo4j）
run-acceptance.bat 19           :: 只跑阶段十九（GreptimeDB）
run-acceptance.bat 20           :: 只跑阶段二十（HBase）
run-acceptance.bat 21           :: 只跑阶段二十一（方言分页 SQL）
run-acceptance.bat 22           :: 只跑阶段二十二（真实 PostgreSQL）
run-acceptance.bat 23           :: 只跑阶段二十三（真实 SQL Server）
run-postgres.bat start          :: 起临时 PostgreSQL 实例（阶段二十二前置）
run-postgres.bat stop           :: 关掉临时实例
run-sqlserver.bat start         :: 起 SQL Server Express 服务并探测 1433（阶段二十三前置）
run-sqlserver.bat stop          :: 停掉该服务
```

> **交付教训（2026-10-04 实际发生）**：仓库根 `.gitignore` 有 `*.ps1`（16 行）/
> `*.cmd`（97 行）/ `*.bat`（146 行）三条全局规则。第一次提交 harness 时用了
> `git add <目录>`，`.gitignore` **静默**剔除了 `run-acceptance.bat` 与
> `prepare-classpath.ps1` —— 提交报告显示"9 文件 1767 行"，看起来完整，
> 但**运行器根本不在 HEAD**，harness 只剩源码、无法复跑。
> 修法是在 `src/acceptance/.gitignore` 里对该目录显式求反
> （`!run-acceptance.bat` / `!prepare-classpath.ps1`）。
> **纪律**：提交"工具目录"后必须用 `git ls-files <目录>` 逐文件核对，
> 不能只看 `git commit` 的文件数——被 ignore 剔掉的文件不会报错、也不会出现在 `git status`。

前置条件：

- **pwsh 7** 在 PATH 上（仓库禁用 PowerShell 5.1：它按 ANSI 解码无 BOM 的 UTF-8 脚本）。
- JDK 21+。
- 阶段三 / 五需本机 Redis（Windows 服务名 `Redis`，`127.0.0.1:6379`）；阶段三需本机 MySQL
  （`127.0.0.1:3306`，`root/root`，库 `engine_accept`）。缺服务时对应套件失败，其余阶段不受影响
  ——**这正是「退出码比报告可信」的用处**：Redis 停机时阶段三/五会给出 25 条
  `Connection refused` 失败，一眼可辨是环境而非产品缺陷。
- 阶段二十二需 `127.0.0.1:55432` 的 PostgreSQL（`run-postgres.bat start`）；
  阶段二十三需 `127.0.0.1:1433` 的 SQL Server Express（服务 `MSSQL$SQLEXPRESS`，
  `run-sqlserver.bat start`，账号 `sa`，库 `engine_accept`，可经
  `MSSQL_HOST` / `MSSQL_PORT` / `MSSQL_DB` / `MSSQL_USER` / `MSSQL_PASSWORD` 覆盖）。
  两者都实现了**实例缺失即 `[SKIP]`**：打印 `STAGE22_SKIP=1` / `STAGE23_SKIP=1` 并以退出码 0
  结束，所以没有这两套服务端的机器也能跑完整套件（日志里能看到 SKIP，不会被当成已通过）。
- 打开被验模块的**新构建**：`src/acceptance/pom.xml` 引用的是本地仓库制品，
  改完产品代码必须先 `mvn install` 对应模块，否则验的是旧包（**报告 md5 就是为此存在**）。

## 7. 尚未覆盖（如实声明）

1. **仍需外部服务的 2 个引擎**（oracle、clickhouse）
   的**查询语义**只有契约面 + 方言 SQL 断言（阶段十四 / 二十一）；
   本机没有这两个服务端，**也没有任何容器运行时**（`docker` / `podman` / `nerdctl` / `wsl`
   均不存在），因此无法起容器做真实读写验收。
   **这部分是"未验"，不是"已通过"。**
   > `sqlserver` **已从本清单移出**：阶段二十三用原生 Microsoft SQL Server 2022 Express
   > 实例（`SQLEXPRESS`，TCP 1433，SQL 身份验证）跑了 39 条断言，真的建表/插入/查询/分页/
   > 类型映射/`NVARCHAR` 往返/`IDENTITY`/`DATETIME2`，并借它发现并修复了缺陷 30。
   > **仍未覆盖**的是企业版特性（列存储、分区、`MERGE` 之外的 T-SQL 扩展）、
   > 高可用/复制、权限模型与执行计划。这**不是容器**——用的是本机 Windows 服务实例。
   > `postgresql` **已从本清单移出**：阶段二十二用官方免安装二进制在本机 `initdb` 出
   > 真实 PostgreSQL 17.6 实例（端口 55432，`trust`，不注册服务、不写系统目录），
   > 真的建表、插入、查询、分页、类型映射、`JSONB`/`BIGSERIAL`/`TIMESTAMPTZ`，
   > 37 条断言全部打真实服务端（含 28 条通用 JDBC 套件）。
   > **仍未覆盖**的是多节点/复制、权限模型、`EXPLAIN` 计划与锁隔离级别。
   > 这**不是容器**——本机没有容器运行时，用的是本机进程 + 临时数据目录。
   > `prometheus` **已从本清单移出**：阶段八用本机 HTTP 桩（`com.sun.net.httpserver`）
   > 打通了 8 个 `/api/v1/*` 端点，`PrometheusEngine` / `PrometheusClient` 的查询、
   > 模型映射、Basic 认证与失败语义均已真实执行（35 条断言）。仍未覆盖的是
   > **真实 Prometheus 服务端的 PromQL 语义**（桩只回放固定响应）。
   >
   > `influxdb` 也已移出「仅契约面」，但**真实 InfluxDB 服务**仍未验：
   > 阶段九只覆盖引擎自身逻辑（见下一条）。
   >
   > `solr` **已从本清单移出**：阶段十五用记录型假 `SolrClient` 驱动了引擎的
   > 表名解析、查询构造、分页下推、`numFound` 计数、文档映射、写入/删除、
   > 失败语义与生命周期（28 条断言）。**仍未覆盖**的是真实 Solr 服务端的
   > 查询解析（QParser）、schema/动态字段与分布式行为——那属于服务端语义，
   > 且本引擎不自带 schema 管理。postgresql / sqlserver 已在阶段二十二/二十三
   > 用真实服务端验过查询语义；oracle / clickhouse 仍只在阶段十四验了配置层，见上方 §2.1。
   >
   > `elasticsearch` **已从「仅契约面」升级**：阶段十七用回放型假 `Transport`
   > 让官方客户端真实序列化请求、反序列化响应（21 条断言），索引名、查询体、
   > 分页、计数、`_source` 映射、bulk 写入与失败语义全部真实执行。
   > **仍未覆盖**的是真实 ES 服务端的查询语义（分词/打分/聚合）、索引与 mapping 管理、
   > 集群与副本行为。**注意：这不等于「已完成真实读写验收」**——没有 ES 服务端参与。
   >
   > `neo4j` **已从本清单移出**：阶段十八用 `java.lang.reflect.Proxy` 构建假
   > `Driver`/`Session`/`Transaction`/`Result`/`Record`/`Value`，让引擎真实拼出 Cypher、
   > 真实展开实体属性、真实解析回放记录（25 条断言）。**仍未覆盖**的是真实 Neo4j
   > 服务端的 Cypher 语义（索引/约束、执行计划、事务冲突）、以及驱动自身的网络层。
   > **这不等于「已完成真实读写验收」**——没有 Neo4j 服务端参与。
   > 已显式钉住的契约差异：`store(name, rows)` 的 `name` 不参与 label 推导（见 §4）。
   >
   > `greptimedb` **已从本清单移出**：阶段十九向 `DriverManager` 注册只认
   > `jdbc:greptimefake:` 的假 `Driver`，用 Proxy 实现 `Connection`/`PreparedStatement`/
   > `ResultSet`/`ResultSetMetaData`，让引擎真实渲染 SQL、真实绑定 `?` 参数、
   > 真实解析结果集（30 条断言）。**仍未覆盖**的是真实 GreptimeDB 服务端的
   > SQL 语义（tag/time 索引、TTL、区域与副本、upsert 冲突判定）与 gRPC 写入通道。
   > **这不等于「已完成真实读写验收」**——没有 GreptimeDB 服务端参与。
   >
   > `hbase` **已从本清单移出**：阶段二十用 `java.lang.reflect.Proxy` 构造假
   > `Connection`/`Admin`/`Table`/`ResultScanner`，`Put`/`Get`/`Delete`/`Scan` 用 HBase
   > 官方类（`KeyValue`/`Scan`）真实构造，`Result.create` 真实解析（23 条断言）。
   > **仍未覆盖**的是真实 HBase 集群的服务端语义（RegionServer 路由、列族压缩/TTL、
   > 过滤器与协处理器、行锁与多版本）。**这不等于「已完成真实读写验收」**——
   > 没有 HBase 集群参与。
2. ~~**`InfluxdbSeriesEngine` 只有契约面**~~ → **已升级**：阶段九用 `java.lang.reflect.Proxy`
   注入一个 `InfluxDB` 桩客户端（该引擎提供了公开的注入构造器，注释即写明用于「测试或外部生命周期管理」），
   把**引擎自身逻辑**全部真实执行（26 条断言）。**仍未覆盖**的是真实 InfluxDB 的
   InfluxQL 执行语义与线上 HTTP 协议——那部分由第三方 influxdb-java 客户端负责，不是本项目代码。
3. **`JdbcReactorEngine` 的两个 private 内部适配器**
   （`R2dbcEngineAdapter` / `UnifiedEngineAdapter`）无法从外部引用，未直接触达；
   其宿主 `JdbcReactorEngine` 的 JDBC 降级路径已由阶段六经 `ReactorFlywayBridge` 真实驱动
   （建表 / 插入 / 条件查询 / 类型化映射 / 计数 / 删除，全部走真实 SQLite 文件）。
4. ~~**`HttpDatalakeQueryEngine` 只做只读契约面**~~ → **已升级**：阶段十用本机 HTTP 桩
   （`com.sun.net.httpserver`）真实驱动 `getExecutor().query/queryPage/execute`（21 条断言）。
   **仍未覆盖**的是真实数据湖查询服务的 SDK/鉴权语义——协议面只有
   `POST {baseUrl}/query` + 纯文本 SQL + 原样响应体，已全部验证。
   **已记录的行为差异**：`close()` 后 `isClosed()` 为 true，但执行器未做关闭门控，
   仍可发起查询（`DatalakeHttpClient.close()` 本身是 no-op，无资源可释放）。
   接口未强制「关闭后拒绝」，故未改动产品代码，仅在此如实记录并在阶段十显式断言该行为。
5. `SeriesEngine.purge` 契约存在**自身歧义**：`0` 既被定义为「后端不支持」，又是「成功但删了 0 条」
   的自然返回值，调用方无法区分。本报告未改接口语义，仅记录。
6. **UI 增强已交付一部分**：`pages/datasource`（数据源中心）的死筛选缺陷已修复并入库
   （见第 8 节）。**仍未修复的一处可访问性缺陷**记录在第 8 节末尾，因该文件当时正被
   另一并发会话修改而未动，如实声明。
7. **`Engine.query()` 构建器只覆盖"查询"**：它只负责拼 SELECT，因此 `execute()` 的语义是
   "查询命中行数"；不提供链式 UPDATE/DELETE（那两条仍有 Lambda 包装器与原生语句通道）。

## 8. UI 增强（pages/datasource 数据源中心）

提交 `907ff1a2`（4 文件，+335 / −35，仅在 `vue-support-parent-starter/pages/datasource/`）：

1. **修复「异常」状态页签恒为空的死筛选**：左栏渲染了「异常」页签且可点击，但
   `filteredList` 只有 all / online / offline 三个分支，点进去永远是空列表；计数也写死为
   `null` 不展示。原注释称「现有数据模型没有『异常』的判定来源」——而 `api/index.ts` 的
   `DataSourceInfo` 早就有 `lastTestOk`（0 失败 / 1 连通 / null 未测试）。**错误的前提掩盖了缺陷。**
2. 抽出纯函数模块 `src/datasourceStatus.ts`（状态口径 + 筛选/计数），三态改为**互斥可加**
   （online + abnormal + offline === all），异常计数真实展示；详情头徽标与概览「运行状态」
   扩展为三态，新增 `.ds-detail__badge.is-warn` 复用 `--status-warning` 令牌（暗色自动适配）。
3. 新增 `tests/datasourceStatus.test.mjs`（`node:test`，9 例）并接入 `package.json` 的 `test` 脚本
   ——本包未装 vitest，用 Node 原生 TS 剥离 + `node:test` 做到**零新增依赖**。

验证（含敏感性对照）：

- `node --test tests/datasourceStatus.test.mjs` → 9/9 pass，EXIT=0；把 abnormal 分支改回恒 `false`
  （复现原缺陷）→ 3 例转红、EXIT=1，证明测试**真能拦住该缺陷**；
- `vue-tsc --noEmit -p pages/datasource/tsconfig.json` → EXIT=0；在模块里植入
  `const x: number = "s"` 探针 → EXIT=2 且精确指向该文件，证明检查器覆盖到位。

> **已知未修（待办）**：同页左栏的 `.ds-status__item` 是带 `@click` 的 `<li>`，**没有**
> `tabindex` / `role` / 键盘处理，因此键盘用户无法切换状态筛选，且 `ScDataSourceView.vue`
> 已有的 `.ds-status__item:focus-visible` 焦点环规则**永远不会触发**（元素不可聚焦，是死规则）。
> 修法：把 `li` 内改为 `<button type="button" :aria-pressed="...">`（或补
> `role="button"` + `tabindex="0"` + `@keydown.enter|space`）。
> 本轮**未改**：当时该文件已被另一并发会话加入了未提交的 elasticsearch 支持
> （`TYPE_META` / `typeDesc` / `defaultPort` 三处 + 新页面），按路径提交会把对方半成品
> 一并卷入，故只记录不动手。**注意**：本页的端到端浏览器验收需要
> `spring-support-datasource-hub-starter` 后端，本轮以「纯逻辑单测 + 模板/类型检查」定案，
> 未做浏览器验收。

## 9. 新增能力：`Engine.query()` 非 ORM SQL 文本构建器（阶段七）

需求：在 `Engine` 既有的 `update(...)`、`query(Class)` / `query(String, Object...)` 之外，
补一条**链式拼接 SQL 文本、但不做实体映射**的通路，可按
`select / table / view / where / orderby / groupby / innerjoin` 拼成一条语句再执行。

实现（均在 `utils-support-common-starter`）：

| 位置 | 内容 |
|---|---|
| `…engine/Engine.java` | 新增 `default SqlQueryBuilder query()`。与既有 `query(Class)`、`query(String,Object...)`、`query(String,Class,Object...)` **重载共存**（已 grep 确认无实现者声明无参 `query()`，无冲突） |
| `…engine/wrapper/SqlQueryBuilder.java` | 新建；`final class`，与 `LambdaQueryWrapper` / `JoinClause` / `QuerySql` 同包 |

能力面：

- 组成：`select` / `selectFunc` / `selectCount` / `table`(±别名) / `view`(±别名) /
  `innerJoin` / `leftJoin` / `rightJoin` / `where` / `and` / `or` / `groupBy` / `having` /
  `orderBy`(= Asc) / `orderByAsc` / `orderByDesc` / `limit` / `offset`；
- 终端：`list()` / `list(Class)` / `map()` / `map(Class)` / `execute()`（返回命中行数）/
  `listAsync()` / `executeAsync()`（返回 `Mono`，`Schedulers.boundedElastic()`）；
- 预览：`sql()` / `boundParams()`；`sql()` 未指定来源时抛 `IllegalStateException`。

**它不是 ORM**：不解析实体、不读字段注解，只把调用方给的标识符与条件片段拼成语句，
再交给引擎既有的 `querySql(String,Object...)` / `querySql(String,Class,Object...)` 通道执行。

安全口径：

- 会拼进 SQL **结构位**的标识符（来源表/视图、别名、投影列、分组列、排序列、JOIN 表名与别名、
  聚合函数名）全部先过 `SqlName.isSafe` 白名单，非法即 `IllegalArgumentException`；
  `*` 仅在投影列位点被显式放行；白名单只允许**一段** `schema.object` 限定，
  故 `a.b` 合法而 `a.b.c` 被拒（与 `SqlName` 既有口径一致）。
- 条件片段（`where` / `having` / JOIN `ON`）沿用 `JoinClause` 的既有约定，是**受控 SQL 片段**：
  值必须用 `?` 占位符并随方法参数传入以完成参数化绑定；绑定顺序为 **WHERE 参数在前、HAVING 参数在后**。

验证：

1. 单元测试 `…/engine/wrapper/SqlQueryBuilderTest.java`（JUnit 5，**37 例**）——
   用 `RecordingEngine implements Engine` 替身捕获实际下发的语句与参数，
   覆盖全子句渲染、注入拒绝、参数顺序、守卫、终端与替身交互。
   `mvn -o clean install -DskipTests=false`（common-starter）：**Tests run: 242, Failures: 0, Errors: 0**，
   BUILD SUCCESS（该模块 `junit-jupiter` 为 test 依赖）。
2. 阶段七 `harness/EngineQueryBuilder.java`（**37 条断言**）：
   - `render`：纯渲染与守卫，不触库；
   - `memory`：`InMemoryEngine` 真实执行（WHERE / ORDER BY / LIMIT+OFFSET / 类型化映射 / 异步）；
   - `h2` / `sqlite`：**两个真实方言**端到端执行——JOIN（inner/left）、GROUP BY + HAVING、
     `selectFunc` 聚合别名、VIEW 作为来源、分页切片、执行行数、异步终端。
   去重说明：`schemaQualifiedNamesAreAccepted` 一类断言在单测里已覆盖，harness 只做跨引擎端到端。

## 10. 查证后判定「不修改」：`Engine.create()` 的 SPI 单例缓存与 `close()` 毒化

阶段七初版把「render」与「memory」两个套件各自 `Engine.create("memory")` 并**各自关闭**，
结果第二个套件直接报 `IllegalStateException: 引擎已关闭，无法执行写入内存表 …: InMemoryEngine`。
据此定位到如下**真实行为**（非任何 `Engine` 子类自身的缺陷）：

- `Engine.create(String)` → `ServiceProvider.of(Engine.class).getExtension(type)` →
  `DefaultServiceProvider.getExtension` → `ServiceDefinition.getObj(...)`；
  后者按 (SPI 类型, 扩展键) **缓存单例**（`volatile Object obj` + `isLoaded`）。
- 因此同一 JVM 内对同一键反复 `Engine.create(key)` 拿到的是**同一实例**；
  该实例一旦 `close()`（`AbstractEngine` 用实例级 `AtomicBoolean closed` 置位并清空 dataSources/dataStores），
  后续 `Engine.create(同一键)` 返回的仍是**已关闭**的同一实例，再用即抛 `IllegalStateException`。

**为何本轮不改**（避免把一个框架级约定当成局部 bug 去"修"）：

1. 这是仓库范围内的统一约定，而非 `Engine` 独有：`ReactorEngine.create` 同样用 `getExtension`。
   只改 `Engine.create` 会让两者语义分叉。
2. 改成 `getNewExtension`（每次新建）会改变 `Engine` 全部 24 个实现的**共享状态语义**
   （例如先 `create` 再 `addDataSource` 后，别处再 `create` 是否还能看到该数据源），
   属于产品级决策，不应在验收环节顺手改。
3. 接口 Javadoc 的既有用法是**持有一个引用、末尾 `close()`**（见 `Engine` 类注释示例），
   该用法在两种语义下都正确；没有任何文档承诺"重复 create 得到可用的新实例"。
4. 已确认**没有**任何 Engine 实现标注 `@SpiDefault`（playwright 的 `JavaEngine` 虽标了，
   但其未登记在 `META-INF/extensions/…engine.Engine` 中，不可被发现），
   故未命中扩展键时的回落行为在两种实现下一致，`Engine.create("__no_such_engine__")`
   仍返回 null（`EngineEnhanceExample` 依赖此点）。

harness 侧的处置：阶段七改为**只创建一次内存引擎，两个套件共用，末尾统一关闭**，并在
`EngineQueryBuilder.main` 就地注释说明原因。

> 若产品后续决定让 `create` 每次返回新实例，改法是一行：把
> `Engine.create(String)` 的 `getExtension(type)` 换成 `getNewExtension(type)`
> （`ReactorEngine.create` 需同步）；届时本报告第 3 节阶段七的断言需重跑确认。

## 11. Prometheus 从「契约面」升级为「真实执行」（阶段八）

`PrometheusEngine` 原先只做契约面，理由是「本机无 Prometheus 服务」。复核后确认：
它的底层 `PrometheusClient` 是**纯 JDK `HttpClient` + 一组固定端点**，不依赖任何
Prometheus SDK，因此可以用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起一个桩服务，
把整条链路真实跑通，无需外部依赖。

桩覆盖的 8 个端点与对应验证点：

| 端点 | 桩返回 | 验证点 |
|---|---|---|
| `/api/v1/query` | `resultType=vector` | 标签映射、`getName()`、值、浮点秒截断为整秒 |
| `/api/v1/query_range` | `resultType=matrix` | `values` 多样本、`start/end/step` 查询串 |
| `/api/v1/series` | 标签对象数组 | `match[]` 编码、Map 化 |
| `/api/v1/label/names` | 字符串数组 | 标签名列表 |
| `/api/v1/label/job/values` | 字符串数组 | 路径段白名单与编码 |
| `/api/v1/targets` | `activeTargets` | RFC3339 → epoch 毫秒、`lastScrapeDuration` |
| `/api/v1/rules` | `groups[].rules` | 分组/文件/类型/时长/标签 |
| `/api/v1/alerts` | `alerts` | 状态/值/`activeAt`/标签 |

失败语义（都由桩切换返回模式触发，断言不静默）：

| 场景 | 期望 |
|---|---|
| HTTP 500 | `RemoteExecutionException` |
| 响应非 JSON | `RemoteExecutionException` |
| `status=error` | `RemoteExecutionException` |
| `resultType=graph` | `UnsupportedOperationException` |
| 空 promql / 非法标签名 / 缺 match / 非法 range·step·lastMinutes | `IllegalArgumentException` |
| 不支持 http(s) 的 baseUrl、timeout≤0 | `IllegalArgumentException` |

另外两条容易被漏掉的契约也覆盖了：

- **Basic 认证头**：`userId:password` → `Base64`，断言桩收到的 `Authorization` 值精确匹配。
- **关闭语义**：`PrometheusClient.close()` 与 `PrometheusEngine.close()` 均幂等；
  关闭后 `client()` / `addDataSource(...)` / `setDefaultDataSourceName(...)` 明确抛
  `IllegalStateException`，而不是返回 null 或静默失效。

还做了**跨特性交叉验证**：`PrometheusEngine.query().table("up").list()` 必须抛
`UnsupportedOperationException` —— 证明阶段七引入的构建器把能力判定**推迟到终端**，
不会在拼接期假装支持，也不会在非 SQL 引擎上返回空结果掩盖问题。

> **仍未覆盖（如实声明）**：桩是固定响应回放，因此**真实 Prometheus 的 PromQL 语义**
> （选择器匹配、函数计算、时间对齐、告警规则求值）不在本阶段证明范围内；
> 本阶段证明的是客户端/引擎的**协议实现、映射与失败语义**正确。

## 12. `InfluxdbSeriesEngine` 从「契约面」升级为「引擎逻辑全量执行」（阶段九）

`InfluxdbSeriesEngine` 原先也只有契约面。复核后确认它有**公开的注入构造器**
`InfluxdbSeriesEngine(InfluxDB, String)`，注释写明用于「测试或外部生命周期管理」——
这正是本项目为自己预留的接缝。于是用 `java.lang.reflect.Proxy` 动态实现
`InfluxDB` 接口作为桩客户端，把引擎自身的逻辑全部真实跑起来。

**这不是 mock 断言**：桩只做两件事——记录收到的 InfluxQL、按语句返回构造好的
`QueryResult`。所有判定、拼装、解析、聚合都由被测引擎真实完成。

覆盖点：

| 面 | 断言内容 |
|---|---|
| 装配 | 客户端为空 / 数据库名为空 / SPI 缺 `url`+`database` 三种「不可用」，且不得发起连接 |
| 写入 | `Point` 行协议 `measurement,monitor_id=<id> field=<v> <ns>`；毫秒→纳秒转换；`writeBatch` 逐点委托 |
| 标识符 | `SqlName.isWord` 白名单：`a-b` / `a;drop` / `a b` / `a"` / 空 / `a.b` / 含制表符全部拒绝，**且在触达客户端之前**拒绝 |
| 标识符（正例） | 数字开头（`1metric` / `2field`）**合法**——`isWord` 是 `[A-Za-z0-9_]{1,128}`，这是既有契约，不是放宽 |
| 指标发现 | `SELECT * FROM "t" WHERE "monitor_id" = '<id>' LIMIT 1`；跳过 `time` 与首列；结果按字典序升序 |
| 曲线 | 显式 `metricName` 时只查单 field（不再探测）；时间范围片段 `time >= …ms AND time <= …ms` |
| 降采样 | 同窗口取均值（10/20 → 15）、跨窗口按桶起点升序、桶起点对齐 `floor(ts/window)*window` |
| 时间解析 | 数字按毫秒；字符串按 RFC3339（`2024-01-01T00:00:00Z` → 1704067200000）；无法解析的点丢弃但**不影响其余点** |
| 歧义 | `metricName == null` 且存在多个 field 时**必须抛 `IllegalArgumentException`** 并列出候选（排序后），严禁合并成一条曲线 |
| 失败 | 结果级 `error`、序列级 `error`、客户端返回 `null`、客户端抛异常，四种都转 `IllegalStateException`，**绝不降级为空曲线** |
| 清理 | `DELETE FROM "t" WHERE time < <ms>`，`monitorId` 为空时不加 tag 条件；成功返回 **-1**（已清理但条数不可统计），不能返回 0 |
| 生命周期 | `close()` 关闭客户端、`isAvailable()` 转 false、可重复调用；关闭后写抛 `ISE`、读空、清理 0，且**零客户端调用** |

> **仍未覆盖（如实声明）**：真实 InfluxDB 的 InfluxQL 执行语义与线上 HTTP 协议
> （由第三方 influxdb-java 客户端负责）。本阶段证明的是**本项目引擎代码**的正确性。

## 13. `HttpDatalakeQueryEngine` 查询/执行路径真实化（阶段十）

该引擎原先只做只读契约面。复核后确认它的协议面极窄，完全可以桩验证：

- 请求：`POST {baseUrl}/query`，`Content-Type: text/plain; charset=utf-8`，**SQL 原文即请求体**；
- 响应：**原样返回**，由 `DefaultDatalakeHttpClient.parseRows` 解析为行集合；
- 执行：同一端点，响应体须是整数（受影响行数）。

于是用 `com.sun.net.httpserver.HttpServer` 起桩，真实跑通全链路。

| 面 | 断言内容 |
|---|---|
| 请求契约 | 方法 `POST`、路径 `/query`、`Content-Type` 含 `text/plain`、请求体与 SQL（含中文）**逐字节相等**（证明 UTF-8 原样传输） |
| 行解析 | JSON 数组 → `List<Map>`，**保序**；标量类型映射 `int→Integer` / `1.5→Double` / `true→Boolean` / `null→null` |
| POJO | `query(sql, Row.class)` 经 Jackson 映射为实体 |
| 分页 | 端点无分页通道，`queryPage` 在客户端切片并回填 `total=总行数`；越界页为空、`pageSize=0` 为空、`pagination==null` 返回整表 |
| 参数 | 带绑定参数一律 `UnsupportedOperationException`（端点无参数通道，**拒绝静默丢弃**） |
| 受影响行数 | `execute` 解析响应体整数；非数字响应抛 `IllegalStateException`（**不得当作 0 行**） |
| 畸形响应 | 非 JSON / 非数组 / 数组含非对象 / 空响应 → `IllegalArgumentException`，不得返回空列表冒充「无数据」 |
| 错误 | HTTP 500 → `IOException` → 执行器转 `IllegalStateException` |
| 地址 | `baseUrl` 空/空白/非 http(s)/无 scheme/含空格 → `IllegalArgumentException`；尾部多个 `/` 被规整，不产生 `//query` |
| 只读 | `store` / `query(Class)` / `update` / `delete` / `meta` / `addDataSource` 全部 `UOE` |

**已记录的行为差异（未改动产品代码）**：`close()` 后 `isClosed()==true`，但 `HttpSqlExecutor`
未检查该标记，仍能发起查询。`DatalakeHttpClient.close()` 本身是 no-op（JDK `HttpClient`
无需关闭），且 `Engine` 接口只要求 `isClosed()` 反映真实状态、未强制「关闭后拒绝调用」。
因此判定为**一致性差异而非缺陷**：与 `AbstractEngine` 系引擎的行为不一致，但无资源泄漏、
无错误结果。阶段十以一条显式命名的断言记录该行为，避免它被静默遗漏。

## 14. 补上两个从未验收的 SeriesEngine（阶段十一 / 十二）

追查「所有 `SeriesEngine` 子类」时发现：SPI 注册文件里共 5 个键
（`datasource` / `influxdb` / `questdb` / `rocksdb` / `redis`），而报告此前只覆盖了
`datasource` / `redis` / `influxdb` 三个 + 非注册的 `Noop`，即
**`rocksdb` 与 `questdb` 两个实现一次都没有被验过**，却被写成「4 个全覆盖」。
两者都可在本机做真实执行验收，故补齐。

### 14.1 `RocksDbSeriesEngine`（阶段十一，17 条，真实嵌入式 RocksDB）

RocksDB 是嵌入式库，数据落本地目录，无需外部服务——本阶段**没有使用任何桩**：
真实打开数据库、真实写点、真实前缀扫描、真实删除。

| 面 | 断言内容 |
|---|---|
| 装配 | `engine()=="rocksdb"`；`path` 为空/空白/缺省时 `isAvailable()==false` |
| 不可用语义 | 写抛 `ISE`；`metrics` / `series` 返回空；`purge` 返回 0 |
| 守卫 | 必填参数缺失 → `IAE`；`a-b` / `a:b` / `a;drop` / `a b` / 空 / `a.b` 全部拒绝，且**不留下索引** |
| 键布局 | 同一 `(target, monitorId, metric)` 的点按时间字节序排列，`SER:1:…` 前缀扫描 + 范围裁剪 |
| 降采样 | 同 1 分钟桶 10/20 → 15；跨桶按桶起点升序；桶起点 = `floor(ts/window)*window` |
| 覆盖 | 同一时间戳重复写入 → 单点覆盖（RocksDB 键唯一），值为后写者 |
| 隔离 | monitor 7 / 8 的同名指标互不串曲线 |
| 范围 | 1 小时窗口裁掉 3 小时前的点；4 小时窗口保留 |
| 指标发现 | 按 monitor 收窄、字典序升序；`metricName==null` 且唯一 → 自动推断；多指标 → `IAE` 并列出候选 |
| 清理 | 返回**真实删除条数**（2 / 0）；清空某指标后索引被回收（`metrics` 不再报告），其它指标不受影响；`monitorId==null` 跨 monitor 清理 |
| 持久化 | `close()` 后在同一目录新建实例，点与索引都还在 |
| 生命周期 | `close()` 幂等；关闭后写抛 `ISE`、读空、清理 0 |

### 14.2 `QuestdbSeriesEngine`（阶段十二，30 条，本机 HTTP 桩）

两条链路都是 HTTP：写入走官方 `Sender`（ILP）→ `POST /write?precision=n`；
查询/指标发现/清理走 JDK `HttpClient` → `GET /exec?query=…`。
用 `com.sun.net.httpserver.HttpServer` 起桩即可**真实执行**，无需 QuestDB 服务。

| 面 | 断言内容 |
|---|---|
| 写入报文 | `cpu,monitor_id=7 cpu_usage=12.5 <纳秒>` **逐字节相等**；`precision=n`；每点一次 POST（同步 flush，不攒批） |
| 写入失败 | 桩返回 500 → `ISE` 且消息含 target/metric（**绝不静默丢点**）；恢复后自动重建 sender 并写成功 |
| 地址 | 无 scheme / 多个尾斜杠被归一化后仍打到 `/exec`；无法解析 host → 不可用且不抛异常 |
| 鉴权 | token → `/write` 与 `/exec` **双侧** `Bearer`；用户名密码 → `/exec` 为 `Basic base64(user:pass)`；未配凭据 → **不发** 头 |
| 探测 SQL | `SELECT * FROM "t" WHERE "monitor_id" = 'n' LIMIT 1`；`monitorId==null` 时不加 WHERE |
| 列收窄 | 只报数值类型列；按首行样本剔除 null 列；结果排序 |
| 失败语义 | HTTP 500（含后端 error 文本）/ 非 JSON / 空响应 / 表不存在，各自按契约转 `ISE` 或空结果 |
| 曲线 | 先 `SELECT * … LIMIT 1` 定位设计时间戳列，再按 `ts ∈ [now-hours, now]` 查询；ISO 边界跨度恰为 `hours`；显式指标只查该列 |
| 时间 | RFC3339 字符串 → 毫秒；epoch 微秒 → `/1000`；无法解析的时间与非数值点被丢弃（留 warn），其余保留 |
| 歧义 | `metricName==null` + 多指标 → `IAE` 列候选，且**不再发查询** |
| 清理 | `DELETE` 按时间与 tag 过滤；`monitorId==null` 不加 tag；成功返回 **-1**（条数不可统计，不能返回 0）；表不存在返回 0 |
| 生命周期 | `close()` 幂等；关闭后写抛 `ISE`、读空、清理 0，且**零 HTTP** |

> **仍未覆盖（如实声明）**：QuestDB 的真实 SQL 语义、ILP 解析与列类型推断（由真实服务端负责）；
> 桩是固定响应回放，本阶段证明的是**本项目引擎的协议编码、SQL 拼装、映射与失败语义**。
> 另：`purge` 的 `metricName` 只做白名单校验、不进入 DELETE 条件——QuestDB 是宽表布局
> （一指标一列），按时间删行必然连带同时间戳的其它指标，强行按 metric 过滤反而会留下
> 半行数据；该语义已在阶段十二以显式断言固定下来，不是遗漏。

### 14.3 `InfluxDbEngine` 从「仅契约」升级为深度功能（阶段十三，16 条）

`InfluxDbEngine` 是 SPI 键 `influxdb` 指向的 **Engine**（别与阶段九的
`InfluxdbSeriesEngine` 混淆）。它的名字容易让人以为需要 InfluxDB 服务，实际实现是
**纯内存**：`store(name, data)` 写入 + `MemoryWhereParser` 求值，因此本机即可完整验收。

| 面 | 断言内容 |
|---|---|
| 能力自洽 | `supportsSql()==false`、`getExecutor()==null`、`querySql` 抛 `UOE`；未注册数据源时 `dataSourceNames()` 为空 |
| 表名解析 | 按实体 `@TableName` 命中；非 `default` 的无关表**不串读**；无表名数据回退到 `default` |
| 覆盖语义 | 同名重复 `store` 为覆盖而非追加 |
| 查询 | `eq` 命中唯一行且字段映射完整；`gt` + `orderByDesc` 同时生效；无匹配返回空而非全表 |
| 分页 | `page(1,2)`/`page(2,2)` 不重叠；**skip 先于 limit**（1000 行第 500 页恰为 999/1000）；越界页为空 |
| 写守卫 | 空条件 `update`/`delete` 被 `IllegalStateException` 拒绝且 **数据不变**；带条件时真实生效且只命中目标行 |

> 该阶段**修正了两条我自己写错的断言**（`age>26` 的样例数据只有 3 行；内存引擎并非不支持
> `update`/`delete`）——记录在此，因为它说明「验收代码也会错」，断言必须先与实现/数据对齐，
> 不能拿别处的期望值硬套。

### 14.4 5 个「无服务端」JDBC 引擎的本机可验面（阶段十四，45 条）

postgresql / sqlserver / sqlserver-legacy / oracle / clickhouse 的**查询语义**仍然无法
真实验收（没有服务端，报告 §7 已声明）。但下面四层行为不需要服务端，且每一层出错都是
生产事故，因此被单独立为阶段十四，每个引擎 9 条、共 45 条：

| 层 | 断言 |
|---|---|
| 连接串 | URL **逐字符**精确（5 种方言各自的 `jdbc:` 前缀、参数、jTDS 的 `;selectMethod=cursor`）；用户名/密码透传；**密码不进入 URL**（避免日志与异常栈泄漏） |
| 入参守卫 | host 的 7 种非法形态（null / 空 / 空白 / `evil?allowLoadLocalInfile=true` / `a/b` / `host;param=1` / `u@host`）、4 种越界端口、5 种非法库名（含 `db?autoDeserialize=true`）全部抛 `IAE` |
| 半成品 | 被拒绝的注册**不得**在 `dataSourceNames()` 里留下痕迹（校验必须先于注册） |
| 端口 0 | 5 个引擎各断言 `URL 不含 ":0"`，且形态为 `jdbc:xxx://host/db`（缺陷 12 的回归锁） |
| 失败语义 | 连 `127.0.0.1:1`（必然拒绝）：**必须抛异常**，返回空集合即判失败；失败须在 10s 内（实测 5ms ~ 3.1s） |
| 关闭语义 | `close()` 后 `HikariDataSource.isClosed()==true`（线程不泄漏）、重复 `close()` 不抛、`dataSourceNames()` 清空 |

> 本阶段**发现了两个真缺陷**（第 11、12 条），且都是「读代码看不出来、一测就现形」的类型：
> Oracle 少三条校验、端口 0 的文档契约在 10 处拼装点全部失效。
> 这 5 个引擎的真正查询语义仍未验证——不要因为本阶段全绿就认为它们「已通过」。

### 14.5 Solr 引擎：用记录型假客户端驱动真实路径（阶段十五，28 条）

Solr 引擎的关键事实是：`addDataSource` 接受**任意** `SolrClient`，而 `SolrClient`
只有一个抽象方法 `request(...)`。因此验收里实现一个「记录型假客户端」，
覆写引擎真正调用的 `query/add/commit/deleteByQuery/close`，就能让引擎照常构建
`SolrQuery`、照常映射返回文档——验的是**本项目代码**，不是第三方客户端。

| 面 | 断言内容 |
|---|---|
| 表名解析 | `@TableName("t_acc_user")` 生效（此前被忽略）；无注解 `PlainUser` → `plain_user`；**`store` 写入集合与 `query` 读取集合一致**（缺陷 13 回归锁） |
| 查询构造 | 无条件 → `*:*`；`eq(name,"alice")` → `name:alice`；两个条件 → `name:alice AND age:30` |
| 分页下推 | `list()` 未指定时 `rows=1000`；`one()` → `rows=1`；`page(3,20)` → `start=40/rows=20`；`page().total` 取自 `numFound`（137）而非当前页行数 |
| 计数 | `count()` 返回 `numFound`（42）；**不被 1000 行上限截断**（5000 → 5000）；以 `rows=0` 只取总数（缺陷 14 回归锁） |
| 文档映射 | `Long/String/Integer` 正确映射；多文档顺序保持；缺失字段保持 `null`；多值字段取首值 |
| 写入 | 空列表不触达客户端；`add`+`commit` 集合名正确且字段来自 getter；客户端异常包装为 `RuntimeException` 且带集合名 |
| 删除 | 命中 0 行**不发** `deleteByQuery` 并返回 0；命中 3 行时集合名/条件正确且提交 |
| 失败语义 | 未配置客户端 → `IllegalStateException`；客户端异常 → `RuntimeException` 且消息含集合名与查询、`cause` 保留；`executeUpdate/executeDelete` 抛 UOE |
| 生命周期 | `close()` 关闭底层客户端、重复调用不抛；无执行器 → `supportsSql()==false` 且 `querySql` 抛 UOE |

> 两个「一测就现形」的缺陷：集合名绕过 `@TableName`（写进去读不出来）、`count()` 直接抛 UOE。
> 还记录一个非缺陷事实：`close()` 会把底层客户端关两次（引擎直关 + 数据源关闭），
> `SolrClient` 与 `HikariDataSource` 的 `close` 都幂等，故不构成缺陷，断言里已显式说明。
>
> **仍未覆盖**：真实 Solr 服务端的查询解析（QParser）、schema/动态字段、
> 分布式与副本行为——那属于服务端语义，且本引擎不自带 schema 管理。

### 14.6 RediSearchEngine：真实 Redis 上的实体 CRUD（阶段十六，18 条）

这是本报告里少数几个**真实服务端读写**的引擎阶段（另一个是 mysql）。本机 Windows 版
Redis 不支持 `MODULE` 命令，即没有 RediSearch 模块——因此 `FT.SEARCH` 必然失败，
所有查询都走 `scanAll` 回退路径。这恰好让回退路径的缺陷暴露出来。

| 面 | 断言内容 |
|---|---|
| 表名/前缀 | `@TableName("t_redis_user")` 生效：写入后 Redis 里存在 `t_redis_user:1`；无注解 `PlainUser` → `plain_user`；**store 写入的前缀与 query 读取的前缀一致**（缺陷 15 回归锁） |
| 字段映射 | 哈希里的字符串还原为 `Long/String/Integer` |
| 条件过滤 | 单条件命中 1 行；多条件 AND 命中 1 / 0；无命中返回空列表；`one()` 返回唯一命中行（缺陷 16 回归锁） |
| 计数 | 全量 3、条件 1、无命中 0（缺陷 17 回归锁） |
| 分页 | `page(1,2)` → total=3 / 2 行；`page(2,2)` → total 仍 3 / 1 行（SCAN 顺序无关，故不断言行序） |
| 能力声明 | `update()`/`remove()` 明确抛 UOE（不静默失败）；无执行器 → `supportsSql()==false` 且 `querySql` 抛 UOE |
| 生命周期 | `close()` 幂等；原生命令 `execute("DEL key")` 可执行 |
| 隔离 | 每条断言前后按前缀清理键，可重复运行 |

> 三个缺陷的**独立证据**（而不是「改完就绿」）：
> - 回退缺陷 15 → 24 条失败，其中 `store 写入 3 行，query 只读到 0 行`；
> - 只修 15、回退 16 → 7 条失败，`eq(name,"alice")` 返回 **3 行**（期望 1），
>   `one()` 返回任意一行，条件计数 3；
> - 回退 17 → `count() 需由引擎实现类重写` 的 UOE。
>
> **仍未覆盖**：真实 RediSearch 模块下的 `FT.SEARCH`/`FT.AGGREGATE` 语义（本机无该模块）、
> 条件更新与条件删除（引擎明确不支持，直接抛 UOE）。

### 14.7 ElasticsearchEngine：回放型假 Transport 驱动真实链路（阶段十七，21 条）

本机没有 ES 服务端。但引擎的 `addDataSource` 接受**任意** `ElasticsearchClient`，
而该客户端的唯一出站口是 `ElasticsearchTransport`——一个只有 4 个抽象方法的接口
（`performRequest` / `performRequestAsync` / `jsonpMapper` / `options`）。因此验收里实现
一个「回放型假 Transport」：

- 引擎照常构建 `SearchRequest` / `BulkRequest`；
- 官方客户端照常序列化请求、反序列化响应（`Endpoint.responseDeserializer()`）；
- 假 Transport 只做两件事：**记录真实请求 URL / 请求模型**，**回放固定 JSON**。

这样验到的是「本项目引擎 + 官方客户端」的真实链路，而不是口头声明。

| 面 | 断言内容 |
|---|---|
| 索引名 | `@TableName("t_acc_user")` → 请求 URL 为 `/t_acc_user/_search`；无注解 `PlainUser` → `/plain_user/_search`；**不再出现 `accuser`**；`list`/`one`/`page` 三入口索引名一致（缺陷 18 回归锁） |
| 查询构造 | 无条件 → `match_all`；`eq(name,"alice")` → `term/name/alice`；`one()` → `size:1`；`page(2,3)` → `from:3 / size:3` |
| 计数 | `count()` 返回 `hits.total.value`（201）且请求体含 `track_total_hits`（缺陷 19 回归锁） |
| 分页 | `page(1,2)` → `total=201`、当前页 2 行 |
| 映射 | `_source` → 实体（`Long/String/Integer`）；缺失字段保持 `null` |
| 写入 | 空列表不触达客户端；`store("t_acc_user", 2 行)` → `/_bulk` 且两条操作的 `_index` 均为 `t_acc_user`、文档类型与内容正确 |
| 失败语义 | 未配置客户端 → `IllegalStateException`（缺陷 20 回归锁）；传输异常 → `RuntimeException` 且消息含索引名、`cause` 保留；bulk `errors=true` → 消息含索引与 `失败条数`（缺陷 21 回归锁）；`update`/`delete` → UOE |
| 生命周期 | `close()` **不关闭注入的客户端**（所有权归调用方）；`close()` 后查询抛 ISE、重复 `close()` 不抛；能力声明自洽 |

> **隔离实验（实际运行）**：把 ES 源码退回 HEAD 版本，`mvn -o clean package` 得到
> `target/classes`，仅将该目录置于 classpath 最前（**不覆盖** `D:\maven-repo` 中的制品），
> 同一套断言由 21/21 变为 **12 PASS / 9 FAIL**：
> `/accuser/_search`、`/plainuser/_search`、`count()` 抛 UOE、
> `close()` 后仍抛泛化 `RuntimeException`、`失败条数` 不在消息里。
> 恢复修复后重新 `mvn -o clean install`，回到 21/21 全绿。
>
> **仍未覆盖**：真实 ES 服务端的查询语义（分词/打分/聚合）、索引与 mapping 管理、
> 集群与副本行为——那属于服务端语义，且本引擎不自带索引管理。

### 14.8 Neo4jEngine：动态代理假 Driver 驱动真实 Cypher 生成（阶段十八，25 条）

本机没有 Neo4j 服务端。但引擎的 `addDataSource` 接受**任意** `org.neo4j.driver.Driver`，
而 `Driver` / `Session` / `Transaction` / `TransactionContext` / `Result` / `Record` / `Value`
**全部是接口**——这正是可以用 `java.lang.reflect.Proxy` 精确替身的地方：

- 引擎照常拼 Cypher、照常把实体展开成属性 Map、照常把 `Record` 映射回实体；
- 假驱动**记录每一条实际执行的 Cypher 与每个参数**，并按 Cypher 形态回放固定记录
  （`count(n) AS total` → 计数记录、`RETURN n` → 行记录、写入语句 → 空结果）；
- 断言因此聚焦「引擎到底发了什么、又如何解释结果」，而不是第三方驱动的网络层。

| 面 | 断言内容 |
|---|---|
| label | `@TableName("t_acc_user")` → `MATCH (n:`t_acc_user`)`；无注解 `PlainUser` → `:plain_user`；**写入与读取 label 一致**；不再出现 `AccUser`（缺陷 23 回归锁） |
| 标签契约 | `store("whatever_name", …)` 仍写实体 label，`name` 不参与推导（行为显式钉住，见 §4 契约差异） |
| 查询构造 | 无条件 → `MATCH (n:…) RETURN n`；`eq` → `WHERE n.name = $p0` 且值不内联；多条件 `AND` 且参数编号递增；`one()` → `LIMIT 1`；`page(2,3)` → `SKIP 3 LIMIT 3`；`orderByDesc` → `ORDER BY n.name DESC` |
| 计数 | `page().total` 与 `count()` 均走 `count(n) AS total`，`count()` 返回真实值（缺陷 22 回归锁） |
| 映射 | 节点属性 → 实体（`Integer`/`Number` → `Long`/`Integer`）；缺失属性保持 `null` |
| 写入 | `UNWIND $rows AS row CREATE (n:…) SET n = row`，`rows` 为属性 Map 列表且同 getter 一致；空列表不触达驱动 |
| 更新/删除 | `SET n.name = $set0` + `RETURN count(n) AS updated`，返回 counters；`DETACH DELETE n` + `RETURN count(n) AS deleted` |
| 原生执行 | `execute(cypher, args…)` 不改写 Cypher，位置参数转 `$p0`，返回 `nodesCreated+nodesDeleted+relationshipsCreated+relationshipsDeleted` |
| 失败语义 | 未初始化 → ISE（消息含「驱动未初始化」）；驱动异常 → `RuntimeException` 消息含「Neo4j 查询失败」与 label、`cause` 保留（缺陷 25 回归锁） |
| 生命周期 | `close()` **不关闭注入的 `Driver`**（缺陷 24 回归锁）；`close()` 后查询抛 ISE、重复 `close()` 不抛；无执行器 / `supportsSql=false` / `supportsMeta=false` / `querySql` 抛 UOE |

> **隔离实验（实际运行）**：把 Neo4j 源码退回 HEAD，`mvn -o clean package` 得到
> `target/classes`，仅将该目录置于 classpath 最前（**不覆盖** `D:\maven-repo` 中的制品），
> 同一套断言由 25/25 变为 **15 PASS / 10 FAIL**：
> `(:AccUser)` / `(:PlainUser)` 标签、`count()` 抛 UOE、`close()` 关闭了注入的驱动、
> 异常消息只剩 `simulated server failure`。恢复修复后重新 `mvn -o clean install`，回到 25/25 全绿。
>
> **仍未覆盖**：真实 Neo4j 服务端的 Cypher 语义（索引/约束、执行计划、事务冲突与重试）、
> 驱动自身的连接池与网络层——没有 Neo4j 服务端参与，**这不等于「已完成真实读写验收」**。

### 14.9 GreptimeDbEngine：假 JDBC 驱动驱动真实 SQL 生成（阶段十九，30 条）

本机没有 GreptimeDB 服务端。该引擎的查询、分页、计数、删除全部经
**MySQL 协议 JDBC**（`GreptimeJdbcClient`，内部为 `DriverManager.getConnection(url)`），
而 JDBC 的 `Connection` / `PreparedStatement` / `ResultSet` / `ResultSetMetaData`
全是接口。因此验收向 `DriverManager` 注册一个**只认 `jdbc:greptimefake:<id>:` 前缀**的
假 `Driver`，用 Proxy 实现上述四个接口：

- 引擎照常渲染 SQL、照常把条件值绑定成 `?` 参数（值绝不进 SQL 文本）；
- 假驱动记录**实际执行的每一条 SQL 与每一个参数**，并按 SQL 形态回放结果集；
- 断言聚焦「引擎发了什么 SQL、又如何映射结果」，真实 MySQL 驱动与服务端均不参与。

| 面 | 断言内容 |
|---|---|
| 表名 | `@TableName("t_acc_user")` → `FROM t_acc_user` 且不再出现 `AccUser`；无注解 `PlainUser` → `FROM plain_user`；`CpuMetric` → `FROM cpu_metric` |
| 列名归一化 | `cpuUtil` → `cpu_util`（Lambda 解析出的驼峰/去下划线变体都能映射回真实列名） |
| 查询构造 | `eq` → `WHERE name = ?` 且值不进 SQL；`gt` → `age > ?`；`orderByDesc` → `ORDER BY age DESC` |
| 分页 | `page(2,3)` → `LIMIT 3 OFFSET 3`；`limit(1)` → `LIMIT 1 OFFSET 0`；`page().total` 走 `SELECT COUNT(*) FROM (…) t_count` |
| 计数 | `count()` 返回 COUNT 子查询结果（23） |
| 映射 | 列标签 → 实体（`Long`/`String`/`Integer`）；`ID`/`NAME`/`AGE` 大小写宽松匹配；未知列与 `null` 值跳过且不影响其它字段 |
| 写入语义 | `store` 抛 UOE（「不支持内存存储」，禁止假持久化）；`update` 抛 UOE（提示 upsert）；两者均不向驱动发 SQL |
| 删除 | 无 WHERE → 基类守卫拦截（消息含「禁止无 WHERE」）且**不发 SQL**；带条件 → `DELETE FROM t_acc_user WHERE name = ?` 并返回服务端影响行数 |
| 执行器 | `query` 返回列名→值；`execute` 返回影响行数；`batch` 透传多行参数并返回各行结果；`queryPage` 追加 `LIMIT 2 OFFSET 4`；`query(sql, Class)` 映射实体；未注册名称返回 `null` 执行器 + `client(name)` 抛 IAE |
| URL 推导 | `jdbcUrlFromEndpoint` 精确到 `:4002` + 6 个固定参数；`hostOf` 剥离协议/路径、保留 IPv6 方括号；空端点与非法库名被拒绝；`setJdbcUrl` 只接受 `jdbc:` 前缀、空串表示清除 |
| 生命周期 | 未配置数据源 → ISE（不是空结果）；`close()` 后查询抛 ISE（含「已关闭」）；重新注册数据源可恢复；数据源名不允许空白 |

> **契约差异（显式声明，不是 GreptimeDB 缺陷）**：`query(X).one()` 走通用
> `EngineQueryWrapper.one()` —— 先 `list()` 再取首行，**不下推 `LIMIT 1`**。
> 这与其它 JDBC 引擎一致（Solr/ES/Neo4j 因各自覆写包装器才下推）。
> 需要服务端只回一行时应显式 `limit(1)`；阶段十九对此分别断言，避免后人误判。
>
> **负向对照（实际运行）**：把 `GreptimeDbEngine.tableNameOf` 临时改成
> `entityClass.getSimpleName()`（模拟「绕过 `@TableName`」的既有缺陷族），
> `mvn -o clean package` 后仅将该 `target/classes` 置于 classpath 最前
> （**不覆盖** `D:\maven-repo`），同一套断言由 30/30 变为 **26 PASS / 4 FAIL**：
> `FROM AccUser`、`FROM PlainUser`、`FROM CpuMetric`、`DELETE FROM AccUser`。
> 恢复源码并重新 `mvn -o clean install` 后回到 30/30 全绿。**该缺陷并未在本引擎真实存在**，
> 此实验只证明断言能拦住这一类回退。
>
> **仍未覆盖**：真实 GreptimeDB 服务端的 SQL 语义（tag/time 索引与 TTL、区域/副本、
> upsert 冲突判定、`DELETE` 的时间窗口约束）与 gRPC 写入通道（`write(Table)`）。
> **这不等于「已完成真实读写验收」**——没有 GreptimeDB 服务端参与。

### 14.10 HBaseEngine：动态代理假 Connection 驱动真实领域 API（阶段二十，23 条）

本机没有 HBase 集群。但 `HBaseEngine` 的数据源接受**任意** `Connection`，而
`Connection` / `Table` / `Admin` / `ResultScanner` 都是接口；`Put` / `Get` / `Delete` /
`Scan` / `KeyValue` / `Result` 是可直接构造与检查的官方类。因此验收用
`java.lang.reflect.Proxy` 构造假连接，让引擎真实地：

- 用 `TableDescriptorBuilder` / `ColumnFamilyDescriptorBuilder` 构造建表请求；
- 用 `Put.addColumn` 累计单元格（行键、列族、限定符、值均为真实 `byte[]`）；
- 用 `Scan.withStartRow` / `setStopRow` 表达前缀范围；
- 遍历 `ResultScanner`，把 `Result.rawCells()` 解析成 Map。

| 面 | 断言内容 |
|---|---|
| 建表 | 表不存在 → `Admin.createTable` 收到 `t_user` + 列族 `info`，`Admin` 被关闭；表已存在 → **不建表**（幂等）；Admin 异常 → ISE 含「HBase 建表失败: t_user」且保留 cause |
| 写入 | `Put` 行键 = `row-1`；`info:name=alice` / `info:city=shanghai` 逐项正确；空 Map 只写行键、不产生列；异常 → ISE 含「HBase put 失败: t_user/row-9」 |
| 读取 | 指定列族 → 只返回该列族的 `限定符→值`；`family=null` → 全部列族以 `族:限定符` 返回；行不存在 → **空 Map**（不是 null）；`Get` 行键正确 |
| 扫描 | 前缀 `ab` → 起始行 `ab`、停止行 `ac`；**非 ASCII 前缀**（`中`）按 UTF-8 末字节 +1（不做字符串往返）；无前缀 → 不设起止行且不设列族；结果带 `__row`，空 `Result` 被跳过；异常 → ISE 含「HBase scan 失败: t_user」 |
| 删除 | `Delete` 行键 = `row-7`，表名正确 |
| 显式拒绝 | `store` → UOE（提示用 `put()/scan()`）；`query(X).list()` → UOE（提示用 `scan`）；`update` → UOE（提示同键覆盖）；`delete` → UOE（提示用 `deleteRow`） |
| 生命周期 | `close()` **不关闭**调用方注入的 `Connection`；`close()` 后 `put` 顶层即 ISE「未配置 HBase 数据源」；未配置时 `get` 同样是该 ISE；非法底层类型被拒绝 |

> **断言先失败、再通过（实际运行）**：这套断言在修复前为 **17 PASS / 6 FAIL**，
> 失败点包括「建表断言误期望 `getTable`」（套件自身问题，已改为观察 `Admin`）、
> 自制 `Cell` 在 `Result.create` 归一化时失真（改用官方 `KeyValue`），以及
> **两条真实产品缺陷证据**：`HBase put 失败: t_user/r`、`HBase get 失败: t_user/r`
> ——「未配置数据源」被包装成操作失败。收紧断言 + 修产品后 23/23。
>
> **仍未覆盖**：真实 HBase 集群的服务端语义（RegionServer 路由与重试、列族压缩与 TTL、
> filter/协处理器、行锁与多版本、`checkAndPut` 类原子操作）。
> **这不等于「已完成真实读写验收」**——没有 HBase 集群参与。
>
> **未触达的分支（如实声明）**：`conn()` 中「存在多个数据源且未指定默认」的 ISE 分支
> 经公开 API 不可达——`addDataSource` 在首个数据源处即固定默认名，之后既不覆盖也不清空。
> 本阶段未对该分支做断言。

### 14.11 方言分页 SQL：Oracle / SQL Server / PostgreSQL / ClickHouse（阶段二十一，28 条）

**先说清楚边界**：本机没有 PostgreSQL / ClickHouse / Oracle / SQL Server 服务端，
也没有任何容器运行时（`docker` / `podman` / `nerdctl` / `wsl` 均未安装，
`Get-NetTCPConnection` 只看到 3306 的 MySQL），**因此本阶段不是真实查询语义验收**，
也没有假装是。能验、且更该先验的是这四个引擎的**方言分页渲染**——它是纯函数，
但结果会原样下发给数据库。

| 面 | 断言内容 |
|---|---|
| 能力声明 | `postgresql` / `clickhouse` / `mysql` 为 `supportsLimit=true`；`oracle` / `oracle11g` / `oracle12c` / `sqlserver` / `sqlserver2008` / `sqlserver2012` / `derby` / `derby1014` 全部为 `false`；`Dialect.require(k)` 与直接 `new` 结果一致 |
| LIMIT 方言 | PostgreSQL / ClickHouse / MySQL 渲染 `LIMIT n OFFSET m`；`pageNum=3,pageSize=2` → `OFFSET 4`（偏移量由 pageNum 推导） |
| Oracle 19c / 11g | ROWNUM 双层包装：`SELECT * FROM (SELECT TMP.*, ROWNUM ROW_ID FROM (…) TMP WHERE ROWNUM <= 3 + 3) WHERE ROW_ID > 3`，且**不出现** `LIMIT` |
| Oracle 12c | `OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY`；缺 ORDER BY 时用方言自己的回退 `ORDER BY NULL`（不是 SQL Server 的 `(SELECT NULL)`） |
| SQL Server 2019 / 2012 | `OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY`；缺 ORDER BY 时补 `ORDER BY (SELECT NULL)` |
| SQL Server 2008 | `ROW_NUMBER() OVER (ORDER BY (SELECT NULL))` 双层嵌套 + `ROW_NUM > 3 AND ROW_NUM <= 3 + 3` |
| Derby / Derby 10.14 | 前者 OFFSET…FETCH；后者 `ROW_NUMBER() OVER ()` + `ROW_ID` 边界；均不含 `LIMIT` |
| 边界 | `offset=0` 仍走模板（不是退回 LIMIT）；`pageSize<=0` 归一为 1；ORDER BY 已存在时不重复追加；大小写 `order by` 视为已排序 |
| 与引擎接线 | `Engine.create(postgresql/clickhouse/oracle/sqlserver)` 注册数据源（不可达地址，只装配 Hikari、不建连）后取到默认方言：协议名正确，`supportsLimit` 与预期一致；`sqlserver-legacy` 用 `sqlserver2008` 方言并渲染 `ROW_NUMBER()`；方言渲染无状态；非法方言名抛异常而不是返回 null |

> **隔离实验（实际运行）**：把这 8 个 env 与 Java 一起 `git stash`（回到 HEAD），
> `mvn -o clean install` 后同一套断言为 **23 PASS / 5 FAIL**，失败点精确为
> `oracle=true, oracle11g=true`、`sqlserver=true, sqlserver2008=true`、
> `derby=true, derby1014=true`、`Dialect.require("oracle").supportsLimit 应为 false`、
> `oracle supportsLimit=true`。恢复后重新构建回到 **28/28**。
> 值得注意的是模板类断言在回退状态下**仍然通过**——说明 `SqlDialect.processSql`
> 早就实现了模板渲染，真正缺的只是 `supports-limit` 这一行声明（见 §4 缺陷 27）。
>
> **仍未覆盖**：真实服务端的查询语义。具体是：PostgreSQL 的类型/事务/`RETURNING`、
> ClickHouse 的列式存储与 `FINAL`/物化视图、Oracle 的 `NUMBER`/`VARCHAR2 CHAR` 语义与
> 执行计划、SQL Server 的 `IDENTITY`/`DATETIME2`/锁与隔离级别。
> 校验这些需要真实实例；本机没有容器运行时，**这部分是"未验"，不是"已通过"**。

### 14.12 真实 PostgreSQL 读写（阶段二十二，37 条）

**先说清楚为什么是「本机进程」而不是「容器」**：本机没有 `docker` / `podman` / `nerdctl` /
`wsl`（实测 `Get-Command` 全部 `MISSING`，`Get-NetTCPConnection -State Listen` 只有 3306 的
MySQL 与 6379 的 Redis），所以**无法起容器**。替代方案是用 PostgreSQL 官方**免安装二进制**
（`postgresql-17.6-1-windows-x64-binaries.zip`，329,891,687 B）在本机 `%TEMP%` 下
`initdb` 出一个临时实例：

| 项 | 值 |
|---|---|
| 版本 | `PostgreSQL 17.6 on x86_64-windows, compiled by msvc-19.44.35213, 64-bit` |
| 监听 | `127.0.0.1:55432`（避开用户自己的 5432） |
| 认证 | `trust`（仅本机临时实例） |
| 数据目录 | `%TEMP%\opencode\pg\pgdata`（不写 `Program Files`、不注册 Windows 服务） |
| 运行账号 | 普通本地账号 `pgaccept`（PostgreSQL 在 Windows 上**拒绝以管理员身份运行服务端**） |
| 生命周期 | `run-postgres.bat start` / `stop`，验收后即停 |

断言（37 条 = 28 条通用 JDBC 套件 + 9 条 PostgreSQL 专有）：

| 面 | 断言内容 |
|---|---|
| 通用 JDBC（28） | 建表/插入/查询/条件/`COUNT`/`MAX`/`exists`/类型化映射/`queryOne`/分页三态/`batchSql`/Lambda `eq`/`gt+orderByDesc`/`count`/`page`/`update`/`delete`/全表写删拒绝/`querySql(null)`/`removeDataSource(null)`/数据源注册/不存在的表/关闭语义 |
| 版本 | `SELECT version()` 必须含 `PostgreSQL`（证明真的连到了服务端） |
| Unicode | 中文 + emoji（`\uD83D\uDE00`）+ 制表符写入再读回，逐字符相等 |
| JSONB | `CAST(? AS JSONB)` 写入，`doc->>'b'` 读回 `中文`，`(doc->>'a')::int` 读回 `1` |
| 默认值 | `BIGSERIAL` 自增首值 = 1；`TIMESTAMPTZ DEFAULT now()` 非空 |
| 分页真实边界 | 5 行数据：第 2 页 = id `3,4`；第 3 页只剩 `5`；越界页为空（不是「最多返回 2 行」的假分页） |
| 失败语义 | 查不存在的表：消息同时含表名与服务端原因（`relation "…" does not exist`） |
| 约束 | 主键冲突写入失败后行数不变（不留半条） |
| 方言一致 | `supportsLimit()==true` 且渲染 `LIMIT 3 OFFSET 3`，与实际分页结果一致 |
| 关闭语义 | `sqlSuite` 关闭引擎后再查询：消息必须含「已关闭」且不得含「不支持」（缺陷 28） |

> **为什么这组断言有分量**：它不是桩。`sqlSuite` 的 28 条是先建表、再插入、再读回、
> 再改再删的完整往返；分页断言比对的是具体 id，不是行数；JSONB 断言走的是
> PostgreSQL 独有的 `->>` / `::` 语法——桩无法伪造这些语义。
>
> **仍未覆盖**：多节点/流复制、权限与角色模型、`EXPLAIN` 计划与锁/隔离级别、
> 大对象与 `COPY`、分区表。需要更完整的实例与更多时间，**未验**。
>
> **跳过语义**：实例未启动时打印 `[SKIP]` 与 `STAGE22_SKIP=1`，退出码 0；
> 本次全量日志中**没有** SKIP，37 条全部真实执行。


### 14.13 真实 SQL Server 读写（阶段二十三，39 条）

本机同样没有容器运行时，因此这一阶段用的是**原生 Windows 服务实例**——Microsoft SQL
Server 2022 Express（`SQLEXPRESS`），由官方 Express 引导器静默安装：

| 项 | 值 |
|---|---|
| 版本 | `Microsoft SQL Server 2022 (RTM) - 16.0.1000.6 (X64)`（`@@VERSION` 实测） |
| 安装方式 | `SQL2022-SSEI-Expr.exe /Action=Install /Quiet /ConfigurationFile=install-config.ini`（实例选项写进 INI：`FEATURES=SQLENGINE` / `INSTANCENAME=SQLEXPRESS` / `SECURITYMODE=SQL` / `TCPENABLED=1`） |
| 监听 | `127.0.0.1:1433`（安装后把 `SuperSocketNetLib\Tcp\IPAll` 的 `TcpDynamicPorts` 清空、`TcpPort=1433`，再重启服务） |
| 认证 | SQL 身份验证（`LoginMode=2`，账号 `sa`，仅本机临时验收用） |
| 数据库 | `engine_accept`（harness 先连 `master` 建库，再切到目标库） |
| 生命周期 | `run-sqlserver.bat start` / `stop`（只控制服务，不改服务端配置） |

断言（39 条 = 28 条通用 JDBC 套件 + 11 条 T-SQL 专有）：

| 面 | 断言内容 |
|---|---|
| 通用 JDBC（28） | 与阶段二十二同一套 `sqlSuite`：建表/插入/查询/条件/`COUNT`/`MAX`/`exists`/类型化映射/`queryOne`/分页三态/`batchSql`/Lambda `eq`/`gt+orderByDesc`/`count`/`page`/`update`/`delete`/全表写删拒绝/`querySql(null)`/`removeDataSource(null)`/数据源注册/不存在的表/关闭语义 |
| 版本与归属 | `@@VERSION` 含 `Microsoft SQL Server`；`DB_NAME()` 必须等于 `engine_accept`（证明连的是目标库，不是 master 或别的实例） |
| Unicode | `NVARCHAR` 写入中文 + emoji（`\uD83D\uDE00`）+ 制表符后逐字符读回；`LEN(N'中文')=2`（T-SQL 语义） |
| 默认值 | `IDENTITY(1,1)` 首值 = 1；`DATETIME2 DEFAULT SYSDATETIME()` 非空 |
| 分页真实边界 | 5 行：第 2 页 = id `3,4`；第 3 页只剩 `5`；越界页为空（真的走了 `OFFSET … ROWS FETCH NEXT … ROWS ONLY`） |
| T-SQL 语法 | `SELECT TOP 2 … ORDER BY id` 首行 id=1；`ISNULL(NULL, N'x')='x'`；`CAST('2020-01-02' AS DATE)` |
| **缺陷 30 回归** | 计数前裁剪尾部 `ORDER BY` 的四类场景：普通排序、`ROW_NUMBER() OVER (ORDER BY …)` 窗口函数（不得被误裁）、字面量 `'order by'`（不得被误裁）、完全无 `ORDER BY`；另加一条**带绑定参数**的分页（裁剪后 `?` 索引不受影响） |
| 失败语义 | 查不存在的表：消息含表名 + 服务端原因（兼容中英文本地化：`Invalid object name` / `对象名 … 无效`） |
| 约束 | 主键冲突写入失败后行数不变，消息含 `PRIMARY KEY` / `duplicate` |
| 方言一致 | `supportsLimit()==false`，渲染结果以 `OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY` 结尾，且与实际分页结果一致 |
| 关闭语义 | `sqlSuite` 关闭引擎后再查询：消息必须含「已关闭」且不得含「不支持」（缺陷 28） |

> **这一阶段的最大价值是「真实服务端立刻抓出真缺陷」**：首次运行 33 PASS / 5 FAIL，
> 其中 3 条失败都指向同一个根因——`queryPage` 统计总数时把带 `ORDER BY` 的用户 SQL
> 包进派生表，SQL Server 直接拒绝（`ORDER BY 子句在 … 派生表 … 中无效`）。
> 修复前后对比：修复前 `queryPage` 三态断言全红，修复后 39/39 全绿。
> 这同时说明「只靠桩/方言文本断言」不够：桩不会拒绝非法 SQL。
>
> **仍未覆盖**：企业版特性（列存储、分区表、`MERGE` 之外的 T-SQL 扩展）、
> 高可用/复制、权限与角色模型、执行计划与锁隔离级别。**未验**。
>
> **跳过语义**：实例不可达时打印 `[SKIP]` 与 `STAGE23_SKIP=1`，退出码 0
> （可用 `MSSQL_HOST` / `MSSQL_PORT` / `MSSQL_DB` / `MSSQL_USER` / `MSSQL_PASSWORD` 覆盖默认值）；
> 本次全量日志中**没有** SKIP，39 条全部真实执行。



