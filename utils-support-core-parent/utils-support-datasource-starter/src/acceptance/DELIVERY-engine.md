# 引擎验收与修复交付报告（Engine / KvEngine / SeriesEngine 全子类）

日期：2026-10-04
仓库：`utils-support-parent-starter`（阶段五另用 `spring-support-parent-starter` 的两个制品）
已提交的产品修复：`a22f42be1`（11 文件，+346 / −38）
已提交的验收工具：`c1b77f94d`（9 文件，+1767）
验收结论：**五阶段共 558 条断言全绿，0 失败**

---

## 1. 被验产物与 md5（**换包即失效，须重跑后才能引用本报告**）

| 产物 | md5（前 8 位） | 大小 | 本批是否重建 |
|---|---|---|---|
| `com.chua:utils-support-common-starter:4.0.0.42` | `2d8648b1` | 4,563,385 B | 是（缺陷 1/3/4/5/6/7 所在） |
| `com.chua:utils-support-datasource-starter:4.0.0.42` | `d1fed079` | 342,996 B | 是（缺陷 1/2/3/4/6/7 所在） |
| `com.chua:utils-support-redis-starter:4.0.0.42` | `4f50d47b` | 125,768 B | 是（缺陷 8/9 所在） |
| `com.chua:utils-support-h2-starter:4.0.0.42` | `ba65bcc3` | 29,453 B | 否（阶段二/三负载） |
| `com.chua:utils-support-mysql-starter:4.0.0.42` | `dff38075` | 140,011 B | 否（阶段三负载） |
| `com.chua:utils-support-rocksdb-starter:4.0.0.42` | `d1f43707` | 31,213 B | 否（阶段三/四负载） |
| `com.chua:utils-support-tablesaw-starter:4.0.0.42` | `293d527f` | 39,346 B | 否（阶段四负载） |
| `com.chua:utils-support-parquet-starter:4.0.0.42` | `96db579b` | 21,193 B | 否（阶段四负载） |
| `com.chua:utils-support-lucene-starter:4.0.0.42` | `b33fa46c` | 35,450 B | 否（阶段四负载） |
| `com.chua:utils-support-nitrite-starter:4.0.0.42` | `e6fdd83a` | 12,584 B | 否（阶段四负载） |
| `com.chua:spring-support-redis-starter:4.0.0.42` | `33bea63c` | 37,653 B | 否（阶段五负载） |
| `com.chua:spring-support-monitor-starter:4.0.0.42` | `5f504ebe` | 1,127,261 B | 否（阶段五负载） |

签收前须重新核对上述 md5；不一致即本报告作废。

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

**非 SPI 注册的 Engine 实现**（不在 `ServiceProvider` 可发现范围内）：

| 实现 | 性质 | 覆盖状态 |
|---|---|---|
| `AbstractEngine` | 抽象基类 | 经全部子类间接覆盖 |
| `JdbcEngine` / `JdbcReactorEngine` / `SqliteReactorEngine` | 抽象基类 + 另一 SPI 家族（`ReactorEngine`） | 阶段二经 JdbcEngine 子类间接覆盖；响应式家族未单独验 |
| `JdbcReactorEngine$R2dbcEngineAdapter` / `$UnifiedEngineAdapter` | 私有内部降级适配器 | 未单独验（内部实现，随 `JdbcReactorEngine` 走） |
| `ReactorFlywayBridge` | Flyway 的「响应式→同步」桥接 | 未单独验（纯委托，依赖响应式家族） |
| `HttpDatalakeQueryEngine` | datalake 模块的查询引擎 | 契约面未纳入本 harness（跨 parent，未进 classpath） |

> `com.chua.playwright.support.engine.JavaEngine` **不是** datasource Engine ——
> 它实现的是 playwright 自己的 `Engine`（浏览器自动化 SPI，同包接口）。已甄别排除。
> `com.chua.redis.support.engine.RedisEngine` 是 `RediSearchEngine` 的**基类且不实现 Engine**，同样排除。

### 2.2 KvEngine —— 4 个（全 覆盖）

