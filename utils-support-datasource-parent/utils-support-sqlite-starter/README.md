# utils-support-sqlite-starter

> SQLite 嵌入式数据库支持模块：同步引擎 + 响应式引擎（JDBC 路径 + Reactor `Flux`/`Mono`）、FTS5 全文检索元数据、MySQL → SQLite 迁移脚本方言转换、`update_hook` CDC 变更捕获（同步回调 + 真响应式事件流）与表数据轮询目录。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.sqlite.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API](#2-核心-api)
  - [2.1 SqliteEngine：同步引擎](#21-sqliteengine同步引擎)
  - [2.2 SqliteReactorEngine：响应式引擎](#22-sqlitereactorengine响应式引擎)
  - [2.3 元数据与 FTS5 全文检索](#23-元数据与-fts5-全文检索)
  - [2.4 SqliteScriptConverter：迁移脚本方言转换](#24-sqlitescriptconverter迁移脚本方言转换)
  - [2.5 CDC 变更捕获](#25-cdc-变更捕获)
  - [2.6 SqlitePolledDirectory：表数据轮询目录](#26-sqlitepolleddirectory表数据轮询目录)
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
    <artifactId>utils-support-sqlite-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎实现 | `Engine` SPI（扩展名 `sqlite`）的同步实现 `SqliteEngine`，以及 `ReactorEngine` SPI（扩展名 `sqlite`）的响应式实现 `SqliteReactorEngine` | 不是通用 JDBC 抽象：`Engine`、`SqlExecutor`、`Dialect`、`JdbcEngine`、`JdbcReactorEngine` 等契约与公共逻辑在 `utils-support-common-starter` / `utils-support-datasource-starter` |
| 元数据 | 对象化建表 DDL（`SqliteMetaData`）与 FTS5 全文检索索引管理（`SqliteMetaSearch` / `SqliteSearchEngineImpl`） | 不提供视图 / 索引入口 / 触发器 / 存储过程 / 外键 / 用户 / 权限的元数据操作（`SqliteMetaData` 对这些直接抛 `UnsupportedOperationException`） |
| 迁移 | MySQL 风格 DDL/DML 到 SQLite 的方言转换（`SqliteScriptConverter`） | 不含迁移执行器本身（`Flyway` 契约在 common 层） |
| CDC | 基于 sqlite `update_hook` 的变更捕获门面：同步 `SqliteHookConnection`、响应式 `SqliteReactorHook` | 不含 native 动态库与 FFM 绑定实现（在 `utils-support-native-sqlite`） |
| 形态 | 纯 Java 库（utils 层 starter） | 不是 Spring Boot 自动配置模块：无 spring 依赖、无自动配置类、无 application 配置键 |

### 1.3 规模

- Java 源文件 **11** 个，子包 4 个：`engine`（6）、`meta`（3）、`flyway`（1）、`directory`（1）。
- SPI 扩展声明 **6** 个（`src/main/resources/META-INF/extensions/`）。
- GraalVM `native-image` 配置 2 个：`reflect-config.json`（`SqlitePolledDirectory`）与 `resource-config.json`（`META-INF/extensions/.*`）。
- 版本线索（源码 `@since`）：多数类 `4.0.0.42`；`SqliteReactorHook` `4.0.0.43`；`SqliteHookBridge` `4.0.0.50`（由反射 native 类改为 `SqliteHookSpi` 契约门面）。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 同步读写 SQLite | `SqliteEngine`（SPI 名 `sqlite`，或 `Engine.create("sqlite")`） | HikariCP + sqlite-jdbc；`execute` / `query` / `queryOne` / `queryPage` / `count` / `exists`，实体映射 `query(sql, Class)` |
| 响应式查询与写入 | `SqliteReactorEngine` | `query` → `Flux`，`execute` → `Mono<Integer>`，`batch` → `Flux<Integer>` |
| 对象化建表 DDL | `engine.meta().table()` | `SqliteMetaData`：建表 / 删表 / 重命名 |
| FTS5 全文检索索引 | `engine.meta().search()` | `create` / `list` / `get` / `drop` / `refresh` / `optimize` |
| 迁移脚本方言转换 | `SqliteScriptConverter`（SPI `ScriptConverter`，键 `sqlite`） | 自增下推、`INSERT IGNORE`、upsert、UUID 改写 |
| CDC 同步回调 | `SqliteHookConnection.open(path)` | `onEvent` + `exec` + `drain`；native 库缺失时返回 `null` |
| CDC 响应式事件流 | `new SqliteReactorHook(path)` | `events()` → `Flux<SqliteChangeEvent>`，`exec` → `Mono<Integer>` |
| 表数据轮询监听 | `SqlitePolledDirectory` | 快照对比发现插入 / 更新 / 删除，经 `PolledListener` 分发 |

---

## 2. 核心 API

### 2.1 SqliteEngine：同步引擎

`com.chua.sqlite.support.engine.SqliteEngine`，`@Spi("sqlite")`，继承 `com.chua.datasource.support.engine.JdbcEngine`，`@since 4.0.0.42`。

- `addDataSource(String name, String filePath)`：创建 HikariCP 数据源（驱动 `org.sqlite.JDBC`，`jdbc:sqlite:<path>`，`maximumPoolSize=5`，并设置 `busy_timeout=5000` —— SQLite 是单写者模型，池内并发写依靠 busy_timeout 排队等待）。
- 路径可以传 `:memory:`：为避免「连接池里每条连接各自拿到一份私有内存库」，自动改写为按数据源名隔离的共享缓存库 `file:ch_mem_<name>?mode=memory&cache=shared`（名称中的非 `[A-Za-z0-9_]` 字符替换为 `_`，name 为空按 `default` 处理）；路径为空抛 `IllegalArgumentException`。
- 注册后的数据源不支持运行期替换：对数据源调用 `setSource(...)` 抛 `UnsupportedOperationException`，需重新 `addDataSource` 注册新数据源。
- 方言默认 `Dialect.require("sqlite")`（common 层契约），可通过 `setDialect` 覆盖（传 `null` 不生效）。
- 继承自 `Engine` 的常用方法：`execute(sql, params...)`、`query(sql, params...)`、`query(sql, Class<T>, params...)`、`queryOne`、`queryValue`、`count`、`exists`、`batchSql`、`queryPage`、`insert` / `insertBatch`、`meta()`、`flyway()`、`getExecutor()`。

```java
SqliteEngine engine = new SqliteEngine();
engine.addDataSource("default", "data/app.db");   // 也支持 ":memory:"
engine.execute("CREATE TABLE IF NOT EXISTS note(id INTEGER PRIMARY KEY, title TEXT)");
List<Map<String, Object>> rows = engine.query("SELECT * FROM note");
```

### 2.2 SqliteReactorEngine：响应式引擎

`com.chua.sqlite.support.engine.SqliteReactorEngine`，`@Spi("sqlite")`，继承 `com.chua.datasource.support.engine.JdbcReactorEngine`，`@since 4.0.0.42`。

- `addDataSource(String name, String filePath)`：注册 `jdbc:sqlite:<filePath>` 数据源，返回自身支持链式调用。
- SQLite 没有对应 R2DBC 驱动：注册时按 JDBC URL 尝试转换 R2DBC 失败后自动降级为纯 JDBC 路径；对外仍是 Reactor API，阻塞 JDBC 调用包在 `Mono.fromCallable` 中执行。
- API（继承自 `ReactorEngine` 契约）：
  - `query(String sql, Object... params)` → `Flux<Map<String, Object>>`
  - `query(String sql, Class<T> rowType, Object... params)` → `Flux<T>`
  - `execute(String sql, Object... params)` → `Mono<Integer>`
  - `batch(String sql, List<Object[]> batchParams)` → `Flux<Integer>`

```java
SqliteReactorEngine reactor = new SqliteReactorEngine();
reactor.addDataSource("default", "data/app.db");
reactor.execute("INSERT INTO note(title) VALUES('reactive')").block();
reactor.query("SELECT * FROM note").subscribe(System.out::println);
```

### 2.3 元数据与 FTS5 全文检索

**SqliteMetaData**（`meta` 包，`@since 4.0.0.42`，SPI `MetaData` 键 `sqlite`，由 `engine.meta()` 自动加载）：

- `table()` / `table(String tableName)` → `JdbcMetaTable`（datasource-starter 的 JDBC 实现），提供对象化 DDL：建表 / 删表 / 重命名。
- `search()` / `search(String indexName)` → `SqliteMetaSearch`。
- `view` / `index` / `trigger` / `procedure` / `fk` / `user` / `permission` 入口 → 一律抛 `UnsupportedOperationException`（SQLite 暂不支持对应元数据操作）。

**SqliteMetaSearch / SqliteSearchEngineImpl**：基于 SQLite FTS5 虚拟表的索引管理，索引名与 FTS5 虚表名一一对应；`SqliteSearchEngineImpl.type()` 返回 `"sqlite"`，`getClient()` 返回引擎的 `DataSource`。

| 方法 | 行为 |
| --- | --- |
| `create(indexName)` | 返回 `SearchIndexCreateBuilder`：`field(name, type)`、`fields(...)`、`settings(...)`、`execute()` |
| `list()` / `get(indexName)` | 列出 / 读取 FTS5 虚表（按 `sqlite_master` 中 `USING fts5` 识别，列信息来自 `PRAGMA table_info`） |
| `drop(indexName)` | 删除虚表（表不存在返回 `false`） |
| `refresh(indexName)` | FTS5 是同步索引（写入即可检索），此方法只做存在性校验，不对不存在的索引返回成功 |
| `optimize(indexName)` | 执行 FTS5 段落合并：`INSERT INTO t(t) VALUES('optimize')` |

FTS5 能力边界（与源码行为一致）：

- 列没有类型：非 `text` / `string` / `keyword` 的字段类型按 `text` 落表并告警；`mappings(...)` 直接抛 `UnsupportedOperationException`（FTS5 虚表没有字段映射层）。
- `field(...).index(false)` 落为 FTS5 的 `UNINDEXED` 列；`analyzer`、`searchAnalyzer`、`store`、`weight`、`ignoreAbove`、`docValues`、`nullValue` 等 FTS5 无法表达的选项记录告警后忽略；`shards` / `replicas` 同样只告警（FTS5 索引只能落在单个 sqlite 文件内，没有副本概念）。
- `settings(...)` 白名单键：`tokenize`、`prefix`、`detail`、`uncompressed`、`content`、`content_rowid`；取值只允许字母 / 数字 / 下划线 / 点 / 短横线 / 空格（1~128 位），`uncompressed` 只接受 0/1；白名单外的键告警后忽略。

```java
engine.meta().search().create("note_idx")
        .field("title", "text")
        .field("body", "text")
        .execute();
engine.meta().search().list();
engine.meta().search().optimize("note_idx");
engine.meta().search().drop("note_idx");
```

### 2.4 SqliteScriptConverter：迁移脚本方言转换

`com.chua.sqlite.support.flyway.SqliteScriptConverter`，`@Spi(value = "sqlite", order = 100)`，继承 `DefaultScriptConverter`，`@since 4.0.0.42`。常量 `SqliteScriptConverter.PROTOCOL = "sqlite"`；`supports("sqlite")` 返回 `true`。

在父类通用剥离规则（表尾 `ENGINE=`、行内 `KEY`、列内联 `COMMENT`、类型/函数映射）之上，补齐 SQLite 的硬性差异（均经 sqlite-jdbc 实测确认）：

| MySQL 写法 | SQLite 改写 |
| --- | --- |
| 列级 `AUTO_INCREMENT` | 下推为 `<列> INTEGER PRIMARY KEY AUTOINCREMENT`（仅单自增列且表级 `PRIMARY KEY` 与其同名时安全下推，并删除表级主键行，避免 `table has more than one primary key`）；表尾 `AUTO_INCREMENT=100` 一并剥离 |
| `INSERT IGNORE` | `INSERT OR IGNORE` |
| `ON DUPLICATE KEY UPDATE c = VALUES(c)` | `ON CONFLICT DO UPDATE SET c = excluded.c`（不写冲突目标，对任意唯一约束生效） |
| `REPLACE(UUID(), '-', '')` 与裸 `UUID()` | `lower(hex(randomblob(16)))`（SQLite 没有 `UUID()` 函数） |

约束与前提：

- `CREATE TABLE` 必须按「每列一行」的对齐格式书写；单行紧凑写法无法识别自增列，退化为仅剥离 `AUTO_INCREMENT` 关键字（建表可执行，但列不再自动编号）。
- `convertAll(statements, protocol)` 会先做自增列预处理、再走父类通用转换 —— 父类会先把 `AUTO_INCREMENT` 关键字剥掉，必须在它之前取出列名，否则自增语义丢失（插入时主键为 null，关联表外键随即报 NOT NULL 约束失败）。

```java
SqliteScriptConverter converter = new SqliteScriptConverter();
List<String> sqliteSql = converter.convertAll(mysqlStatements, "sqlite");
```

### 2.5 CDC 变更捕获

基于 sqlite `update_hook` 的变更捕获，native 侧以 JSON `{type, table, rowId}` 上报事件，Java 侧封装为 `SqliteChangeEvent`：

- `SqliteChangeEvent.Type`：`INSERT` / `UPDATE` / `DELETE`（无法识别的类型归为 `UPDATE`，表名缺失按 `(unknown)` 处理）。
- `getType()` / `getTable()` / `getRowId()`；工厂方法 `insert(table, rowId)` / `update(...)` / `delete(...)`；`@since 4.0.0.42`。

native 依赖：`SqliteHookBridge`（包私有，`@since 4.0.0.50`）通过 `NativeServices.get(SqliteHookSpi.class)` 获取 `utils-support-native-sqlite` 注册的实现（`NativeSqliteHookSpi`，SPI 名 `native`）。native 钩子库缺失时：

- `SqliteHookConnection.open(...)` 返回 `null`（不抛异常）；
- `new SqliteReactorHook(...)` 抛 `UnsatisfiedLinkError`。

**SqliteHookConnection**（同步封装，`@since 4.0.0.42`，实现 `AutoCloseable`）：

| 方法 | 说明 |
| --- | --- |
| `open(String dbPath)`（静态） | 打开钩子连接；native 缺失或打开失败返回 `null`，原因见日志 |
| `onEvent(Consumer<SqliteChangeEvent>)` | 设置事件回调，每次 `exec` 后自动触发 |
| `exec(String sql)` | 执行 SQL，返回 SQLite 返回码（0 成功；异常返回 -1） |
| `drain()` | 取走并清空事件缓冲，返回本次排空的 `List<SqliteChangeEvent>` |
| `isOpen()` / `close()` | 状态与释放 |

**SqliteReactorHook**（真响应式封装，`@since 4.0.0.43`，实现 `AutoCloseable`）：

- 基于 OS 原生异步 I/O（IOCP / io_uring）+ Java FFM upcall 回调：零专用线程、零轮询，事件在 native 线程直接推入 Reactor `Sinks`（multicast + 256 缓冲）。
- `events()` → `Flux<SqliteChangeEvent>`，完全事件驱动；`exec(String sql)` → `Mono<Integer>`（0 成功，异常返回 -1）；`isOpen()` / `close()`。

```java
// 同步回调
try (SqliteHookConnection cdc = SqliteHookConnection.open("data/app.db")) {
    if (cdc == null) { /* native 钩子库缺失，走降级逻辑 */ return; }
    cdc.onEvent(e -> System.out.println(e.getType() + " on " + e.getTable()));
    cdc.exec("INSERT INTO note(title) VALUES('cdc')");
    List<SqliteChangeEvent> batch = cdc.drain();
}

// 响应式事件流
try (SqliteReactorHook hook = new SqliteReactorHook("data/app.db")) {
    hook.events()
        .filter(e -> "note".equals(e.getTable()))
        .subscribe(e -> System.out.println(e.getType() + " rowId=" + e.getRowId()));
    hook.exec("INSERT INTO note(title) VALUES('cdc')").subscribe();
}
```

### 2.6 SqlitePolledDirectory：表数据轮询目录

`com.chua.sqlite.support.directory.SqlitePolledDirectory`，继承 common 层 `DiffPolledDirectory<String>`，SPI `PolledDirectory` 键 `sqlite`，`@since 4.0.0.42`。

原理：定期执行查询 SQL，把每行编码为 `key|timestamp` 字符串，与前一次快照对比 key 的新增 / 消失和 timestamp 的变化，得到插入 / 更新 / 删除事件并经 `PolledListener` 分发。构造时 `listenPath` 通常传表名（默认查询 `SELECT rowid, * FROM <表名>`，表名经 `SqlName.isWord` 校验防注入）。

```java
// 源码类注释中的用法
DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(
        Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE),
        3, TimeUnit.SECONDS)
        .setProperty("jdbc.url", "jdbc:sqlite:/data/test.db");

SqlitePolledDirectory poller = new SqlitePolledDirectory("users", env);
poller.addListener(new SimplePolledListener(System.out::println));
poller.start(env);
```

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sqlite-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- 模块对 utils-support-datasource-starter 是 provided 依赖，使用方需自行引入 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasource-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 同步引擎（最短示例）

```java
import com.chua.sqlite.support.engine.SqliteEngine;

SqliteEngine engine = new SqliteEngine();
engine.addDataSource("default", "data/app.db");   // 内存库传 ":memory:"

engine.execute("CREATE TABLE IF NOT EXISTS note(id INTEGER PRIMARY KEY, title TEXT)");
engine.execute("INSERT INTO note(title) VALUES('hello')");

List<Map<String, Object>> rows = engine.query("SELECT * FROM note");
long total = engine.count("SELECT COUNT(*) FROM note");
```

### 3.3 响应式引擎

```java
import com.chua.sqlite.support.engine.SqliteReactorEngine;

SqliteReactorEngine reactor = new SqliteReactorEngine();
reactor.addDataSource("default", "data/app.db")
       .execute("INSERT INTO note(title) VALUES('reactive')")
       .block();
reactor.query("SELECT * FROM note").subscribe(System.out::println);
```

### 3.4 FTS5 全文索引

```java
engine.meta().search().create("note_idx")
        .field("title", "text")
        .field("body", "text")
        .execute();
```

### 3.5 CDC 变更捕获

```java
try (SqliteHookConnection cdc = SqliteHookConnection.open("data/app.db")) {
    cdc.onEvent(e -> System.out.println(e.getType() + " on " + e.getTable()));
    cdc.exec("INSERT INTO note(title) VALUES('cdc')");
}
```

> `SqliteHookConnection.open(...)` 可能返回 `null`（native 钩子库缺失）；`new SqliteReactorHook(...)` 则会抛 `UnsatisfiedLinkError`。生产使用前先确认 `utils-support-native-sqlite` 在 classpath 且对应平台动态库可用。

---

## 4. 配置项

**应用配置文件键：无。** 依据：本模块 pom 无任何 spring 依赖，源码中没有 `@ConfigurationProperties` 之类的配置绑定类，也没有 `spring-configuration-metadata.json`；它是纯 Java 库，所有配置都通过代码 / 环境对象传入。

代码级配置（均来自源码）：

| 位置 | 键 / 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `SqliteEngine.addDataSource(name, filePath)` | `filePath` | 是 | — | 数据库文件路径；传 `:memory:` 自动改写为 `file:ch_mem_<name>?mode=memory&cache=shared`；为空抛 `IllegalArgumentException` |
| `SqliteEngine.addDataSource` 内建 | — | — | `maximumPoolSize=5`、`busy_timeout=5000` | HikariCP 池参数，源码硬编码 |
| `SqlitePolledDirectory` 环境属性 | `jdbc.url` | 是 | — | 缺失时构造器抛 `IllegalArgumentException("缺少必填配置: jdbc.url")` |
| 同上 | `query.sql` | 否 | `SELECT rowid, * FROM <listenPath>` | 第一列作唯一键、第二列作修改时间戳 |
| 同上 | `query.key.column` | 否 | `1` | 唯一键列索引（从 1 开始） |
| 同上 | `query.ts.column` | 否 | `2` | 时间戳列索引（从 1 开始） |
| `SqliteMetaSearch` 的 `settings(...)` | `tokenize` / `prefix` / `detail` / `uncompressed` / `content` / `content_rowid` | 否 | — | FTS5 虚表选项白名单；白名单外的键告警忽略；`uncompressed` 只接受 0/1 |
| `SqliteHookConnection` / `SqliteReactorHook` | — | — | — | 无键式配置；运行前提是 classpath 存在 `SqliteHookSpi` 实现（`utils-support-native-sqlite`） |

系统属性、环境变量：源码未提供。

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI 实现

扩展名均为 `sqlite`，声明在 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | 实现类 | 说明 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `SqliteEngine` | 同步引擎，`Engine.create("sqlite")` / `ServiceProvider.of(Engine.class).getExtension("sqlite")` |
| `com.chua.datasource.support.engine.ReactorEngine` | `SqliteReactorEngine` | 响应式引擎 |
| `com.chua.common.support.lang.datasource.meta.MetaData` | `SqliteMetaData` | 元数据入口（`engine.meta()` 自动加载） |
| `com.chua.common.support.lang.datasource.meta.SearchEngine` | `SqliteSearchEngineImpl` | FTS5 索引管理 |
| `com.chua.common.support.lang.datasource.flyway.ScriptConverter` | `SqliteScriptConverter` | 迁移脚本方言转换 |
| `com.chua.common.support.lang.directory.PolledDirectory` | `SqlitePolledDirectory` | 表数据轮询目录 |

### 5.2 本模块消费的外部 SPI

| SPI | 提供方 | 缺失时行为 |
| --- | --- | --- |
| `com.chua.common.support.nativeapi.SqliteHookSpi` | `utils-support-native-sqlite`（`NativeSqliteHookSpi`，SPI 名 `native`） | CDC 不可用：`SqliteHookConnection.open` 返回 `null`，`SqliteReactorHook` 构造抛 `UnsatisfiedLinkError` |
| `com.chua.datasource.support.engine.DataSourceConversion` | calcite 等联邦实现模块 | 仅多数据源联邦查询时需要；单数据源不受影响 |

### 5.3 扩展方式

与 utils 体系一致：实现类加 `@Spi("名称")`（或 `@Extension`），并在本模块 `src/main/resources/META-INF/extensions/<接口全限定名>` 文件中登记 `名称=实现类全限定名`；运行期通过 `ServiceProvider.of(接口.class).getExtension("名称")` 获取。注意 `SqliteScriptConverter` 的扩展键必须等于协议名 `sqlite`，否则会与兜底默认实现同键互相遮蔽。

---

## 6. 构建与依赖

### 6.1 依赖清单（pom.xml）

| 依赖 | scope | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | SPI 契约与工具（同版本 `${project.version}`） |
| `com.chua:utils-support-datasource-starter` | **provided** | `JdbcEngine` / `JdbcReactorEngine` / `AbstractMetaData` 等父类；使用方需自行提供 |
| `com.zaxxer:HikariCP` | compile | 连接池 |
| `org.xerial:sqlite-jdbc` | compile | 版本由根 pom 属性 `${sqlite.jdbc.version}` = `3.53.4.0` 管理 |
| `io.projectreactor:reactor-core` | compile | `Flux` / `Mono` |

### 6.2 编译与运行要求

- JDK `25`（根 pom `maven.compiler.release=25`，全仓开启 `--enable-preview`）。
- 本模块额外启用 FFM：编译器参数 `-J--enable-native-access=ALL-UNNAMED`，surefire 运行参数 `--enable-native-access=ALL-UNNAMED`（`SqliteReactorHook` / `SqliteHookConnection` 使用 `java.lang.foreign`）。
- 常用命令：

```bash
mvn -o -q compile -DskipTests
mvn clean package -DskipTests -Dpmd.skip=true -Dmaven.test.skip=true
```

### 6.3 native-image 配置

`src/main/resources/META-INF/native-image/com.chua/utils-support-sqlite-starter/`：

- `reflect-config.json`：注册 `SqlitePolledDirectory` 的全部构造器 / 方法 / 字段反射。
- `resource-config.json`：打包 `META-INF/extensions/.*` SPI 声明文件。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `Engine` / `SqlExecutor` / `Dialect` / `Flyway` / `PolledDirectory` / `MetaData` / `SearchEngine` 等 SPI 接口与公共逻辑 | `utils-support-common-starter`（`com.chua.common.support.lang.datasource.*`） |
| `JdbcEngine`、`JdbcReactorEngine`、`AbstractMetaData`、`JdbcMetaTable`、`AbstractMetaSearch` 等 JDBC 公共父类 | `utils-support-datasource-starter`（`com.chua.datasource.support.*`，本模块 provided 引用） |
| SQLite CDC 的 native 动态库与 FFM 绑定实现 | `utils-support-native-sqlite`（`utils-support-native-parent`，`NativeSqliteHookSpi`） |
| 多数据源联邦查询（`DataSourceConversion`） | calcite 等对应 starter |
| 视图 / 索引入口 / 触发器 / 存储过程 / 外键 / 用户 / 权限的元数据操作 | 无 —— `SqliteMetaData` 对这些入口直接抛 `UnsupportedOperationException` |
| 其它数据库支持（MySQL、H2、DuckDB、PostgreSQL、ClickHouse 等） | 各自的 `utils-support-<db>-starter` |
| Spring Boot 自动配置、application.yml 配置键 | 无 —— 本模块不是 Spring Boot starter |
