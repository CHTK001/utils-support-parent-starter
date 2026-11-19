# utils-support-neo4j-starter

> Neo4j 图数据库的 `Engine` ORM 实现：基于官方 `neo4j-java-driver`（Bolt 协议）执行 Cypher，提供 Lambda 链式查询 / 更新 / 删除、原生 SKIP/LIMIT 分页、条件计数与批量写入；SPI 扩展键为 `neo4j`。
>
> 版本：`4.0.0.51`（跟随父 POM `utils-support-datasource-parent`，类自 `4.0.0.42` 起提供）｜JDK：`25`（随父 POM 开启 `--enable-preview`）｜包名：`com.chua.neo4j.support`

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
| ORM 引擎 | `Engine` SPI 的 `neo4j` 实现：Lambda 条件查询、`one()`、`page()`、`count()`、条件更新 / 删除、批量写入 | 不是 SQL 引擎：`getExecutor()` / `getExecutor(n)` 返回 `null`，无 JDBC 执行器 |
| 连接管理 | `connect(uri, user, password)` 自建驱动并验证连通；`addDataSource` 支持 `Driver` 或 bolt URI 字符串 | 不是连接池：不提供池化参数调优，驱动级配置需自建 `Driver` 注入 |
| 查询语言 | Lambda 条件自动翻译为参数化 Cypher（`$pN` 命名参数），标签 / 属性名过白名单校验，防 Cypher 注入 | 不做图遍历：关系、路径、变长匹配请用 `execute()` 写原生 Cypher |
| 装配方式 | 纯 SPI 模块，引入 jar 即被 `ServiceProvider` 发现，零 Spring 配置 | 不是 Spring Boot starter：无自动装配类、无 `application.yml` 约定 |

类一览（3 个源文件）：

| 类 | 职责 |
| --- | --- |
| `engine.Neo4jEngine` | `@Spi("neo4j")`，`Engine` 实现：连接管理、Lambda 查询 / 更新 / 删除、批量写入、原生 Cypher、生命周期 |
| `engine.Neo4jEngineDataSource` | `EngineDataSource<Driver>` 实现：包装 Bolt `Driver`，携带 url / 用户名 / 密码 |
| `dialect.Neo4jDialect` | 方言（继承 `SqlDialect`，配置驱动），读取 `META-INF/dialect-env/neo4j.env` |

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建引擎 | `Engine.create("neo4j")` 或 `new Neo4jEngine()` | SPI 键 `neo4j` |
| 建立连接 | `engine.connect("bolt://host:7687", user, password)` | 自建驱动；内部执行 `RETURN 1 AS n` 验证连通 |
| 注册数据源 | `engine.addDataSource(name, ds)` | `ds.getSource()` 为 `Driver`（外部所有，`close()` 不回收）或 bolt URI 字符串（含 `@` 走 URI 内嵌认证，否则按无认证创建；引擎自建并持有）；首个注册的成为默认数据源 |
| Lambda 查询 | `engine.query(Class).eq(...).list()` | 终端：`list()` / `one()` / `page(pn, ps)` / `count()` |
| 批量写入 | `engine.store(name, List<T>)` | `UNWIND $rows AS row CREATE (n:标签) SET n = row`；空列表直接跳过；标签取自实体类（`name` 参数不参与标签解析）；只写入 getter 返回非 null 的属性 |
| 条件更新 | `engine.update(Class).set(...).eq(...).update()` | `MATCH (n:标签) [WHERE …] SET n.属性 = $setN …`，返回命中节点数；无 SET 值返回 0 |
| 条件删除 | `engine.delete(Class).eq(...).remove()` | `MATCH (n:标签) [WHERE …] DETACH DELETE n`，返回删除节点数（连带删除关系） |
| 原生 Cypher | `engine.execute(cypher, params)` | 位置参数 `p0、p1…` 绑定为 `$pN`；返回节点 / 关系建删变更计数之和 |

**条件算子 → Cypher 对照**（`Neo4jEngine.appendCondition`；多条件之间 AND，嵌套分组加括号并按分组算子拼接）：

| wrapper 条件 | Cypher |
| --- | --- |
| `eq` | `n.属性 = $p` |
| `ne` | `n.属性 <> $p` |
| `gt` / `ge` / `lt` / `le` | `> $p` / `>= $p` / `< $p` / `<= $p` |
| `between` | `n.属性 >= $p1 AND n.属性 <= $p2` |
| `like` / `notLike` | `n.属性 =~ '正则'` / `NOT n.属性 =~ '正则'`（SQL LIKE 模式转锚定全串正则：`%`→`.*`、`_`→`.`，其余正则元字符转义；null 模式编译为永不匹配的正则） |
| `isNull` / `isNotNull` | `n.属性 IS NULL` / `n.属性 IS NOT NULL` |
| `in` / `notIn` | `n.属性 IN $p` / `NOT n.属性 IN $p`（非集合值自动包装为单元素列表） |
| 其它算子 | 抛 `UnsupportedOperationException` |

