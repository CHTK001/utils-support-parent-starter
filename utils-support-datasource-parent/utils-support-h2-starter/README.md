# utils-support-h2-starter

> H2 嵌入式数据库支持模块：H2Engine、H2MetaData、H2SearchEngine（H2 2.x 无内置 FTS，检索门面降级为普通索引 + LIKE 关键字查询），另含 H2 迁移脚本方言转换器与测试清理插件。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.h2.support`（子包：`engine`、`meta`、`flyway`、`dialect`、`cleanup`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 核心 API](#3-核心-api)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

本模块是 H2 数据库在 utils 数据源体系（`Engine` / `MetaData` / `SearchEngine` / `ScriptConverter` 四个 SPI）下的适配实现，全部能力基于 JDBC（HikariCP 连接池）。Maven 坐标：`com.chua:utils-support-h2-starter:4.0.0.51`，父模块 `utils-support-datasource-parent`。

### 1.1 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `Engine`/`MetaData`/`SearchEngine`/`ScriptConverter` 四个 SPI 的 `h2` 实现 + 测试清理插件 | 不是 Spring Boot 自动装配 starter（无自动配置类、无 `spring.factories`） |
| 检索 | 检索门面的 H2 降级实现：普通二级索引 + 调用方 `LIKE` 关键字查询 | 不提供全文检索（H2 2.x 已移除内置 FTS） |
| 迁移 | MySQL 风格迁移脚本 → H2 标准模式语句的纯文本转换器 | 不做 `ON DUPLICATE KEY UPDATE` 改写（整条跳过）、不做 `INSERT IGNORE` → `MERGE` 改写 |
| 依赖 | 只依赖 common / datasource 契约模块 | 不管理 H2 驱动版本（`provided`，由应用选择并提供）；不提供响应式（R2DBC）引擎实现 |

**判断口径**：凡是「H2 特有语法 / 元数据 / 脚本方言 / 清库」的适配放本模块；跨库通用的引擎契约与 JDBC 基类在 `utils-support-datasource-starter`，SPI 接口定义在 `utils-support-common-starter`。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 建 H2 引擎并注册数据源 | `new H2Engine()` → `addDataSource(name, jdbcUrl)` | 支持内存库 `jdbc:h2:mem:...` 与文件库 `jdbc:h2:file:...`；HikariCP 池，驱动固定 `org.h2.Driver`，最大连接 5 |
| 执行 SQL / 查询 | `engine.getExecutor().execute(sql)` / `.query(sql)` | `SqlExecutor`：`execute` / `query` / `queryPage` / `batch` |
| 对象化建表 / 删表 / 重命名 | `engine.meta().table()` 或 `table(表名)` | 返回 `JdbcMetaTable`；元数据入口由 `meta()` 按协议 `h2` 经 SPI 自动加载 |
| 建索引 / 查索引 / 删索引 | `engine.meta().search().create(名)...` / `.get(名)` / `.list()` / `.drop(名)` | 降级为普通二级索引：索引名 = 被索引表名，表必须已存在 |
| 索引存在性 / 统计重算 | `engine.meta().search().refresh(名)` / `.optimize(名)` | `refresh` 只做存在性校验（无刷新命令）；`optimize` 执行库级 `ANALYZE` |
| 测试清库 | `engine.cleanup()` → `cleanup()` / `cleanupByPrefix(前缀)` / `truncateAll()` | 全清：删索引 → 删用户表 → 删版本记录表；按前缀删表；清空数据保留表结构 |
| MySQL 脚本转 H2 | `new H2ScriptConverter().convert(语句, "h2")` | SPI 键 `h2`、order=100；类型/函数映射，字面量不被改写 |
| H2 1.x 方言 | `new H2V1Dialect()` | `protocol()="h2v1"`、`LIMIT/OFFSET` 分页、`supportsUpsert()=false` |

---

## 3. 核心 API

### 3.1 H2Engine（`com.chua.h2.support.engine.H2Engine`）

继承 `com.chua.datasource.support.engine.JdbcEngine`，`@Spi("h2")`，`@since 4.0.0.42`。

| 方法 | 说明 |
| --- | --- |
| `addDataSource(String name, String jdbcUrl)` | 注册 H2 数据源：内置 HikariDataSource（驱动 `org.h2.Driver`、`setMaximumPoolSize(5)`），方言为 `Dialect.require("h2")`。注册后不支持运行期替换数据源对象（`setSource` 抛 `UnsupportedOperationException`），需重新 `addDataSource` |
| `getExecutor()` / `getExecutor(String name)` | 取默认 / 指定数据源的 `SqlExecutor`（非 JDBC 数据源返回 `null`） |
| `meta()` | 元数据入口：按方言协议 `h2` 经 SPI 加载 `H2MetaData`（找不到时回退 `JdbcMetaData`） |
| `cleanup()` | 返回 `H2CleanupPlugin` 测试清理插件 |

另继承 `JdbcEngine` 的通用能力：`createDatabase` / `dropDatabase` / `createTable` / `getTriggers` / `getProcedures` 等。

### 3.2 H2MetaData（`com.chua.h2.support.meta.H2MetaData`）

继承 `AbstractMetaData`，提供对象化 DDL（建表/删表/重命名）：

- `table()` / `table(String tableName)` → `JdbcMetaTable`（可用）
- `search()` / `search(String indexName)` → `H2MetaSearch`（可用）
- `view` / `index` / `trigger` / `procedure` / `fk` / `user` / `permission` 各入口 → 一律抛 `UnsupportedOperationException`（「H2 暂不支持……」）

### 3.3 H2MetaSearch / H2SearchEngineImpl（检索门面）

`H2MetaSearch`（继承 `AbstractMetaSearch`）是门面入口，内部委托 `H2SearchEngineImpl`（实现 `SearchEngine`，`type()="h2"`）执行。H2 2.x 已移除内置全文检索引擎，本实现把检索门面落地为普通索引管理：

| 方法 | 行为 |
| --- | --- |
| `create(indexName)` | 返回 `SearchIndexCreateBuilder`；`execute()` 执行 `CREATE INDEX IF NOT EXISTS 索引名 ON 同名表 (索引列)`——**索引名同时是被索引表名，表必须已存在**；至少一个 `index=true` 的列，否则抛 `IllegalArgumentException` |
| `get(indexName)` / `list()` | 读 `INFORMATION_SCHEMA.INDEXES` / `INDEX_COLUMNS`，返回物理定义（列与真实列类型），不声称分片/副本/设置等不存在的信息 |
| `drop(indexName)` | `DROP INDEX IF EXISTS`，仅识别用户创建的普通二级索引（`INDEX_TYPE_NAME='INDEX'`，排除主键/约束自动索引） |
| `refresh(indexName)` | 仅做存在性校验。H2 索引随事务提交即可见，无近实时窗口，也没有刷新命令；对不存在的索引不返回成功 |
| `optimize(indexName)` | 索引存在时执行库级 `ANALYZE`（H2 2.x 已取消按表分析语法） |

同名索引已存在时：列集合一致（忽略大小写与顺序）→ 幂等返回 `true`；不一致 → 抛 `IllegalStateException`（`IF NOT EXISTS` 会静默保留旧索引，不报错即谎报成功）。

### 3.4 H2CleanupPlugin（`com.chua.h2.support.cleanup.H2CleanupPlugin`）

测试环境清理插件，事务内执行（`setAutoCommit(false)` + `commit`）：

| 方法 | 行为 |
| --- | --- |
| `cleanup()` | 删除所有用户索引 → 删除所有用户表（`PUBLIC` 模式、`TABLE_TYPE='BASE TABLE'`）→ 删除版本记录表 `sys_database_version`（`FlywayHistory.HISTORY_TABLE`）；返回清理的表数量 |
| `cleanupByPrefix(String prefix)` | 仅删除表名匹配前缀（忽略大小写）的用户表，用于隔离测试 |
| `truncateAll()` | `TRUNCATE TABLE` 所有用户表，保留表结构；返回处理的表数量 |

### 3.5 H2ScriptConverter（`com.chua.h2.support.flyway.H2ScriptConverter`）

迁移脚本方言转换器，`@Spi(value = "h2", order = 100)`，继承 `DefaultScriptConverter`，常量 `PROTOCOL = "h2"`。按 **H2 标准模式**（`jdbc:h2:mem:...` 默认，不带 `;MODE=MySQL`）转换：

| 规则 | 处理 |
| --- | --- |
| `JSON` → `CLOB` | 由父类 `DefaultScriptConverter` 的 `case "h2"` 完成，本类不重复实现 |
| 表尾 `ENGINE=` / `AUTO_INCREMENT=` / `CHARSET=` / `COLLATE=`、行内 `KEY`/`UNIQUE KEY`、列内联 `COMMENT` | 由父类剥离；索引改由独立 `CREATE INDEX` 承载 |
| `ON DUPLICATE KEY UPDATE` | 整条返回 `null` 跳过（仅 H2 MySQL 兼容模式支持；不做 `MERGE INTO` 高风险改写） |
| `INSERT IGNORE INTO` | 仅剥离 `IGNORE` → `INSERT INTO`（重复执行会主键冲突，依赖 Flyway 每版本只执行一次） |
| 类型映射 | `DATETIME`→`TIMESTAMP`；`MEDIUMTEXT`/`LONGTEXT`/`TINYTEXT`/`TEXT`→`CLOB`；`TINYINT(1)`→`TINYINT`（剥显示宽度）；`DOUBLE`→`DOUBLE PRECISION` |
| 函数映射 | `NOW()`→`CURRENT_TIMESTAMP`；`IFNULL(`→`COALESCE(`；`RAND()`→`RANDOM()` |
| 保持原样 | `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（H2 原生支持）；反引号标识符（H2 默认接受） |

所有替换经 `replaceOutsideLiterals` 执行，避免把字符串字面量里的 `.json` / `.text` / `NOW()` 之类文本改坏。前提：`CREATE TABLE` 按「每列一行」的对齐格式书写，单行紧凑写法不在转换范围内。

### 3.6 H2V1Dialect（`com.chua.h2.support.dialect.H2V1Dialect`）

H2 1.x 方言实现，继承 `AbstractDialect`，常量 `VERSION = "H2 1.x"`：

- `protocol()="h2v1"`、`driver()="org.h2.Driver"`、`url()="jdbc:h2:<DATABASE>"`、引号符 `"`
- 分页：`LIMIT n` / `LIMIT n OFFSET m`
- `getAutoIncrementKeyword()="AUTO_INCREMENT"`、`supportsUpsert()=false`、`getAlterColumnString()="ALTER COLUMN"`、`getCurrentTimestampSelectString()="SELECT CURRENT_TIMESTAMP()"`
- `getTypeName(...)` 提供 JDBC 类型 → H2 类型名映射；`getTriggerListSql` / `getTriggerSql` 查 `INFORMATION_SCHEMA.TRIGGERS`

---

## 4. 快速开始

### 4.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-h2-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- H2 驱动由应用选择并提供（本模块为 provided）：
     源码注释中行为验证所用版本为 com.h2database:h2:2.4.240 -->
```

### 4.2 最短可运行示例

```java
import com.chua.h2.support.engine.H2Engine;

H2Engine engine = new H2Engine();
engine.addDataSource("default", "jdbc:h2:mem:demo");   // 文件库用 jdbc:h2:file:./data/demo

// 1. 执行 DDL / DML
engine.getExecutor().execute("CREATE TABLE product (title VARCHAR(200), price INT)");
engine.getExecutor().execute("INSERT INTO product VALUES ('机械键盘', 199)");

// 2. 查询（关键字检索用 LIKE）
java.util.List<java.util.Map<String, Object>> rows =
        engine.getExecutor().query("SELECT * FROM product WHERE title LIKE '%键盘%'");

// 3. 检索门面：在「与索引同名的已存在表」上建普通二级索引
engine.meta().search().create("product")
        .field("title", "text")
        .field("price", "integer")
        .execute();
engine.meta().search().list();        // 物理索引定义
engine.meta().search().refresh("product");   // 存在性校验

// 4. 测试清理
engine.cleanup().truncateAll();              // 清空数据保留表结构
engine.cleanup().cleanupByPrefix("tmp_");    // 按前缀删表
```

---

## 5. 配置项

**无。** 依据：本模块 `src/main/resources` 下只有 4 个 SPI 扩展声明文件（见下节），没有 `application*.yml` / `*.properties`、没有 `@ConfigurationProperties`、没有系统属性读取；连接信息（数据源名称、JDBC URL）由调用方在 `addDataSource(name, jdbcUrl)` 中代码传入；驱动名（`org.h2.Driver`）与连接池大小（`5`）在 `H2Engine` 源码中固定，未暴露配置键。H2 驱动版本以 `provided` 引入，由应用决定。

---

## 6. SPI 扩展点

本模块通过 `META-INF/extensions/<接口全限定名>` 注册了 4 个 `h2` 扩展（文件内容均为一行 `h2=<实现类全名>`）：

| SPI 接口（扩展点，定义在 utils-support-common-starter） | 扩展键 | 本模块实现 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `h2` | `com.chua.h2.support.engine.H2Engine`（类上 `@Spi("h2")`） |
| `com.chua.common.support.lang.datasource.meta.MetaData` | `h2` | `com.chua.h2.support.meta.H2MetaData`（由 `JdbcEngine.meta()` 按协议名经 `ServiceProvider` 自动加载） |
| `com.chua.common.support.lang.datasource.meta.SearchEngine` | `h2` | `com.chua.h2.support.meta.H2SearchEngineImpl`（`type()="h2"`；`H2MetaSearch` 内部直接 `new` 使用） |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `h2` | `com.chua.h2.support.flyway.H2ScriptConverter`（`@Spi(value="h2", order=100)`） |

说明：`H2V1Dialect` 在本模块没有对应的 SPI 注册文件（源码未提供其注册方式），按普通类直接使用。扩展键必须等于协议名 `h2`，否则会与 `DefaultScriptConverter` 等兜底默认实现同键互相遮蔽（见 `H2ScriptConverter` 类注释）。

---

## 7. 构建与依赖

### 7.1 Maven 坐标与层级

```text
com.chua:utils-support-parent-starter:4.0.0.51        （仓库聚合根）
  └── com.chua:utils-support-datasource-parent:4.0.0.51（数据源模块父 POM）
        └── com.chua:utils-support-h2-starter:4.0.0.51 （本模块）
```

JDK：`25`（根 POM `maven.compiler.source/target=25`、`java.version=25`）。全部类 `@since 4.0.0.42`。

### 7.2 依赖清单（本模块 pom.xml）

| 依赖 | scope | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | SPI 接口与工具契约 |
| `com.chua:utils-support-datasource-starter` | compile | `JdbcEngine` / `AbstractMetaData` / `AbstractMetaSearch` / `AbstractDialect` 等基类 |
| `com.h2database:h2` | provided | H2 驱动，版本由具体应用选择并提供 |
| `io.r2dbc:r2dbc-h2`（0.9.1.RELEASE） | provided | 仅声明；本模块源码未使用（无响应式实现） |
| `com.zaxxer:HikariCP` | provided | 连接池（`H2Engine` 内创建 `HikariDataSource`） |

### 7.3 构建命令

```bash
mvn -o -q compile -DskipTests          # 仓库验收口径
mvn clean package -DskipTests          # 完整打包
```

---

## 8. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 全文检索 | 不提供。H2 2.x 已移除内置 FTS（文本索引 / CATSEARCH）；检索门面降级为普通索引 + 调用方 `LIKE` 查询。源码注释建议：需要全文检索请自建 FTS 表或使用 Elasticsearch/Lucene 门面 |
| 检索门面的分片/副本/设置/映射 | 不落地。`shards≠1`、`replicas≠1`、`settings(...)` 与字段级选项（`analyzer`、`searchAnalyzer`、`store`、`weight`、`ignoreAbove`、`docValues`、`nullValue`、`object`/`nested` 类型等）只汇总告警；`mappings(...)` 非空直接抛 `UnsupportedOperationException` |
| 视图 / 索引入口 / 触发器 / 存储过程 / 外键 / 用户 / 权限的元数据操作 | `H2MetaData` 对应入口一律抛 `UnsupportedOperationException`；可用的只有 `table(...)` 与 `search(...)` |
| 响应式 / R2DBC 引擎 | 无实现。`r2dbc-h2` 仅以 provided 声明，源码未使用（同仓 `utils-support-sqlite-starter` 有 `SqliteReactorEngine`，本模块源码未提供对应物） |
| Spring Boot 自动装配 | 无自动配置类、无 `spring.factories`、无配置属性；虽名为 starter，实际是纯库模块 |
| 需要表结构元数据的脚本改写 | 不做。`H2ScriptConverter` 是无元数据上下文的纯文本转换：`ON DUPLICATE KEY UPDATE` 整条跳过、`INSERT IGNORE` 仅剥 `IGNORE`，均不做 `MERGE INTO` 改写 |
| H2 驱动版本管理 | 不负责。`com.h2database:h2` 为 provided，由具体应用选择并提供版本 |
| 索引刷新 / 段合并 | 无对应命令。H2 索引随事务提交即可见、随 DML 自动维护；`refresh()` 只是存在性校验，`optimize()` 是库级 `ANALYZE` |
