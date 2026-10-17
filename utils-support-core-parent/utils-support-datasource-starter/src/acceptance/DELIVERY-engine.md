# 引擎验收与修复交付报告（Engine / KvEngine / SeriesEngine 全子类）

日期：2026-10-04（阶段六增补与 md5 重绑：2026-10-17；阶段七新增 `Engine.query()` 构建器：2026-10-17；
阶段八将 Prometheus 升级为本机 HTTP 桩真实执行：2026-10-17；
阶段九以桩客户端驱动 `InfluxdbSeriesEngine` 全部引擎逻辑：2026-10-17；
阶段十以本机 HTTP 桩真实驱动 `HttpDatalakeQueryEngine` 查询/执行路径：2026-10-17；
阶段十一真实嵌入式 RocksDB 验收 `RocksDbSeriesEngine`；
阶段十二以本机 HTTP 桩验收 `QuestdbSeriesEngine` 并修复其 `/exec` 鉴权缺失：2026-10-17）
仓库：`utils-support-parent-starter`（另用 `spring-support-parent-starter` 与 `utils-support-datalake-parent` 的制品）
已提交的产品修复：`a22f42be1`（11 文件，+346 / −38）
已提交的验收工具：`c1b77f94d`（9 文件，+1767）、`ef627b740`（阶段五 4 文件）、
`03d360448`（补回被全局 ignore 规则静默剔除的两个运行器）、本轮阶段六
验收结论：**十五阶段共 858 条断言全绿，0 失败（`ACCEPTANCE_RESULT=PASS`，退出码 0）**

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
| `com.chua:utils-support-common-starter:4.0.0.42` | `0896c47e` | 4,749,974 B | 缺陷 1/3/4/5/6/7 所在（阶段二/三/四）+ `Engine.query()` / `SqlQueryBuilder`（阶段七） |
| `com.chua:utils-support-datasource-starter:4.0.0.42` | `91c5b9e1` | 353,307 B | 缺陷 1/2/3/4/6/7 所在（阶段一~四）+ 阶段六 `ReactorFlywayBridge` + 缺陷 12 的 `JdbcUrl.hostPort`（阶段十四） |
| `com.chua:utils-support-redis-starter:4.0.0.42` | `abc98158` | 134,252 B | 缺陷 8/9 所在（阶段三/一） |
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
| `com.chua:utils-support-hbase-starter:4.0.0.42` | `7e9eb07b` | 12,262 B | 阶段一契约面（`hbase`） |
| `com.chua:utils-support-solr-starter:4.0.0.42` | `46b6a1c9` | 48,582 B | 阶段一契约面（`solr`）+ **阶段十五深度功能**（假 `SolrClient` 驱动真实路径）+ 缺陷 13/14 修复 |
| `com.chua:utils-support-neo4j-starter:4.0.0.42` | `fdf22bed` | 25,466 B | 阶段一契约面（`neo4j`） |
| `com.chua:utils-support-elasticsearch-starter:4.0.0.42` | `b2a2cb4e` | 55,833 B | 阶段一契约面（`elasticsearch`） |
| `com.chua:utils-support-greptimedb-starter:4.0.0.42` | `998edaed` | 32,785 B | 阶段一契约面（`greptimedb`） |

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
influxdb（阶段十三，纯内存）、redis（阶段三/五，真实 6379）、solr（阶段十五，记录型假
`SolrClient` 驱动真实查询/写入/删除路径）；非 SPI 的 datalake-query（阶段十，本机 HTTP 桩）
与 ReactorFlywayBridge（阶段六，真实 SQLite）。
另：postgresql / sqlserver（含 legacy）/ oracle / clickhouse 在阶段十四验证了
**配置拼装、入参守卫、失败语义与关闭语义**，但**查询语义仍未验**（见 §7）。
其余 8 个需外部服务的引擎只有契约面（见 §7）。

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

**合计 858 条断言，0 失败**（`run-acceptance.bat`：十五阶段逐段 `PASS`，
末行 `ACCEPTANCE_RESULT=PASS`，退出码 0）。

## 4. 修复的 14 处真实缺陷

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
├─ run-acceptance.bat          十五阶段运行器（自动解析 classpath → 编译 → 逐阶段执行）
├─ prepare-classpath.ps1       从 pom.xml 解析 classpath 并生成 lib\（硬链接/复制）
├─ pom.xml                     仅用于解析 classpath，不参与 reactor 构建
├─ harness/*.java              十五个验收程序
└─ README.md                   覆盖范围、前置条件、交付纪律
```

```bat
cd D:\ch\project\utils-support-parent-starter\utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat              :: 十五阶段全跑，末行 ACCEPTANCE_RESULT=PASS/FAIL
run-acceptance.bat 1 5          :: 只跑阶段一与阶段五
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
- 打开被验模块的**新构建**：`src/acceptance/pom.xml` 引用的是本地仓库制品，
  改完产品代码必须先 `mvn install` 对应模块，否则验的是旧包（**报告 md5 就是为此存在**）。

## 7. 尚未覆盖（如实声明）

1. **需外部服务的 8 个引擎**（postgresql、sqlserver、oracle、clickhouse、hbase、
   neo4j、elasticsearch、greptimedb）只有契约面；
   本机无对应服务，无法做真实读写验收。**这部分是"未验"，不是"已通过"。**
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
   > 且本引擎不自带 schema 管理。postgresql / sqlserver / oracle / clickhouse
   > 亦仅在阶段十四验了配置层，见上方 §2.1。
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



