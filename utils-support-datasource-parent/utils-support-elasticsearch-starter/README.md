# utils-support-elasticsearch-starter

> Elasticsearch 搜索引擎的 `Engine` ORM 实现 + 元数据/索引管理 + 数据同步输出源 + 只读管理面 SPI。以 `Engine` / `MetaData` / `MiddlewareClientProvider` 三套 SPI 接入 utils 数据源体系，SPI 扩展键均为 `elasticsearch`。
>
> 版本：`4.0.0.51`（跟随父 POM `utils-support-datasource-parent`，类自 `4.0.0.42` 起提供）｜JDK：`25`（随父 POM 开启 `--enable-preview`）｜包名：`com.chua.elasticsearch.support`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| ORM 引擎 | `Engine` SPI 的 `elasticsearch` 实现：lambda 条件查询、bulk 写入、分页、count | 不是 SQL 引擎：`getExecutor()` 返回 `null`，不支持 SQL 式执行器 |
| 索引元数据 | `meta().search()` 提供索引 list / get / create / drop / refresh / optimize | 不是集群运维工具：不含索引模板、ILM、快照、安全管理 |
| 数据同步 | `DataSyncSource` 的 **OUTPUT** 实现：把 `Flux<Map>` 批量写入 ES 索引 | 不是输入源：`read()` 返回 `Flux.empty()`，`currentOffset()` 返回 `null` |
| 管理面 | `MiddlewareClientProvider` 的只读实现：连通性测试、索引/字段树、6 种只读查询 | 不是写通道：`DELETE` / `_bulk` / 建删索引等写操作在白名单处直接拒绝 |
| 装配方式 | 纯 SPI 模块，引入 jar 即被 `ServiceProvider` 发现，零 Spring 配置 | 不是 Spring Boot starter：无自动装配类、无 `application.yml` 约定 |

类一览（7 个源文件）：

| 类 | 职责 |
| --- | --- |
| `engine.ElasticsearchEngine` | `@Spi("elasticsearch")`，`Engine` 实现：数据源注册、bulk 写入、lambda 查询/分页/count、生命周期 |
| `engine.EsDataSyncSource` | 数据同步输出源：按 `id` 字段幂等 bulk 写入指定索引 |
| `meta.EsMetaData` | `MetaData` 入口（继承 `DefaultMetaData`），`search()` 返回 `EsMeta` |
| `meta.EsMeta` | 索引元数据操作：list / get / create（链式构建器）/ drop / refresh / optimize |
| `meta.EsSearchEngineImpl` | `SearchEngine` 实现：索引清单、mapping/settings 读写、建删索引 |
| `dialect.ElasticsearchDialect` | 方言（配置驱动），读取 `META-INF/dialect-env/elasticsearch.env` |
| `spi.ElasticsearchClientProvider` | `@Spi(value="elasticsearch", order=200)`，只读管理面（基于底层 `RestClient`） |

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎 | `Engine.create("elasticsearch")` 或 `new ElasticsearchEngine()` | SPI 键 `elasticsearch` |
| 注册数据源 | `engine.addDataSource(name, ds)` | `ds.getSource()` 为 `ElasticsearchClient` 或连接串（`es://host:port`、`http(s)://host:port`；端口缺省 `9200`） |
| 批量写入 | `engine.store(indexName, List<T>)` | bulk index；有空列表直接跳过；部分失败抛 `RuntimeException`（含失败条数） |
| 条件查询 | `engine.query(Class).eq(...).list()` | 支持 `list()` / `one()` / `page(pn, ps)` / `count()` |
| 索引管理 | `engine.meta().search()` | `list()` / `get(name)` / `create(name)` / `drop(name)` / `refresh(name)` / `optimize(name)` |
| 建索引 | `engine.meta().search().create("x").shards(3).field("t","text",cfg->cfg.analyzer("ik_max_word")).execute()` | 字段类型词表见下 |
| 数据同步输出 | `EsDataSyncSource.output(engine, index, sourceId, agentId[, batchSize])` | 默认批大小 1000；文档 id 取行内 `id` 字段，缺失则随机 UUID |
| 原生客户端 | `engine.getClient()` | 返回 `co.elastic.clients.elasticsearch.ElasticsearchClient` |

