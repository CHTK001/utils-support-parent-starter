# utils-support-solr-starter

> Solr 搜索引擎的 ORM 适配模块：把 Apache Solr 的集合（Collection）与 schema 字段映射为 utils 数据源体系的 `Engine` / `TableDef` / `SearchIndexDef` 统一模型，提供链式查询、分组统计、游标更新、索引管理与数据同步输出。
>
> 版本：`4.0.0.51`（父链 `utils-support-datasource-parent` → `utils-support-parent-starter`）｜JDK：`25`（`java.version=25`，随父 pom）｜包名：`com.chua.solr.support`（子包 `engine` / `meta` / `ddl`）｜核心类型自 `4.0.0.42` 起提供。

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 快速开始](#2-快速开始)
- [3. 核心 API](#3-核心-api)
  - [3.1 SolrEngine：ORM 引擎](#31-solrengineorm-引擎)
  - [3.2 条件到 Solr 查询的映射](#32-条件到-solr-查询的映射)
  - [3.3 元数据与索引管理](#33-元数据与索引管理)
  - [3.4 DDL：SolrDdlManager](#34-ddlsolrddlmanager)
  - [3.5 数据同步输出：SolrDataSyncSource](#35-数据同步输出solrdatasyncsource)
- [4. SPI 扩展点](#4-spi-扩展点)
- [5. 配置项](#5-配置项)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-solr-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 抽象 | `SearchEngine`、`Engine`、`DslManager` 三个 common 契约的 Solr 实现，SPI 名均为 `solr` | 不定义新契约（接口在 `utils-support-common-starter`） |
| 能力 | 集合即索引：ORM 写入/查询/分组/游标更新/删除、schema 字段注册、集合 DDL、数据同步 OUTPUT | 不是 SQL 引擎（`getExecutor()` / `getDialect()` 返回 `null`，SQL 式 `executeUpdate` / `executeDelete` 抛 `UnsupportedOperationException`） |
| 形态 | 纯 SPI 扩展 jar，拿到 `SolrClient` 或 Solr 地址即可用 | 不是 Spring Boot starter：无自动配置类、无 `spring.factories`、无配置属性绑定 |
| 依赖 | 编译期依赖 `solr-solrj` 9.5.0；`utils-support-datasource-starter`、`utils-support-datasync-agent-starter` 为 `provided` | 不内嵌、不管理 Solr 服务器与集群部署 |

### 1.3 规模

- 主源码 **7** 个 Java 文件：`engine`（`SolrEngine`、`SolrFields`、`SolrDataSyncSource`）、`meta`（`SolrMetaData`、`SolrMeta`、`SolrSearchEngine`）、`ddl`（`SolrDdlManager`）。
- SPI 扩展声明文件 **3** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`、`resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 接入 Solr | `SolrEngine` + `addDataSource(name, dataSource)` | `dataSource.getSource()` 返回 `SolrClient`，或返回 Solr 基础地址字符串（自动创建 `HttpSolrClient`） |
| 写入 | `SolrEngine#store(name, list)` | 实体 getter → 文档字段，缺 `id` 自动补 UUID，写入即 commit |
| 查询 / 分页 / 计数 | `SolrEngine#query(Class)` | `list()` / `one()` / `page(pn, ps)` / `count()` |
| 分组统计 | `SolrEngine#groupBy(Class, cols...)` | JSON Facet terms 分组，返回每组 group_key 与 count |
| 更新 / 删除 | `SolrEngine#update(Class)` / `#delete(Class)` | 游标深分页重建文档 / 先 count 再 deleteByQuery |
| 索引（集合）管理 | `engine.meta().search()` → `SolrMeta` | `list` / `get` / `create` / `drop` / `refresh` / `optimize` |
| DDL（集合 ≙ 表） | `SolrDdlManager` | `listTables` / `getTable` / `createTableDDL` / `createCollection` / `addField` / `dropCollection` |
| 数据同步输出 | `SolrDataSyncSource.output(...)` | `Direction.OUTPUT`，`Flux<Map>` 按批写入并 commit |

---

## 2. 快速开始

```java
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.solr.support.engine.SolrEngine;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;

// 1) 创建引擎并注册数据源。
//    EngineDataSource 的 getSource() 返回 SolrClient；或返回 Solr 基础地址字符串，
//    SolrEngine 会自动用 HttpSolrClient.Builder(url).build() 包装。
SolrClient client = new HttpSolrClient.Builder("http://localhost:8983/solr").build();
SolrEngine engine = new SolrEngine();
engine.addDataSource("solr", myEngineDataSource);   // myEngineDataSource.getSource() -> client

// 2) 写入：实体 getter 反射成文档字段；无 id 自动补 UUID；add + commit
engine.store("user", List.of(new User(1L, "张三")));

// 3) 查询：集合名 = 实体 @TableName 优先，未标注时驼峰转下划线（AbstractEngine.resolveTableName）
List<User> all   = engine.query(User.class).list();      // 默认 rows 上限 1000
User one         = engine.query(User.class).one();
Page<User> page  = engine.query(User.class).page(1, 20);
long total       = engine.query(User.class).count();     // rows=0 只取 numFound

// 4) 分组统计：GROUP BY 字段翻译为 Solr JSON Facet（terms），每行含 group_key 与 count
List<Map<String, Object>> rows = engine.groupBy(User.class, "dept")
        .limit(10)
        .list();

// 5) 更新 / 删除：走链式包装器（条件与 set 值由 common 的 LambdaUpdateWrapper /
//    LambdaDeleteWrapper 设置）；SQL 式 executeUpdate / executeDelete 不支持
int updated = engine.update(User.class).update();        // CursorMark 游标每批 1000 条重建文档
int removed = engine.delete(User.class).remove();        // 先 count，命中 > 0 才 deleteByQuery + commit
```

索引（集合）与 DDL：

```java
import com.chua.solr.support.ddl.SolrDdlManager;
import com.chua.solr.support.meta.SolrMetaData;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

// 索引管理：元数据入口
SolrMetaData meta = new SolrMetaData(engine);
MetaSearch search = meta.search();
List<SearchIndexDef> indexes = search.list();    // 全部集合及其 schema 字段
search.create("user2").shards(1).replicas(1)
        .field("nickname", "string")
        .execute();                               // 建集合（_default 配置）+ 注册 schema 字段
search.refresh("user");                           // commit
search.optimize("user");                          // 优化为 1 个段
search.drop("user2");

// DDL：集合 ≙ 表，schema 字段 ≙ 列
SolrDdlManager ddl = SolrDdlManager.of("http://localhost:8983/solr");
ddl.numShards(2).replicationFactor(1);
String json = ddl.createTableDDL(null, null, "user");
// {"create-collection":{"name":"user","numShards":2,"replicationFactor":1}}
ddl.createCollection("user3");
ddl.addField("user3", "nickname", "string");
ddl.dropCollection("user3");
```

---

## 3. 核心 API

### 3.1 SolrEngine：ORM 引擎

`com.chua.solr.support.engine.SolrEngine`，`@Spi("solr")`，继承 `AbstractEngine`。

| 方法 | 行为 |
| --- | --- |
| `addDataSource(name, dataSource)` | `getSource()` 为 `SolrClient` 时直接使用；为 `String` 时按地址创建 `HttpSolrClient`；首个数据源成为默认 |
| `setDefaultDataSourceName(name)` / `getDefaultDataSourceName()` | 默认数据源 |
| `getClient()` | 取当前 `SolrClient`（懒创建） |
| `store(name, list)` | 反射实体 getter（`getXxx` → 字段 `xxx`）写入文档；`null` 值跳过；无 `id` 补 UUID；`add` + `commit` |
| `query(entityClass)` | 返回 `LambdaQueryWrapper`：`list()`（limit≤0 时取 1000）、`one()`、`page(pn, ps)`（偏移 `(pn-1)*ps`）、`count()`（rows=0 取 numFound） |
| `groupBy(entityClass, cols...)` | 返回 `GroupByQueryWrapper`；多字段为嵌套 terms facet，子层结果放在 `children` |
| `update(entityClass)` | 返回 `LambdaUpdateWrapper`：按 `id` 排序用 CursorMark 游标深分页（每批 `UPDATE_BATCH=1000`），保留原文档字段、覆盖 set 值（跳过 `id` 与 `_version_`），最后 commit |
| `delete(entityClass)` | 返回 `LambdaDeleteWrapper`：先 rows=0 统计 numFound，命中 > 0 才 `deleteByQuery` + commit，返回删除命中数 |
| `executeUpdate(sql)` / `executeDelete(sql)` | 抛 `UnsupportedOperationException`，提示改用 `update(Class)` / `delete(Class)` 链式包装器 |
| `meta()` | 返回 `SolrMetaData` |
| `getExecutor()` / `getExecutor(name)` / `getDialect(name)` | 返回 `null`（Solr 无 SQL 执行器与方言） |
| `ddlSpiKeys()` | `["solr"]`（Solr 无 JDBC 方言，固定用 `solr` 别名找 `DslManager`） |
| `close()` | 关闭 `SolrClient` |

`GroupByQueryWrapper` 链式方法：`select(cols...)`、`eq` / `ne` / `gt` / `ge` / `lt` / `le` / `like` / `in`（条件）、`orderBy(col, asc)`、`limit(n)`（默认 1000）、`offset(n)`、`list()`（`List<Map<String, Object>>`）、`page(pn, ps)`（内存分页）。

### 3.2 条件到 Solr 查询的映射

`buildSolrQuery` 把 `Condition` 列表翻译为 Solr 查询语法；多条件默认 ` AND ` 连接，嵌套条件加括号并使用其嵌套操作符。字段名与值均做 Solr 特殊字符转义；含空格的值自动加引号。

| 操作符 | Solr 语法 |
| --- | --- |
| `=` | `field:value` |
| `!=` | `-field:value` |
| `>` / `>=` | `field:{v TO *}` / `field:[v TO *]` |
| `<` / `<=` | `field:{* TO v}` / `field:[* TO v]` |
| `LIKE` / `NOT LIKE` | `field:pattern` / `-field:pattern`（`%` → `*`，`_` → `?`，不做隐式前后补 `*`） |
| `IN` / `NOT IN` | `field:(v1 v2 ...)` / `-field:(v1 v2 ...)`（空集合时分别为 `-*:*` / `*:*`） |
| `IS NULL` / `IS NOT NULL` | `-field:[* TO *]` / `+field:[* TO *]` |
| `BETWEEN` | `field:[v1 TO v2]` |

其余操作符抛 `UnsupportedOperationException`；条件缺少列名抛 `IllegalArgumentException`。

### 3.3 元数据与索引管理

- `SolrMetaData`（继承 `DefaultMetaData`）：`search()` / `search(indexName)` 返回 `SolrMeta`。
- `SolrMeta`（继承 `AbstractMetaSearch`）：

| 方法 | 行为 |
| --- | --- |
| `list()` | 全部集合 → `List<SearchIndexDef>`（含 schema 字段名与类型） |
| `get(indexName)` | 单个索引定义，集合不存在等场景返回 `null` |
| `create(indexName)` | 返回 `SearchIndexCreateBuilder`：`shards(n)`（默认 1）、`replicas(n)`（默认 1）、`field(name, type)`、`field(name, type, Consumer<SearchFieldBuilder>)`、`fields(list)`、`settings(map)`、`mappings(map)`、`execute()` |
| `drop(indexName)` | 删除集合 |
| `refresh(indexName)` | `client.commit(indexName, true, true, true)` |
| `optimize(indexName)` | `client.optimize(indexName, true, true, 1)` |

- `SolrSearchEngine`（实现 common 的 `SearchEngine`，SPI key `solr`）：`type()` 返回 `"solr"`；`listIndexes()`（Collections API LIST）、`getIndex(name)`（Schema API Fields）、`createIndex(def)`（`createCollection(name, "_default", shards, replicas)` 后用 `ReplaceField` 写 managed schema）、`deleteIndex(name)`、`getClient()`。
- 建索引细节：字段名必须匹配 `[A-Za-z0-9_.\-]+`，否则 `IllegalArgumentException`；类型为空默认 `"string"`；`multiValued` 固定 `false`；`indexed` / `stored` / `docValues` 按定义写入。新建集合副本上线是异步过程，schema 注册最多重试 **20** 次、间隔 **500ms**。
- `SolrFields`：字段名常量 `ID = "id"`、`VERSION = "_version_"`。

### 3.4 DDL：SolrDdlManager

`com.chua.solr.support.ddl.SolrDdlManager`，`@Spi(DslManager.SPI_NAME)`，实现 `DslManager` 与 `EngineAware`。概念映射：**集合 ≙ 表，schema 字段 ≙ 列**。

| 方法 | 行为 |
| --- | --- |
| 构造 | `new SolrDdlManager(client)`、`SolrDdlManager.of(solrBaseUrl)`、SPI 无参构造（经 `setEngine` 从 `SolrEngine` 取客户端，类型不符抛 `IllegalArgumentException`） |
| `requireContext()` | 客户端未注入时抛 `IllegalStateException` |
| `numShards(n)` / `replicationFactor(n)` | 分片 / 副本数，默认均 1 |
| `listTables(catalog, schema)` | 全部集合 → `TableDef`（`type = "COLLECTION"`） |
| `getTable(catalog, schema, tableName)` | 集合不存在返回 `null`；schema 字段 → `ColumnDef`（`multiValued=true` 映射为 `nullable=false`） |
| `createTableDDL(...)` | 返回集合 API 的 JSON 管理动作文本：`{"create-collection":{"name":...,"numShards":...,"replicationFactor":...}}`（Solr 无 SQL 方言，该 JSON 提交集合 API 执行）；集合已存在时仅日志提示，不抛错 |
| `renameTable(...)` | `{"rename-collection":{...}}` |
| `copyTableStructure(...)` | 抛 `UnsupportedOperationException`（Collections API 无等价动作，请用 `createCollection` + `addField` 逐项建模） |
| `createCollection(name)` / `addField(collection, field, type)` / `dropCollection(name)` | 超出 SPI 的增强执行能力 |
| `type()` | `"solr"` |

集合名白名单：以字母或下划线开头，可含数字、`_`、`-`，最多一层点分（`a.b`），否则 `IllegalArgumentException`。

### 3.5 数据同步输出：SolrDataSyncSource

`com.chua.solr.support.engine.SolrDataSyncSource`，实现 datasync 的 `DataSyncSource`，把 `Flux<Map<String, Object>>` 批量写入 Solr 集合。

```java
SolrDataSyncSource out = SolrDataSyncSource.output(engine, "user", "src-1", "agent-1", 500);
out.write(Flux.just(Map.of("id", "1", "name", "张三")));
```

| 方法 | 行为 |
| --- | --- |
| `output(engine, collection, sourceId, agentId)` / `output(..., batchSize)` | 静态工厂；`batchSize ≤ 0` 时取默认 **1000** |
| `direction()` | 恒为 `Direction.OUTPUT` |
| `write(Flux<Map>)` | 逐行转 `SolrInputDocument`（`null` 值跳过、缺 `id` 补 UUID），按 `batchSize` 分批 commit |
| `read(offset, params)` | 恒返回 `Flux.empty()`（只出不进） |
| `currentOffset()` | 恒返回 `null` |
| `close()` | 空实现 |

---

## 4. SPI 扩展点

本模块**不定义**新 SPI，而是把 Solr 实现注册到 common-starter 的三个契约上（`src/main/resources/META-INF/extensions/`）：

| 契约接口 | key | 实现 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `solr` | `com.chua.solr.support.engine.SolrEngine` |
| `com.chua.common.support.lang.datasource.meta.SearchEngine` | `solr` | `com.chua.solr.support.meta.SolrSearchEngine` |
| `com.chua.common.support.lang.datasource.engine.ddl.DslManager` | `solr` | `com.chua.solr.support.ddl.SolrDdlManager` |

按名字装配：

```java
Engine engine = ServiceProvider.of(Engine.class).getExtension("solr");
```

`SolrDdlManager` 经 `EngineAware#setEngine` 从所属 `SolrEngine` 取客户端（`SolrEngine#ddlSpiKeys()` 固定返回 `["solr"]`），不走构造参数；直接 `new SolrDdlManager(client)` 或 `SolrDdlManager.of(baseUrl)` 亦可。

---

## 5. 配置项

**无。**

依据：本模块为纯 SPI 扩展 jar，源码中没有 Spring Boot 自动配置类、`@ConfigurationProperties` 属性类或 `application.yml` 约定。连接信息通过 `SolrEngine#addDataSource(name, dataSource)` 传入（`SolrClient` 实例或地址字符串）；分片 / 副本数通过编程式 API 设置（`SolrDdlManager#numShards` / `#replicationFactor`，以及 `SolrMeta#create(indexName)` 构建器的 `shards` / `replicas`）。

---

## 6. 构建与依赖

父链：`utils-support-datasource-parent` → `utils-support-parent-starter`（均 `4.0.0.51`），JDK `25`。

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 pom | compile | 契约（`Engine` / `SearchEngine` / `DslManager`）、工具 |
| `org.apache.solr:solr-solrj` | `9.5.0` | compile | Solr Java 客户端 |
| `com.chua:utils-support-datasource-starter` | 随父 pom | provided | `AbstractEngine` / `DefaultMetaData` 等基类 |
| `com.chua:utils-support-datasync-agent-starter` | `${project.version}` | provided | `DataSyncSource` 同步契约（仅 `SolrDataSyncSource` 需要） |

编译验证（本仓库交付口径）：

```text
mvn -o -q compile -DskipTests   → EXIT=0
```

GraalVM native-image：`src/main/resources/META-INF/native-image/com.chua/utils-support-solr-starter/` 提供 `reflect-config.json` 与 `resource-config.json`。

---

## 7. 边界：这些能力不在本模块

- **不是 Spring Boot starter**：无自动装配、无配置属性；使用方需自行创建引擎（或经 SPI 取 `solr` 实现）并调用 `addDataSource`。
- **不是 SQL 引擎**：`getExecutor()` / `getDialect()` 返回 `null`；`executeUpdate` / `executeDelete`（SQL 式）抛 `UnsupportedOperationException`，只能用 `update(Class)` / `delete(Class)` 链式包装器。
- **不支持复制表结构**：`SolrDdlManager#copyTableStructure` 抛 `UnsupportedOperationException`。
- **数据同步只出不进**：`SolrDataSyncSource` 仅 `Direction.OUTPUT`；`read` 恒为空 `Flux`，`currentOffset` 恒为 `null`。
- **字段类型便捷方法为空操作**：`SolrMeta` 的字段构建器中 `keyword()` / `text()` / `integer()` / `longType()` / `floatType()` / `doubleType()` / `date()` / `bool()` / `object()` / `nested()` / `ignoreAbove(int)` / `docValues(boolean)` / `nullValue(String)` 均直接返回 `this`；实际生效的是 `field(name, type)` 的 `type` 与 `analyzer` / `searchAnalyzer` / `index` / `store` / `weight`。
- **查询行数上限**：`query(...).list()` 在 limit ≤ 0 时默认取 1000 行；精确总数请用 `count()`（numFound）。
- **不内嵌 Solr**：不提供服务器安装、集合副本调优、SolrCloud 运维等能力；需自备 Solr 实例（示例默认 `http://localhost:8983/solr`）。
