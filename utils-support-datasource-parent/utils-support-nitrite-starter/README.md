# utils-support-nitrite-starter

> Nitrite 文档数据库的 **Engine 适配模块**：把嵌入式文档库 Nitrite（MVStore 单文件存储）接入 `Engine` 体系，同时提供全文检索（`FulltextSearch`）与类 MongoDB 文档 CRUD（`DocumentStore`）两套能力。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.nitrite.support.engine`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
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
    <artifactId>utils-support-nitrite-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎 | `Engine` 的 SPI 实现之一（扩展键 `nitrite`），嵌入式文档数据库引擎 | 不是 JDBC/SQL 引擎，没有方言与 `SqlExecutor` |
| 存储 | 基于 Nitrite 4.4.3 + MVStore 适配器，单文件落盘（`openOrCreate`） | 不是服务端数据库，无网络协议、无集群 |
| 检索 | 实现 `FulltextSearch`：`FULL_TEXT` 索引 + `text` 检索 | 不是独立搜索引擎（Lucene 能力见 `utils-support-lucene-starter`） |
| 文档 | 实现 `DocumentStore`：collection 级 insert / findById / update / delete / findAll | 不是 MongoDB 客户端 |
| ORM | 复用 `AbstractEngine` 的 Lambda 包装器（`query` / `update` / `delete`）、分页与计数 | 不做 SQL 下推；过滤、排序、分页均在内存完成 |
| 依赖 | 只依赖 `utils-support-common-starter`、`utils-support-datasource-starter`（provided）与 Nitrite | 不依赖 Spring，不依赖任何业务 starter |

**判断口径**：需要「单文件、零部署的文档存储 + 轻量全文检索」时用本模块；需要 SQL、事务、大数据量物理分页时请选 JDBC 类引擎模块。

### 1.3 规模

- Java 源文件 **2** 个：`NitriteEngine`、`NitriteEngineDataSource`，均 `@since 4.0.0.42`。
- SPI 登记文件 1 个：`META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine`。
- 无 `src/test` 测试代码。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎 | `Engine.create("nitrite")` | SPI 创建，返回 `Engine`（可强转 `NitriteEngine`）；`close()` 幂等 |
| 注册文件数据源 | `engine.addDataSource("default", "./data/app.db")` | MVStore 落盘到该文件，`openOrCreate` 打开或创建 |
| 注册已有 Nitrite 实例 | `engine.addDataSource(name, new NitriteEngineDataSource(name, nitrite, path))` | 也接受 source 为 `Nitrite` 实例或文件路径 `String` 的任意 `EngineDataSource` |
| 实体写入并落盘 | `engine.store("user", userList)` | 进内存视图 + 持久化到 Nitrite 集合 `User`（类简名） |
| Lambda 查询/分页/计数 | `engine.query(User.class).eq(...).list()` / `executePage(...)` / `queryCount(...)` | 继承自 `AbstractEngine` |
| 全文索引与检索 | `createFulltextIndex` / `search` / `dropFulltextIndex` | `FulltextSearch` 能力 |
| 文档 CRUD | `insert` / `findById` / `update` / `delete` / `findAll` | `DocumentStore` 能力，POJO / Map 皆可 |
| 原生入口 | `engine.getNitrite(name)` / `engine.getRepository(name, User.class)` | 直接使用 Nitrite 原生 API |

### 2.2 NitriteEngine

`com.chua.nitrite.support.engine.NitriteEngine extends AbstractEngine implements FulltextSearch, DocumentStore`，标注 `@Spi("nitrite")`。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `addDataSource(String name, String filePath)` | `NitriteEngine` | 注册文件型数据源：`MVStoreModule` 指定 `filePath`，`Nitrite.builder().loadModule(...).openOrCreate()` 打开或创建库 |
| `addDataSource(String name, EngineDataSource<T> ds)` | `Engine` | 注册数据源：`getSource()` 为 `Nitrite` 实例直接收纳；为 `String` 时按文件路径注册；其余类型抛 `IllegalArgumentException` |
| `getNitrite(String name)` | `Nitrite` | 取指定数据源的 Nitrite 实例 |
| `getRepository(String name, Class<T> entityClass)` | `ObjectRepository<T>` | 取 Nitrite 原生对象仓库；数据源不存在抛 `IllegalArgumentException`（"Nitrite 数据源未找到: <name>"） |
| `store(String name, List<T> data)` | `Engine` | 写入内存视图并持久化：集合名为实体类简名；实体含 `id` 字段时按 `id` upsert，否则 `insert` |
| `createFulltextIndex(Class<T> entityClass, String... fieldNames)` | `void` | 创建 `FULL_TEXT` 索引；失败抛 `RuntimeException`（"创建全文索引失败: <集合名>"） |
| `search(String query, Class<T> entityClass)` / `search(String, Class<T>, int limit)` | `List<T>` | 对实体（含父类）全部 `String` 字段做 `text` 过滤后 OR 合并，返回匹配实体；失败抛 `RuntimeException`（"全文检索失败: <query>"） |
| `dropFulltextIndex(Class<T> entityClass, String... fieldNames)` | `void` | 删除索引；失败抛 `RuntimeException`（"删除全文索引失败: <集合名>"） |
| `insert(String collection, T document)` | `T` | 插入文档（`Document` / `Map` / POJO），返回插入后的文档；引擎未连接数据源时抛 `IllegalStateException` |
| `findById(String collection, Object id, Class<T> documentClass)` | `T` | 按 `NitriteId` 或业务 `id` 字段查找，不存在返回 null |
| `update(String collection, Object id, T document)` | `T` | 全量替换指定文档，不存在返回 null |
| `delete(String collection, Object id)` | `boolean` | 删除指定文档，返回是否删除成功 |
| `findAll(String collection, Class<T> documentClass)` | `List<T>` | 全量扫描集合并映射回目标类型 |
| `close()` | `void` | 逐个关闭全部 Nitrite 实例（单个失败记日志不阻断其余），幂等 |

继承自 `AbstractEngine` / `Engine` 的常用能力：`query(Class)` / `update(Class)` / `delete(Class)` Lambda 包装器，`executeQuery` / `executePage` / `queryCount`，`insert` / `insertBatch`，`setDefaultDataSourceName` / `getDataSource` / `dataSourceNames` / `removeDataSource` / `hasDataSource`，`orm()`，`isClosed()`。

### 2.3 NitriteEngineDataSource

`com.chua.nitrite.support.engine.NitriteEngineDataSource implements EngineDataSource<Object>`，构造参数 `(String name, Nitrite nitrite, String filePath)`。

| 方法 | 行为 |
| --- | --- |
| `name()` | 返回数据源名称 |
| `getSource()` / `getSource(Class)` | 返回底层 `Nitrite` 实例 / 按类型强转 |
| `setSource(Object)` | 抛 `UnsupportedOperationException`：运行期不支持替换数据源对象，请重新 `addDataSource` 注册 |
| `getDialect()` / `setDialect(Dialect)` | 恒返回 null（非 SQL 数据源）/ 原样返回 this |
| `url()` | 返回构造时传入的数据库文件路径 |
| `username()` / `password()` | 恒返回 null |
| `tunnelPort()` / `setTunnelPort(int)` | 恒返回 0（未启用隧道）/ 空操作返回 this |
| `close()` | 接口 default 实现：底层 `Nitrite` 为 `AutoCloseable`，关闭即释放 |

### 2.4 行为约定

| 约定 | 说明 |
| --- | --- |
| 集合命名 | ORM 读写与文档 CRUD 的集合名 = `entityClass.getSimpleName()`（如 `User` → 集合 `User`） |
| id 字段 | 文档/实体的 `id` 字段；`id` 非 `NitriteId` 时按「转字符串后相等」匹配，规避数值与字符串过滤类型不匹配 |
| 内存优先 | 内存视图（`store` 写入的表）有数据时，查询走 `MemoryWhereParser` 内存过滤；否则读 Nitrite 集合并经 `toDocument` / `fromDocument` 编解码 |
| 分页 | `sliceByPage` 内存切片，`offset` / `limit` 非正值表示不限制；排序与 limit/offset 截取由基类统一后处理 |
| 类型转换 | POJO ↔ `Document` 反射互转（含父类字段，null 字段跳过；回填时经 `Converter.convertIfNecessary` 转换） |
| 错误信息 | 数据源未找到：`"Nitrite 数据源未找到: <name>"`；全文检索失败：`"全文检索失败: <query>"` 等 |

---

## 3. 快速开始

```java
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.nitrite.support.engine.NitriteEngine;