**条件算子 → ES DSL 对照**（`ElasticsearchEngine.buildConditionQuery`）：

| wrapper 条件 | ES 查询 |
| --- | --- |
| `eq` / `ne` | `term` / `bool.mustNot(term)` |
| `gt` / `ge` / `lt` / `le`、`between` | `range`（`gt/gte/lt/lte`） |
| `like` / `likeLeft` / `likeRight` | `wildcard`（`%`→`*`，`_`→`?`；值中 `*` `?` `\` 自动转义）；`NOT LIKE` 无 wrapper 快捷方法，仅作为 `Condition` 算子到达，翻译为 `bool.mustNot(wildcard)` |
| `in` / `notIn` | `terms` / `bool.mustNot(terms)`（空集合分别降级为 `match_none` / `match_all`） |
| `isNull` / `isNotNull` | `bool.mustNot(exists)` / `exists` |
| `and(Consumer)`（默认多条件） | `bool.must` |
| `or(Consumer)` | `bool.should` + `minimum_should_match=1` |
| 其它算子 | 抛 `UnsupportedOperationException` |

**字段类型词表**（`EsSearchEngineImpl.buildProperty`，大小写不敏感）：`KEYWORD`、`INTEGER`、`LONG`、`FLOAT`、`DOUBLE`、`BOOLEAN`、`DATE`、`OBJECT`、`NESTED`、`BINARY`、`IP`、`COMPLETION`；其余一律按 `TEXT` 处理（支持 `analyzer` / `searchAnalyzer`）。字段构建器另支持 `index` / `store` / `weight` / `ignoreAbove` / `docValues` / `nullValue`。

**索引名规则**：实体优先标 `@TableName`（`com.chua.datasource.support.annotation.TableName`），否则类名驼峰转下划线小写；写入 `store("x", ...)` 与 `query(Entity.class)` 依同一规则对齐到同一索引。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-elasticsearch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> `utils-support-datasource-starter` 与 `utils-support-datasync-agent-starter` 为 `provided` 作用域：用到 ORM 引擎 / 数据同步时由宿主提供这两个 jar。

### 3.2 注册引擎与数据源

`addDataSource` 的数据源对象，其 `getSource()` 可以直接给 `ElasticsearchClient`，也可以给连接串（本例用连接串，引擎内部自建 `RestClient` 并在 `close()` 时回收）：

```java
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.elasticsearch.support.engine.ElasticsearchEngine;

ElasticsearchEngine engine = new ElasticsearchEngine();
engine.addDataSource("default", new EngineDataSource<String>() {
    @Override public String name() { return "default"; }
    @Override public String getSource() { return "es://127.0.0.1:9200"; }
    @Override public EngineDataSource<String> setSource(Object source) { return this; }
    @Override public Dialect getDialect() { return null; }
    @Override public EngineDataSource<String> setDialect(Dialect dialect) { return this; }
    @Override public String url() { return "es://127.0.0.1:9200"; }
    @Override public String username() { return null; }
    @Override public String password() { return null; }
});
```

也可以走 SPI 创建：`Engine engine = Engine.create("elasticsearch");`

### 3.3 写入与查询

```java
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.annotation.TableName;
import java.util.List;

@TableName("user")   // 不标注时索引名 = 类名驼峰转下划线小写
public class User {
    private String id;
    private String name;
    private Integer age;
    // getter / setter 省略（结果映射按 setter 反射回填，类型经 Converter 转换）
}
```

```java
// 批量写入（bulk index）
engine.store("user", List.of(u1, u2, u3));

