# 引擎验收与修复交付报告（Engine / KvEngine / SeriesEngine 全子类）

日期：2026-10-04（阶段六增补与 md5 重绑：2026-10-17；阶段七新增 `Engine.query()` 构建器：2026-10-17；
阶段八将 Prometheus 升级为本机 HTTP 桩真实执行：2026-10-17）
仓库：`utils-support-parent-starter`（另用 `spring-support-parent-starter` 与 `utils-support-datalake-parent` 的制品）
已提交的产品修复：`a22f42be1`（11 文件，+346 / −38）
已提交的验收工具：`c1b77f94d`（9 文件，+1767）、`ef627b740`（阶段五 4 文件）、
`03d360448`（补回被全局 ignore 规则静默剔除的两个运行器）、本轮阶段六
验收结论：**八阶段共 675 条断言全绿，0 失败（`ACCEPTANCE_RESULT=PASS`，退出码 0）**

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
| `com.chua:utils-support-datasource-starter:4.0.0.42` | `8c9bac45` | 353,226 B | 缺陷 1/2/3/4/6/7 所在（阶段一~四）+ 阶段六 `ReactorFlywayBridge` |
| `com.chua:utils-support-redis-starter:4.0.0.42` | `abc98158` | 134,252 B | 缺陷 8/9 所在（阶段三/一） |
| `com.chua:utils-support-h2-starter:4.0.0.42` | `83ed8ee5` | 29,518 B | 阶段二/三负载 |
| `com.chua:utils-support-sqlite-starter:4.0.0.42` | `e6114489` | 43,241 B | 阶段二负载 + 阶段六 `SqliteReactorEngine` |
| `com.chua:utils-support-mysql-starter:4.0.0.42` | `5fad5192` | 140,317 B | 阶段三负载 |
| `com.chua:utils-support-rocksdb-starter:4.0.0.42` | `f5fe32c1` | 38,585 B | 阶段三/四负载 |
| `com.chua:utils-support-tablesaw-starter:4.0.0.42` | `de188bb5` | 39,304 B | 阶段一/四负载 |
| `com.chua:utils-support-parquet-starter:4.0.0.42` | `c2eda54d` | 21,207 B | 阶段一/四负载 |
| `com.chua:utils-support-lucene-starter:4.0.0.42` | `d3448cbf` | 35,486 B | 阶段一/四负载 |
| `com.chua:utils-support-nitrite-starter:4.0.0.42` | `d56620b7` | 12,604 B | 阶段一/四负载 |
| `com.chua:spring-support-redis-starter:4.0.0.42` | `c0278a4f` | 37,985 B | 阶段五负载 |
| `com.chua:spring-support-monitor-starter:4.0.0.42` | `0372fcec` | 922,392 B | 阶段五负载 |
| `com.chua:utils-support-datalake-query-starter:4.0.0.42` | `4804877f` | 12,858 B | 阶段六负载（`HttpDatalakeQueryEngine`） |

签收前须重新核对上述 md5；不一致即本报告作废（本轮已因制品被重建而重跑过一次）。

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
| `JdbcEngine` | 抽象基类（同步 JDBC） | 经 9 个 JDBC 引擎子类间接覆盖 |
| `JdbcReactorEngine` / `SqliteReactorEngine` | 另一 SPI 家族（`ReactorEngine`），非 `Engine` 子类 | 阶段六经 `ReactorFlywayBridge` 以真实 SQLite 驱动其 JDBC 降级路径 |
| `JdbcReactorEngine$R2dbcEngineAdapter` / `$UnifiedEngineAdapter` | **private 内部类**，仅由宿主实例内部创建 | 无法从外部引用，**未直接触达**（如实声明） |
| `ReactorFlywayBridge` | Flyway 的「响应式→同步」桥接，`implements Engine` | **阶段六**：桥接契约（15 条 UOE/能力断言）+ 真实 SQLite 读写（11 条） |
| `HttpDatalakeQueryEngine` | datalake 模块的只读查询引擎，`implements Engine` | **阶段六**：只读契约面 16 条（写入口 UOE / 方言与数据源为 null / 关闭幂等），**全部不触网** |

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

### 2.3 SeriesEngine —— 4 个（全覆盖）

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
| 六 | 非 SPI 注册的两个公开 `Engine` 实现者：`HttpDatalakeQueryEngine`（只读契约面）、`ReactorFlywayBridge`（桥接契约 + 真实 SQLite 读写） | 45 | 全绿 |
| 七 | 新增 `Engine.query()` 非 ORM SQL 文本构建器：渲染 / 守卫 + 内存 / H2 / SQLite 三引擎端到端执行 | 37 | 全绿 |
| 八 | `PrometheusEngine` + `PrometheusClient`：本机 HTTP 桩真实读写（8 个 `/api/v1/*` 端点、Basic 认证头、scalar/vector/matrix、HTTP 500 / 非 JSON / status=error / 不支持 resultType 的失败语义） | 35 | 全绿 |

**合计 675 条断言，0 失败**（`run-acceptance.bat`：八阶段逐段 `PASS`，
末行 `ACCEPTANCE_RESULT=PASS`，退出码 0）。

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
├─ run-acceptance.bat          八阶段运行器（自动解析 classpath → 编译 → 逐阶段执行）
├─ prepare-classpath.ps1       从 pom.xml 解析 classpath 并生成 lib\（硬链接/复制）
├─ pom.xml                     仅用于解析 classpath，不参与 reactor 构建
├─ harness/*.java              八个验收程序
└─ README.md                   覆盖范围、前置条件、交付纪律
```

```bat
cd D:\ch\project\utils-support-parent-starter\utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat              :: 八阶段全跑，末行 ACCEPTANCE_RESULT=PASS/FAIL
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

1. **需外部服务的 10 个引擎**（postgresql、sqlserver、oracle、clickhouse、hbase、solr、
   neo4j、elasticsearch、influxdb、greptimedb）只有契约面；
   本机无对应服务，无法做真实读写验收。**这部分是"未验"，不是"已通过"。**
   > `prometheus` **已从本清单移出**：阶段八用本机 HTTP 桩（`com.sun.net.httpserver`）
   > 打通了 8 个 `/api/v1/*` 端点，`PrometheusEngine` / `PrometheusClient` 的查询、
   > 模型映射、Basic 认证与失败语义均已真实执行（35 条断言）。仍未覆盖的是
   > **真实 Prometheus 服务端的 PromQL 语义**（桩只回放固定响应）。
2. **`InfluxdbSeriesEngine` 只有契约面**（同上，无 InfluxDB 服务）。
3. **`JdbcReactorEngine` 的两个 private 内部适配器**
   （`R2dbcEngineAdapter` / `UnifiedEngineAdapter`）无法从外部引用，未直接触达；
   其宿主 `JdbcReactorEngine` 的 JDBC 降级路径已由阶段六经 `ReactorFlywayBridge` 真实驱动
   （建表 / 插入 / 条件查询 / 类型化映射 / 计数 / 删除，全部走真实 SQLite 文件）。
4. **`HttpDatalakeQueryEngine` 只做只读契约面**：其 `getExecutor().query/execute` 会向
   数据湖查询服务发起 HTTP 调用，本机无该服务，故 `querySql`/`executeSql` 路径未验
   （阶段六的 16 条断言全部不触网）。**这部分是"未验"，不是"已通过"。**
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