import java.util.List;
import java.util.Map;

public class NitriteDemo {

    public static void main(String[] args) {
        // 1. SPI 创建引擎（扩展键 nitrite）；Engine extends AutoCloseable，close() 幂等
        try (NitriteEngine engine = (NitriteEngine) Engine.create("nitrite")) {

            // 2. 注册文件型数据源：MVStore 落盘到该文件，openOrCreate 打开或创建
            engine.addDataSource("default", "./data/demo.db");

            // 3. 实体写入：进入内存视图，并持久化到 Nitrite 集合 User（集合名 = 类简名）
            engine.store("user", List.of(
                    new User(1L, "张三", 18),
                    new User(2L, "李四", 25),
                    new User(3L, "王五", 30)));

            // 4. Lambda 链式查询（内存过滤 + 内存分页）
            List<User> adults = engine.query(User.class)
                    .gt(User::getAge, 20)
                    .orderByDesc(User::getId)
                    .list();

            // 5. 全文检索：先建 FULL_TEXT 索引，再按关键词匹配 String 字段
            engine.createFulltextIndex(User.class, "name");
            List<User> hits = engine.search("张", User.class, 10);

            // 6. 文档式 CRUD（不依赖实体结构，POJO / Map 皆可）
            engine.insert("session", Map.of("id", "s-1", "token", "abc"));
            Object session = engine.findById("session", "s-1", Map.class);
            engine.delete("session", "s-1");

            // 7. 需要 Nitrite 原生能力时直接取实例
            org.dizitart.no2.Nitrite db = engine.getNitrite("default");
        }
    }