// lambda 条件查询：=、!=、>、>=、<、<=、LIKE、IN、BETWEEN、IS NULL …
List<User> adults = engine.query(User.class)
        .gt(User::getAge, 18)
        .like(User::getName, "张%")
        .orderByDesc(User::getAge)
        .list();

User one   = engine.query(User.class).eq(User::getId, "1").one();
Page<User> page = engine.query(User.class).gt(User::getAge, 18).page(1, 20);
long total = engine.query(User.class).count();   // track_total_hits 精确总数
```

### 3.4 索引管理

```java
// 建索引（链式构建器；execute() 返回 SearchIndexDef）
engine.meta().search().create("product")
        .shards(3).replicas(1)
        .field("title", "text", cfg -> cfg.analyzer("ik_max_word"))
        .field("price", "integer")
        .field("status", "keyword")
        .execute();

engine.meta().search().list();          // 全部索引
engine.meta().search().get("product");  // 单索引 settings + fields
engine.meta().search().refresh("product");
engine.meta().search().optimize("product");  // forcemerge 到 1 个段
engine.meta().search().drop("product");
```

### 3.5 数据同步输出

```java
import com.chua.elasticsearch.support.engine.EsDataSyncSource;
import reactor.core.publisher.Flux;
import java.util.HashMap;
import java.util.Map;

EsDataSyncSource output = EsDataSyncSource.output(engine, "user", "user-src", "agent-1", 500);
Map<String, Object> row = new HashMap<>();
row.put("id", "1");          // 以行内 id 字段作为文档 _id，幂等覆盖写入
row.put("name", "张三");
row.put("age", 28);
output.write(Flux.just(row));
```

### 3.6 管理面只读查询（`ElasticsearchClientProvider`）

供中间件管理/控制台类宿主通过 `MiddlewareClientProvider` SPI 使用；`query()` 只放行以下白名单表达式，其余（含任何写语句）在建客户端之前即被拒绝：

| 表达式 | 说明 |
| --- | --- |
| `nodes` | 节点列表（`_cat/nodes`） |
| `indices` | 索引概览：文档数、删除数、存储大小、健康度、状态 |
| `count <index>` | 索引文档数（`_count`） |
| `mapping <index>` | 字段映射（字段名 + 类型） |
| `search <index> [查询]` | 检索（查询缺省 `match_all`；带查询时对全字段 `match`），真实总数另走 `_count` |
| `get <index> <id>` | 取单条文档（`_doc/{id}`） |

其余能力：`test()` 连通性测试（版本、集群名、节点数、索引数，超时 5s）、`listTopNodes()` / `listChildren()` 索引→字段两级树、`document()` 单文档详情（正文超 4000 字符截断）、单次查询上限 500 行、支持 Basic 认证。

### 3.7 释放资源

```java
engine.close();     // 幂等；仅回收引擎自建的 RestClient，不关外部传入的 ElasticsearchClient
engine.isClosed();  // true
```

---

## 4. 配置项

**用户可配置项：无。** 本模块为零配置模块：源码未提供任何 Spring 配置属性、`application.yml` / `application.properties` 或 `spring-configuration-metadata`；连接信息全部通过 `addDataSource` 在代码侧给出。

模块内置一份**方言元数据**资源 `META-INF/dialect-env/elasticsearch.env`，由 `ElasticsearchDialect` 读取，属于能力探测元数据而非用户配置：

| 键 | 值 | 说明 |
| --- | --- | --- |
| `protocol` | `elasticsearch` | 协议标识 |
| `driver` | （空） | 非 JDBC，无驱动类 |
| `url` | `http://<IP>:<PORT>` | 连接串模板 |
| `query-lang` | `es` | 查询语言标识 |
| `supports-native-pagination` | `false` | 不支持 SQL 式分页，from/size 由引擎侧处理 |
| `supports-limit` | `false` | 同上 |
| `aggregation-page-size` | `10` | 聚合分页大小（可选，aggregation 场景） |
| `max-result-window` | `10000` | from/size 上限提示；深度分页需 scroll/cursor（本模块未实现） |