**节点标签规则**：实体类优先标注 `@TableName`（`com.chua.datasource.support.annotation.TableName`）；未标注时类名驼峰转下划线（`AbstractEngine.resolveTableName`）。标签写入 Cypher 时用反引号包裹并转义内部反引号（`safeLabel`）；属性名必须通过 `SqlName.isSimple` 校验（`safeProperty`），否则抛 `IllegalArgumentException`。

**结果映射**：查询结果按节点属性 → setter 反射回填，数值类型经 `Converter.convertIfNecessary` 转换；null 值跳过基本类型字段。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-neo4j-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> `utils-support-datasource-starter` 为 `provided` 作用域：由宿主提供该 jar。

### 3.2 建立连接

```java
import com.chua.neo4j.support.engine.Neo4jEngine;

// 方式一（最短）：引擎自建驱动，connect 内部执行 RETURN 1 验证连通
Neo4jEngine engine = new Neo4jEngine();
engine.connect("bolt://127.0.0.1:7687", "neo4j", "password");
```

```java
// 方式二：注入外部 Driver（Driver 归调用方所有，engine.close() 不关闭它）
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

Driver driver = GraphDatabase.driver("bolt://127.0.0.1:7687",
        AuthTokens.basic("neo4j", "password"));
engine.addDataSource("default", new Neo4jEngineDataSource("default", driver));
```

也可以给 `addDataSource` 传 `getSource()` 返回 bolt URI 字符串的数据源：URI 含 `@` 时按内嵌认证创建驱动，否则按无认证（`AuthTokens.none()`）创建，该驱动由引擎持有并在 `close()` 时回收。

### 3.3 实体与标签

```java
import com.chua.datasource.support.annotation.TableName;

@TableName("User")   // 不标注时标签 = 类名驼峰转下划线
public class User {
    private Long id;
    private String name;
    private Integer age;
    // getter / setter 省略（写入读 getter、查询按 setter 回填）
}
```

### 3.4 写入与查询

```java
import com.chua.common.support.lang.datasource.page.Page;
import java.util.List;

// 批量写入节点
engine.store("user", List.of(u1, u2, u3));

// Lambda 条件查询
List<User> adults = engine.query(User.class)
        .gt(User::getAge, 18)
        .like(User::getName, "张%")
        .orderByDesc(User::getAge)
        .list();

User one = engine.query(User.class).eq(User::getId, 1L).one();

// 分页：SKIP/LIMIT 原生下推 + 独立 count 查询取总数
Page<User> page = engine.query(User.class).gt(User::getAge, 18).page(1, 20);

// 条件计数
long total = engine.query(User.class).like(User::getName, "张%").count();
```

### 3.5 更新与删除

```java
engine.update(User.class)
        .set(User::getName, "李四")
        .eq(User::getId, 1L)
        .update();

engine.delete(User.class).eq(User::getId, 1L).remove();   // DETACH DELETE
```

### 3.6 原生 Cypher

```java
// 位置参数 p0、p1… 自动绑定为 $pN；返回节点/关系建删变更计数之和
int changed = engine.execute(
        "MATCH (n:User) WHERE n.age > $p0 SET n.adult = true", 18);
```

### 3.7 释放资源

```java
engine.close();     // 幂等；仅回收引擎自建的 Driver，不关外部注入的 Driver
engine.isClosed();  // true
```

---

## 4. 配置项

**用户可配置项：无。** 本模块为零配置模块：源码未提供任何 Spring 配置属性、`application.yml` / `application.properties` 或 `spring-configuration-metadata`；连接信息全部通过 `connect(...)` / `addDataSource(...)` 在代码侧给出。

模块内置一份**方言元数据**资源 `META-INF/dialect-env/neo4j.env`，由 `Neo4jEngine` 构造时从类路径读取（缺失或读取失败按空属性处理），属于能力元数据而非用户配置：

| 键 | 值 | 说明 |
| --- | --- | --- |
| `protocol` | `neo4j` | 协议标识 |
| `driver` | `org.neo4j.driver.Driver` | 驱动类 |
| `url` | `bolt://<IP>:<PORT>` | 连接串模板 |
| `query-lang` | `cypher` | 查询语言标识 |
| `supports-native-pagination` | `true` | 支持 `SKIP`/`LIMIT` 原生分页；置为 `false` 时 `page()` 回退为全量查询后内存截取 |
| `supports-limit` | `true` | 支持 `LIMIT` |
| `pagination-sql` | `{sql} SKIP {offset} LIMIT {limit}` | 分页模板 |