    /** 示例实体：含 id 字段即可被 store 的 upsert 逻辑识别。 */
    public static class User {
        private Long id;
        private String name;
        private int age;

        public User(Long id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public int getAge() { return age; }
    }
}
```

> 提示：`search` 会对实体的全部 `String` 字段（含父类）生成 `text` 过滤，请为所有参与检索的 `String` 字段调用 `createFulltextIndex`，否则对应字段过滤失败会被包装为 `RuntimeException`（"全文检索失败: ..."）。

---

## 4. 配置项

无。

依据：模块内没有 `application*.yml` / `*.properties` 配置文件，也没有 `@ConfigurationProperties` 配置类。数据源与数据库文件路径全部通过 `addDataSource(...)` 在代码中注册；Nitrite 底层仅用到 MVStore 的 `filePath`，由 `addDataSource(String name, String filePath)` 的第二个参数传入。默认数据源切换同样是代码级 API（`setDefaultDataSourceName`），不是配置键。

---

## 5. SPI 扩展点

本模块是 **SPI 提供方**，也是基座 SPI 的**消费方**：

| 方向 | 扩展点 | 说明 |
| --- | --- | --- |
| 提供 | `com.chua.common.support.lang.datasource.engine.Engine` | 登记文件 `src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine`：`nitrite=com.chua.nitrite.support.engine.NitriteEngine`；使用 `Engine.create("nitrite")` 获取 |
| 提供（能力接口实现） | `FulltextSearch`、`DocumentStore` | `com.chua.common.support.lang.datasource.search` 包下的两个能力接口，由 `NitriteEngine` 实现 |
| 消费 | `EngineInterceptor`（SPI 名 `engine-interceptor`） | 继承自 `AbstractEngine` / `Engine`：查询与更新执行前后回调 `before*` / `after*` / `onError`，业务方按 SPI 注册即可织入埋点，无需改本模块 |
| 消费 | `DslManager`、`Flyway` 等能力入口 | 继承自 `Engine`；本引擎无 JDBC 数据源，`ddl()` 会报告不支持（见第 7 节） |

自定义扩展提示：想接入新的存储，请实现 `Engine` / `AbstractEngine` 并登记到 `META-INF/extensions/...Engine`，而不是修改本模块。

---

## 6. 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-nitrite-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `org.dizitart:nitrite` | 4.4.3（`${nitrite.version}`） | 文档数据库核心 |
| `org.dizitart:nitrite-mvstore-adapter` | 4.4.3 | MVStore 文件存储适配器 |
| `com.chua:utils-support-common-starter` | 同父版本 | `Engine` / `FulltextSearch` / `DocumentStore` / SPI 契约 |
| `com.chua:utils-support-datasource-starter` | 同父版本（`provided`） | `AbstractEngine`、`MemoryWhereParser` 等引擎基座 |

构建命令（在本模块或父模块目录执行）：

```bash
mvn -o -q compile -DskipTests
```

- JDK 25 编译（`maven.compiler.release=25` + `--enable-preview`）。
- Surefire 已配置 `forkedProcessExitTimeoutInSeconds=15`；本模块当前无 `src/test` 测试代码。
- 版本号随父模块 `utils-support-datasource-parent`（`4.0.0.51`）统一管理。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| SQL / JDBC | 不支持：无 `Dialect`（`getDialect()` 返回 null）、无 `SqlExecutor`（`getExecutor()` 返回 null），`querySql` / `executeSql` 抛 `UnsupportedOperationException` |
| 元数据 / DDL / 用户管理 | 不支持：`meta()` 返回默认实现（`supportsMeta()` 为 false），`ddl()`、`user()` 抛 `UnsupportedOperationException` |
| 物理分页 | 不支持下推：过滤、排序、分页全部在内存完成，数据量大时注意全量加载成本 |
| SSH 隧道 | 不适用：`tunnelPort()` 恒为 0，`setTunnelPort` 为空操作 |
| 集群 / 分布式 | 单机嵌入式文件库，无副本、分片、网络协议 |
| 跨库/跨集合事务编排 | 本模块不提供；Nitrite 单库内部一致性由其自身保证 |
| Nitrite `EntityConverter` 实体转换 | 引擎的 ORM 读写走「原生集合 + 自研 `toDocument` / `fromDocument` 反射编解码」，未注册 Nitrite `EntityConverter`（源码注释明确此取舍）；需要原生仓库时用 `getRepository` |
| 运行期替换数据源 | `NitriteEngineDataSource.setSource` 抛 `UnsupportedOperationException`，请重新 `addDataSource` 注册新数据源 |