代码内固定常量：管理面连接/读超时 5000ms、单次查询上限 500 行、文档正文截断 4000 字符、同步写入默认批大小 1000、连接串缺省端口 9200。

---

## 5. SPI 扩展点

本模块向 utils SPI 体系注册 4 个实现，登记文件位于 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | 扩展键 | 实现类 | 说明 |
| --- | --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `elasticsearch` | `ElasticsearchEngine` | `@Spi("elasticsearch")` |
| `com.chua.common.support.lang.datasource.meta.SearchEngine` | `elasticsearch` | `EsSearchEngineImpl` | 索引元数据引擎 |
| `com.chua.common.support.lang.datasource.dialect.Dialect` | `elasticsearch` | `ElasticsearchDialect` | 方言（继承 `SqlDialect`，配置驱动） |
| `com.chua.datasource.support.middleware.MiddlewareClientProvider` | `elasticsearch` | `ElasticsearchClientProvider` | `@Spi(value="elasticsearch", order=200)`，只读管理面 |

消费方式示例：`ServiceProvider.of(Engine.class).getExtension("elasticsearch")`，或直接 `Engine.create("elasticsearch")`。

本模块自身依赖的 SPI 契约（来自底层模块）：`Engine` / `EngineDataSource` / `MetaData` / `MetaSearch` / `SearchEngine`（common-starter），`AbstractEngine` / `AbstractMetaData` / `AbstractMetaSearch` / `SqlDialect`（datasource-starter），`DataSyncSource`（datasync-agent-starter），`MiddlewareClientProvider`（datasource-starter）。

---

## 6. 构建与依赖

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-elasticsearch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

依赖关系（摘自本模块 `pom.xml`）：

```text
utils-support-elasticsearch-starter
├── utils-support-common-starter                (compile)
├── co.elastic.clients:elasticsearch-java       (compile，版本由父 POM 统一管理，当前 9.5.4)
├── org.elasticsearch.client:elasticsearch-rest-client (compile, 8.17.0)
├── com.fasterxml.jackson.core:jackson-databind (compile，版本由父 POM 统一管理)
├── utils-support-datasource-starter            (provided，宿主提供)
└── utils-support-datasync-agent-starter        (provided，宿主提供；仅数据同步需要)
```

构建：JDK 25（父 POM `maven.compiler.source/target=25`，编译带 `--enable-preview`），源码编码 UTF-8 无 BOM。`src/main/resources/META-INF/native-image/com.chua/utils-support-elasticsearch-starter/` 下内置 GraalVM `native-image` 的 `reflect-config.json` 与 `resource-config.json`。

编译验证（本模块）：`mvn -o -q compile -DskipTests -pl utils-support-elasticsearch-starter`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| SQL 执行器 | 不提供：`getExecutor()` / `getExecutor(n)` 返回 `null`，SQL 能力不可用 |
| 条件更新 / 条件删除 | `update(Class).update()` 与 `delete(Class).remove()` 直接抛 `UnsupportedOperationException`（Elasticsearch 引擎不支持） |
| 管理面写操作 | `ElasticsearchClientProvider` 只读：`DELETE` / `_bulk` / 建删索引等写操作在白名单校验即被拒绝 |
| 数据同步输入 | `EsDataSyncSource` 仅 OUTPUT：`read()` 返回 `Flux.empty()`，`currentOffset()` 返回 `null` |
| 深度分页 | from/size 受 ES `max_result_window`（默认 10000）限制；scroll / search_after / cursor 未在本模块实现 |
| Spring Boot 自动装配 | 无：本模块不依赖 Spring，无 AutoConfiguration / `spring.factories` / 配置属性类 |
| 集群运维 | 索引模板、ILM 生命周期、快照恢复、安全/用户管理、跨集群复制等不在本模块 |
| 其它搜索引擎 | Solr、RedisSearch、Milvus、Lucene 等分别由同仓对应 `*-starter` 提供 |