代码内固定行为（不可通过配置修改）：

| 行为 | 说明 |
| --- | --- |
| `connect()` 驱动配置 | 不加密（`withoutEncryption`）+ 最大连接生命周期 30 分钟；需要加密 / 集群路由等请自建 `Driver` 后 `addDataSource` 注入 |
| 驱动所有权 | 外部注入的 `Driver` 归调用方所有，`close()` 不回收；引擎自建（`connect` / URI 字符串）的才回收 |
| 标识符安全 | 标签反引号包裹 + 内部反引号加倍；属性名须为简单标识符，否则抛 `IllegalArgumentException` |
| Cypher 注入防护 | 条件值一律参数化（`$pN`），不拼接字面量 |
| 错误包装 | 执行失败抛 `RuntimeException`，消息携带失败的 Cypher 语句 |

---

## 5. SPI 扩展点

本模块向 utils SPI 体系注册 2 个实现，登记文件位于 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | 扩展键 | 实现类 | 说明 |
| --- | --- | --- | --- |
| `com.chua.common.support.lang.datasource.engine.Engine` | `neo4j` | `Neo4jEngine` | `@Spi("neo4j")` |
| `com.chua.common.support.lang.datasource.dialect.Dialect` | `neo4j` | `Neo4jDialect` | 继承 `SqlDialect`，配置驱动 |

消费方式示例：`Engine.create("neo4j")`，或 `ServiceProvider.of(Engine.class).getExtension("neo4j")`；方言经 `Dialect.getExtension("neo4j")` 获取（`engine.getDialect(n)` 内部即走此路径）。

本模块自身依赖的 SPI / 基础契约（来自底层模块）：`Engine` / `EngineDataSource` / `Dialect` / `@Spi`（common-starter）；`AbstractEngine`（表名解析）、`SqlDialect`、`LambdaUtils`（Lambda 列名解析）、`@TableName`（datasource-starter）。

---

## 6. 构建与依赖

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-neo4j-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

依赖关系（摘自本模块 `pom.xml`）：

```text
utils-support-neo4j-starter
├── utils-support-common-starter           (compile)
├── org.neo4j.driver:neo4j-java-driver     (compile，版本由父 POM 统一管理；依赖快照 dtrees.txt 记录为 5.28.5)
└── utils-support-datasource-starter       (provided，宿主提供)
```

测试依赖（`dtrees.txt`）：`junit-jupiter`、`testcontainers`（含 `org.testcontainers:neo4j`），用于容器化集成测试；`lombok` 为 `provided`。

构建：JDK 25（随父 POM），源码编码 UTF-8 无 BOM。`src/main/resources/META-INF/native-image/com.chua/utils-support-neo4j-starter/` 下内置 GraalVM `native-image` 的 `reflect-config.json`（注册 `Neo4jEngine` 全量反射）与 `resource-config.json`（包含 `META-INF/extensions/.*`）。

编译验证（本模块）：`mvn -o -q compile -DskipTests -pl utils-support-neo4j-starter`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| SQL 执行器 | 不提供：`getExecutor()` / `getExecutor(n)` 返回 `null`，`supportsSql()` 为 `false` |
| 原生查询语句 | 仅覆盖写入侧 `execute()`；`querySql` / `query(String, ...)` 未实现，调用会抛 `UnsupportedOperationException`，取数请走 Lambda 包装器 |
| 插入单条 / 元数据 / DDL | `insert` / `insertBatch` 未实现（走 `Engine` 默认的 `UnsupportedOperationException`）；`supportsMeta()` 返回 `false`，`meta()` / `ddl()` 不可用 |
| 图遍历与图算法 | Lambda 包装器只做单标签节点的 `MATCH`；关系、路径、变长遍历与图算法（最短路径、社区发现等）请用 `execute()` 写原生 Cypher 或交由 Neo4j 服务端能力 |
| 驱动级调优 | `connect()` 固定不加密、连接最大生命周期 30 分钟；加密、连接池参数、集群路由等需自建 `Driver` 注入 |
| 运行期替换数据源 | `Neo4jEngineDataSource.setSource(...)` 直接抛 `UnsupportedOperationException`，请重新 `addDataSource` 注册新数据源 |
| Spring Boot 自动装配 | 无：本模块不依赖 Spring，无 AutoConfiguration / `spring.factories` / 配置属性类 |
| 其它图数据库 | Neo4j 之外的图库不在本模块；同仓数据源家族见 `utils-support-datasource-parent` 下其它 `*-starter` |
