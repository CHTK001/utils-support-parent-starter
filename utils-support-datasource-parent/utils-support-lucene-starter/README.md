# utils-support-lucene-starter

> 基于 **Apache Lucene** 的全文搜索 ORM：以 `Engine` SPI（key `lucene`）的形式提供「实体索引 + 全文检索 + SQL 风格 WHERE 条件查询 + 分页 + 分组统计」能力，附带实体↔`Document` 转换器与数据同步输出源。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.lucene.support`｜Lucene：`8.11.4`（由父 POM `${lucene.version}` 统一管理）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 查询语义：WHERE 子句 → Lucene Query](#4-查询语义where-子句--lucene-query)
- [5. 字段类型映射](#5-字段类型映射)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-lucene-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `com.chua.common.support.lang.datasource.engine.Engine` 的 Lucene 实现（SPI key `lucene`），同时实现 `FulltextSearch` 全文检索契约 | 不是 JDBC 数据库驱动，不连接任何外部数据库（`getExecutor()` / `getDialect()` 恒返回 `null`） |
| 能力 | 实体索引、关键词全文检索、WHERE 条件结构化查询、分页、分组计数、按条件更新/删除、数据同步批量写入 | 不是 Spring Boot 自动装配 starter：无自动配置类、无 `@ConfigurationProperties` 配置属性 |
| 存储 | 无参构造 = 内存索引（`ByteBuffersDirectory`）；传 `Path` = 文件系统持久化（`FSDirectory`） | 不含分布式/远程搜索服务，索引生命周期与引擎实例绑定 |
| 分词 | 仅内置 `StandardAnalyzer` | 不含中文分词（IK、smartcn 等）、高亮、facet、spatial、向量检索等 Lucene 高级特性封装 |

### 1.3 源码构成（共 4 个主源文件）

| 类 | 说明 |
| --- | --- |
| `com.chua.lucene.support.engine.LuceneEngine` | 核心引擎，继承 `AbstractEngine`，实现 `FulltextSearch`；`@Spi("lucene")` |
| `com.chua.lucene.support.engine.LuceneFields` | 字段名常量：`ID="id"`、`VERSION="_version_"`、`CONTENT="content"` |
| `com.chua.lucene.support.converter.EntityDocumentConverter` | 实体对象 ↔ Lucene `Document` 双向转换器 |
| `com.chua.lucene.support.engine.LuceneDataSyncSource` | 数据同步 OUTPUT 源：把 `Flux<Map>` 批量写入 Lucene 索引 |

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎（内存） | `new LuceneEngine()` | 索引存于进程内存，重启即丢 |
| 创建引擎（落盘） | `new LuceneEngine(Path indexPath)` | 每张表一个子目录，`FSDirectory` 持久化 |
| 按 SPI 创建 | `Engine.create("lucene")` | 走 `ServiceProvider`，返回 `Engine`，需强转回 `LuceneEngine` 才能调 `index` 等扩展方法 |
| 写入索引 | `engine.index(User.class, List.of(u1, u2))` | 按实体类名推导表名（驼峰转下划线），整批 `addDocument` + `commit` |
| 全文检索 | `engine.search(User.class, "关键词")` | 默认字段 `content`，经典 QueryParser 解析，最多返回 1000 条 |
| 全文检索（限量） | `engine.search("关键词", User.class, limit)` | `FulltextSearch` 约定入口，内部截断 |
| 结构化条件查询 | `engine.query(User.class)` + `engine.executeQuery(wrapper, User.class)` | WHERE 子句经 `SqlExpressionParser` 转 Lucene `Query`；未显式 limit 时默认最多 10000 条 |
| 分页查询 | `engine.executePage(wrapper, User.class, pn, ps)`；或 `engine.searchPage(tableName, luceneQuery, pn, ps, User.class)` | 返回 `Page<T>` |
| 分组统计 | `engine.groupBy(User.class, "dept").where(...).orderBy(...).list() / .page(pn, ps)` | 返回 `List<Map<String,Object>>`，每组带 `count` |
| 内存数据注入 | `engine.store("user", dataList)` | 注入后查询优先走内存过滤（`MemoryWhereParser`），不走索引 |
| 按条件更新 | `engine.update(User.class)` → `engine.executeUpdate(sql)` | 先按 WHERE 命中文档，还原实体、应用 SET 后整体重建索引 |
| 按条件删除 | `engine.delete(User.class)` → `engine.executeDelete(sql)` | 按WHERE 构建 Lucene Query 批量删除 |
| 建删全文索引 | `engine.createFulltextIndex(User.class)` / `engine.dropFulltextIndex(User.class)` | 目录级操作；字段名参数保留兼容，实际按表级处理 |
| 数据同步输出 | `LuceneDataSyncSource.output(engine, "user", sourceId, agentId[, batchSize])` | 实现 `DataSyncSource`，`direction()` 为 `OUTPUT`，默认批大小 1000 |
| 释放资源 | `engine.close()` | 幂等；关闭后 `isClosed()` 为 true，后续写入被拒绝 |

> `LuceneEngine` 的查询类方法都会先检查 `store(...)` 注入的内存数据：非空则完全在内存中过滤分页；为空才读 Lucene 索引。两者不要混用同一实体。

---

## 3. 快速开始

```java
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.lucene.support.engine.LuceneEngine;