| 实现 | 覆盖 | 阶段 |
|---|---|---|
| `com.chua.chronicle.support.kv.ChronicleMapKv` | 全 KV 契约 + TTL 自洽 | 三 |
| `com.chua.rocksdb.support.engine.RocksDbEngine` | 全 KV 契约 + TTL 自洽 | 三 |
| `com.chua.redis.support.client.RedisClient` | 全 KV 契约 + TTL 自洽 | 三 |
| `com.chua.starter.redis.support.kv.RedisKvEngine` | **真实读写**（Spring `StringRedisTemplate` + 本机 6379） | 五 |

### 2.3 SeriesEngine —— 4 个（全 覆盖）

| 实现 | 覆盖 | 阶段 |
|---|---|---|
| `com.chua.common.support.lang.datasource.series.impl.DataSourceSeriesEngine` | h2 真实读写 + 全契约 | 三 |
| `com.chua.redis.support.engine.RedisSeriesEngine` | 本机 Redis 真实读写 + 全契约 | 三 |
| `com.chua.influxdb.support.engine.InfluxdbSeriesEngine` | **仅契约面**（本机无 InfluxDB） | 五 |
| `com.chua.starter.monitor.support.config.SeriesEngineConfiguration$NoopSeriesEngine` | 经其唯一公开入口（Bean 工厂）驱动 + 全契约 | 五 |

## 3. 验证阶段与断言数

| 阶段 | 范围 | 断言 | 结果 |
|---|---|---|---|
| 一 | 24 个 SPI 引擎的契约 / 能力声明 / 生命周期 / 空参与边界 | 288 | 全绿 |
| 二 | memory / file / h2 / sqlite / duckdb 深度功能面 | 132 | 全绿 |
| 三 | KvEngine ×3 + SeriesEngine ×2 + mysql（真实 3306） | 75 | 全绿 |
| 四 | parquet / lucene / nitrite / tablesaw + rocksdb 文档面 | 24 | 全绿 |
| 五 | `RedisKvEngine` + `InfluxdbSeriesEngine` + `NoopSeriesEngine` + monitor 装配路径 | 39 | 全绿 |

**合计 558 条断言，0 失败。**

## 4. 修复的 9 处真实缺陷

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
├─ run-acceptance.bat          五阶段运行器（自动解析 classpath → 编译 → 逐阶段执行）
├─ prepare-classpath.ps1       从 pom.xml 解析 classpath 并生成 lib\（硬链接/复制）
├─ pom.xml                     仅用于解析 classpath，不参与 reactor 构建
├─ harness/*.java              五个验收程序
└─ README.md                   覆盖范围、前置条件、交付纪律
```

```bat
cd D:\ch\project\utils-support-parent-starter\utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat              :: 五阶段全跑，末行 ACCEPTANCE_RESULT=PASS/FAIL
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
- 阶段三 / 五需本机 Redis；阶段三需本机 MySQL。缺服务时对应套件失败，其余阶段不受影响。
- 打开被验模块的**新构建**：`src/acceptance/pom.xml` 引用的是本地仓库制品，
  改完产品代码必须先 `mvn install` 对应模块，否则验的是旧包（**报告 md5 就是为此存在**）。

## 7. 尚未覆盖（如实声明）

1. **需外部服务的 11 个引擎**（postgresql、sqlserver、oracle、clickhouse、hbase、solr、
   neo4j、elasticsearch、influxdb、greptimedb、prometheus）只有契约面；
   本机无对应服务，无法做真实读写验收。**这部分是"未验"，不是"已通过"。**
2. **`InfluxdbSeriesEngine` 只有契约面**（同上，无 InfluxDB 服务）。
3. **响应式家族未单独验**：`JdbcReactorEngine`（及 `SqliteReactorEngine`、
   两个私有降级适配器、`ReactorFlywayBridge`）属另一 SPI（`ReactorEngine`），
   本轮未纳入；阶段二经 `JdbcEngine` 子类间接覆盖了同步 JDBC 路径。
4. **`HttpDatalakeQueryEngine`** 在 `utils-support-datalake-parent`，未进本 harness 的 classpath。
5. `SeriesEngine.purge` 契约存在**自身歧义**：`0` 既被定义为「后端不支持」，又是「成功但删了 0 条」
   的自然返回值，调用方无法区分。本报告未改接口语义，仅记录。
6. **UI 增强尚未开始**（目标中的「ui增强」）。