import java.nio.file.Path;
import java.util.List;

public class LuceneDemo {

    public record User(Long id, String name, Integer age) {}

    public static void main(String[] args) {
        // 1. 创建引擎：无参 = 内存索引；传 Path = 落盘到 ./lucene-index
        LuceneEngine engine = new LuceneEngine();
        // LuceneEngine engine = new LuceneEngine(Path.of("./lucene-index"));
        // 也可以走 SPI：LuceneEngine engine = (LuceneEngine) Engine.create("lucene");

        // 2. 写入索引（表名 = 类名驼峰转下划线：User -> user）
        engine.index(User.class, List.of(
                new User(1L, "张三", 25),
                new User(2L, "李四", 30),
                new User(3L, "王五", 18)));

        // 3. 全文检索：关键词解析到聚合字段 content，最多返回 1000 条
        List<User> hits = engine.search(User.class, "张三");

        // 4. 带限量的全文检索（FulltextSearch 契约入口）
        List<User> top10 = engine.search("张三", User.class, 10);

        // 5. 分组统计：按 age 分组计数，返回 [{age=25, count=1}, ...]
        List<java.util.Map<String, Object>> grouped =
                engine.groupBy(User.class, "age").list();

        // 6. 释放索引目录
        engine.close();
    }
}
```

SQL 风格条件查询、分页走 `Engine` 通用入口（`query` / `executeQuery` / `executePage` / `queryCount` / `update` / `delete`），语义见下一节。

---

## 4. 查询语义：WHERE 子句 → Lucene Query

`LuceneEngine` 的结构化查询流程（源码注释原文架构）：

```text
Engine API → LambdaQueryWrapper → WHERE 子句字符串 + 参数列表
        ↓
SqlExpressionParser.parse() → BTreeNode (AST)
        ↓
BTreeNode → Lucene Query（按实体字段 Java 类型构建）
        ↓
IndexSearcher.search(Query, N) → TopDocs → Document[] → List<T>
```

参数占位符 `?` 会先被 `resolvePlaceholders` 就地替换（字符串加单引号、数值/布尔原样、null 转 `NULL`），再交给解析器。各操作符的映射：

| SQL 操作符 | Lucene 查询 |
| --- | --- |
| `AND` / `OR` | `BooleanQuery` 的 `MUST` / `SHOULD` |
| `NOT` | `MUST_NOT` 子句 |
| `=` / `!=` | 按字段类型精确查询（`IntPoint`/`LongPoint`/`FloatPoint`/`DoublePoint`/`TermQuery`）；`= null` 恒为 `MatchNoDocsQuery` |
| `>` `>=` `<` `<=` `BETWEEN` | 数值/日期走 Point 范围查询，其余走 `TermRangeQuery` |
| `IN` / `NOT IN` | 多值 `SHOULD` 组合 / 取反 `MUST_NOT` |
| `LIKE` / `NOT LIKE` | `WildcardQuery`（`%` → `*`，`_` → `?`，字面量 `*`/`?`/`\` 转义） |
| `IS NULL` / `IS NOT NULL` | 用 `TermRangeQuery(field, null, null)` 判断字段存在性（写入侧对 null 跳过字段） |
| 其它操作符 | 抛 `UnsupportedOperationException` |

日期字段（`Date` / `LocalDateTime` / `LocalDate`）按 UTC 毫秒时间戳参与 `LongPoint` 比较与范围查询。

表名推导规则：类名驼峰转下划线，如 `UserInfo` → `user_info`、`User` → `user`。

---

## 5. 字段类型映射

`EntityDocumentConverter.addFieldToDocument` 决定索引形态（所有类型均 `Store.YES` 或带 `StoredField`，保证能还原实体；null 值直接跳过字段）：

| Java 类型 | 索引字段 |
| --- | --- |
| `String` | `StringField(fieldName)` + 附加分词字段 `TextField(fieldName + "_text")`（不存储） |
| `Integer` / `Short` / `Byte` | `IntPoint` + `StoredField` |
| `Long` | `LongPoint` + `StoredField` |
| `Float` / `Double` | 对应 Point + `StoredField` |
| `BigDecimal` | `DoublePoint` + `StoredField`（存原字符串） |
| `Boolean` | `StringField`（`"true"` / `"false"`） |
| `Date` / `LocalDateTime` / `LocalDate` | `LongPoint`（UTC epoch 毫秒）+ `StoredField` |
| `LocalTime` | `StringField`（`toString()`） |
| 其它类型 | `StringField`（`String.valueOf`） |

写入实体时还会做两件额外的事：

1. **主键字段** `id`：以 `StringField(LuceneFields.ID, ...)` 单独存储，作为更新/删除时 `updateDocument` / `deleteDocuments` 的定位 Term；实体无 `id` 字段值时退化为对象 `identityHashCode`。
2. **聚合全文字段** `content`（`TextField`，不存储）：把实体中所有字符型、`Character`、枚举 name 拼接进去，供 `search(entityClass, keyword)` 的关键词检索使用；数值、布尔、日期不参与，避免稀释分词权重。

反向还原（`toEntity`）：普通类走无参构造反射 + `Converter.convertIfNecessary` 类型转换；`record` 类型走规范构造器回填，缺失组件按类型填零值（`null` / `0` / `false`）。

---

## 6. 配置项

**无。** 依据：本模块源码与资源目录中不存在任何 `application*.yml` / `*.properties` 配置文件，也没有 `@ConfigurationProperties` 配置类；Lucene 依赖版本由父 POM 的 `<lucene.version>8.11.4</lucene.version>` 统一管理，模块 POM 不允许硬编码。

唯一的「配置点」是构造参数，不是配置项：

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `indexPath` | `java.nio.file.Path` | `null` | `null` = 内存索引（`ByteBuffersDirectory`）；非空 = `FSDirectory.open(indexPath.resolve(表名))` 落盘，每张表一个子目录 |

`LuceneDataSyncSource` 的 `batchSize` 有默认值 `1000`（`DEFAULT_BATCH`），仅可通过 `output(...)` 重载显式指定，非外部配置。

---

## 7. SPI 扩展点

本模块**作为实现方**注册了以下契约（`src/main/resources/META-INF/extensions/`）：

| 接口 | key | 实现 | 说明 |
| --- | --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `lucene` | `com.chua.lucene.support.engine.LuceneEngine` | 注册文件内容：`lucene=com.chua.lucene.support.engine.LuceneEngine` |

本模块**实现的其它契约**（不通过扩展文件注册，由使用方直接构造）：

| 契约 | 实现 | 说明 |
| --- | --- | --- |
| `com.chua.common.support.lang.datasource.search.FulltextSearch` | `LuceneEngine` | `createFulltextIndex` / `search` / `dropFulltextIndex` |
| `com.chua.datasync.agent.support.DataSyncSource` | `LuceneDataSyncSource` | 方向 `OUTPUT`；`read` 恒返回 `Flux.empty()`，`currentOffset()` 返回 `null`，仅承担写入 |

本模块**没有定义新的可扩展 SPI 接口**。想扩展 Lucene 行为（自定义分词器、评分、目录等），请直接继承或包装 `LuceneEngine`，或在上层以新的 key 实现 `Engine` SPI——不要修改本模块的注册文件。

---

## 8. 构建与依赖

```bash
mvn -pl utils-support-datasource-parent/utils-support-lucene-starter -am clean package
```

- JDK 25（`maven.compiler.source/target=25`），源码 UTF-8、无 BOM。
- Lombok 作为注解处理器参与编译（`@Slf4j` 日志）。

运行期依赖（来自本模块 `pom.xml`）：

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `Engine`、`FulltextSearch`、SPI、`Converter`、`Wraps`、`ReflectUtils` 等契约与工具 |
| `com.chua:utils-support-datasource-starter` | provided | `AbstractEngine`、`MemoryWhereParser` 等引擎基座；由宿主按需引入 |
| `org.apache.lucene:lucene-core` | compile | Lucene 内核（版本 `${lucene.version}` = 8.11.4，父 POM 统一管理） |
| `org.apache.lucene:lucene-queryparser` | compile | 经典 `QueryParser`，用于关键词全文检索 |
| `com.chua:utils-support-datasync-agent-starter` | provided | 仅 `LuceneDataSyncSource` 需要；不用数据同步时可排除 |

---

## 9. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| JDBC 执行、SQL 方言、真实数据库连接 | 不提供；`getExecutor()` / `getDialect(...)` 恒返回 `null`，`addDataSource` 仅做兼容性登记 |
| Spring Boot 自动装配 / 配置属性 | 无任何自动配置类与 `@ConfigurationProperties`；这是纯 utils 层 starter |
| 中文分词（IK、smartcn 等） | 仅内置 `StandardAnalyzer`；需要中文分词请自行扩展或另选实现 |
| Lucene 高级特性封装 | 高亮、facet、spatial、向量检索、suggest 等均未封装 |
| 分布式 / 远程搜索服务 | 无；索引属于单个引擎实例，`close()` 后即释放 |
| 数据同步读取端 | `LuceneDataSyncSource` 只做 OUTPUT 写入；`read` 恒返回 `Flux.empty()`、`currentOffset()` 返回 `null` |
| 其它存储引擎 | MySQL、PostgreSQL、Elasticsearch、Neo4j、Milvus、RocksDB 等由 `utils-support-datasource-parent` 下各自 starter 提供 |

---

## 许可证

Apache License 2.0
