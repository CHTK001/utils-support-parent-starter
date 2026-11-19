# utils-support-datalake-query-starter

> utils 数据湖家族的**查询客户端**模块：把「数据湖-启动」API 服务端的 `POST {baseUrl}/query` 查询端点，包装成 common 体系标准的 `Engine` / `SqlExecutor` **只读**查询入口。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承根 pom `maven.compiler.source/target=25`）｜包名：`com.chua.datalake.support`（子包 `client`、`engine`）

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
    <artifactId>utils-support-datalake-query-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父模块链：`utils-support-datalake-parent`（数据中台父模块：数据采集、订阅、分析、查询）← `utils-support-parent-starter`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | 数据湖查询 API 的 **HTTP 客户端**（`DatalakeHttpClient` / `DefaultDatalakeHttpClient`），基于 JDK `java.net.http.HttpClient` | 不是数据湖服务端：不存数据、不提供 `/query` 服务 |
| 适配 | 把查询端点适配为 common 层标准查询契约 `Engine` / `SqlExecutor`（`HttpDatalakeQueryEngine`） | 不是可写引擎：注册数据源、写入、Lambda 增删改、元数据一律抛 `UnsupportedOperationException` |
| 能力 | 只读查询、类型化行映射、客户端分页切片、受影响行数解析 | 不支持 `?` 占位符绑定参数与批量执行（查询端点未定义参数通道，显式拒绝） |

**判断口径**：需要「用标准 Engine / SqlExecutor 方式查询数据湖」时用本模块；需要采集、订阅、下沉数据时用 datalake 家族其它模块。

### 1.3 规模

- Java 源文件 **3** 个：`client.DatalakeHttpClient`、`client.DefaultDatalakeHttpClient`、`engine.HttpDatalakeQueryEngine`。
- `src/main/resources` 仅有 GraalVM native-image 的 `reflect-config.json`（3 个类的反射注册）；**没有** `META-INF/extensions` SPI 注册文件，也没有任何配置文件。
- 3 个顶层类均为 `@since 4.0.0.42`，作者 CH。

---

## 2. 核心 API 与速查表

### 2.1 DatalakeHttpClient —— 原始查询客户端

`com.chua.datalake.support.client.DatalakeHttpClient`，基于 JDK `HttpClient`：

```java
DatalakeHttpClient client = new DatalakeHttpClient("http://localhost:8700");

// POST {baseUrl}/query，请求体为 SQL 原文（UTF-8 文本），返回响应体原样字符串
String body = client.query("SELECT id, name FROM t_user");

client.close();   // 空实现：JDK HttpClient 无需关闭
```

行为要点（均来自源码）：

| 项 | 行为 |
| --- | --- |
| 构造校验 | `baseUrl` 必须是 `http`/`https` 绝对地址，否则抛 `IllegalArgumentException`；尾部 `/` 会被去除 |
| 请求 | `POST {baseUrl}/query`，`Content-Type: text/plain; charset=utf-8`，请求体为查询语句原文 |
| 超时 | 连接与请求超时均固定 **30 秒**（源码常量 `REQUEST_TIMEOUT_SECONDS`） |
| 空语句 | `query(null)` / 空串抛 `IllegalArgumentException`（"查询语句不能为空"） |
| 非 2xx | 状态码 ≥ 300 抛 `IOException`，消息含 status、url 与 **≤512 字符**的响应体回显 |

### 2.2 DefaultDatalakeHttpClient —— 行列表解析

`com.chua.datalake.support.client.DefaultDatalakeHttpClient`（继承 `DatalakeHttpClient`）：

```java
DefaultDatalakeHttpClient client = new DefaultDatalakeHttpClient("http://localhost:8700");

// 查询并解析为行列表（保序，每行为 LinkedHashMap<String, Object>）
List<Map<String, Object>> rows = client.queryAsList("SELECT id, name FROM t_user");

// 也可以先拿原文再解析
String body = client.queryWithArgs("SELECT * FROM t_user");
List<Map<String, Object>> parsed = DefaultDatalakeHttpClient.parseRows(body);
```

| 方法 | 说明 |
| --- | --- |
| `queryAsList(sql, args...)` | 查询并把响应体解析为 `List<Map<String, Object>>` |
| `queryWithArgs(sql, args...)` | `args` 非空直接抛 `UnsupportedOperationException`（查询端点未定义参数通道，拒绝静默丢弃绑定参数）；否则等价于 `query(sql)` |
| `parseRows(body)`（静态） | 响应体必须是 **JSON 数组且每个元素是对象**；为空 / 非法 JSON / 非数组 / 含非对象元素均抛 `IllegalArgumentException`，回显 ≤200 字符 |

### 2.3 HttpDatalakeQueryEngine —— 只读 Engine 适配

`com.chua.datalake.support.engine.HttpDatalakeQueryEngine` 实现 common 层 `com.chua.common.support.lang.datasource.engine.Engine`，内部持有一个 `DefaultDatalakeHttpClient`：

```java
HttpDatalakeQueryEngine engine = new HttpDatalakeQueryEngine("http://localhost:8700");

// 标准 SqlExecutor 入口（Engine 接口上标记了 @Deprecated，建议优先用 querySql/executeSql；
// 但它仍是本模块公开的执行器入口）
SqlExecutor executor = engine.getExecutor();
List<Map<String, Object>> rows = executor.query("SELECT id, name FROM t_user");

// Engine 默认方法同样可用（内部走执行器）
List<Map<String, Object>> rows2 = engine.querySql("SELECT id, name FROM t_user");

engine.close();      // 幂等（AtomicBoolean）
engine.isClosed();   // true
```

能力矩阵（与源码一一对应）：

| 方法 | 行为 |
| --- | --- |
| `getExecutor()` / `getExecutor(name)` | 返回内部 `HttpSqlExecutor`（忽略数据源名） |
| `getDefaultDataSourceName()` | 恒返回 `"datalake"` |
| `setDefaultDataSourceName(name)` | 空操作，返回 `this` |
| `getDataSource(name)` / `getDataSource()` | 返回 `null`（无本地数据源） |
| `getDialect(name)` | 返回 `null` |
| `supportsMeta()` | 返回 `false` |
| `addDataSource` / `store` / `query(Class)` / `update(Class)` / `delete(Class)` / `meta()` | 抛 `UnsupportedOperationException`（只读引擎，消息带 `[datalake-query]` 前缀） |
| `close()` / `isClosed()` | `close()` 幂等；关闭后再调用无副作用 |

### 2.4 HttpSqlExecutor —— SQL 执行器

`getExecutor()` 返回的执行器（`HttpDatalakeQueryEngine` 的内部类，实现 `com.chua.common.support.lang.datasource.engine.executor.SqlExecutor`）：

| 方法 | 说明 |
| --- | --- |
| `query(sql, params...)` | 返回 `List<Map<String, Object>>`；受检异常包装为 `IllegalStateException`（消息带 `[datalake-query]` 前缀） |
| `query(sql, RowType.class, params...)` | 类型化查询：逐行用 Jackson `convertValue` 映射为指定类型 |
| `queryPage(sql, pagination, params...)` | **客户端切片分页**：整表取回后按 `pageNum/pageSize` 截取，`pagination.setTotal(全量行数)`；`pagination` 为 null 时返回全量 |
| `execute(sql, params...)` | 发送语句并把响应体解析为受影响行数（`int`）；响应体不是整数抛 `IllegalStateException` |
| `batch(sql, batchParams)` | 抛 `UnsupportedOperationException`（查询端点未定义参数通道） |

### 2.5 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 发原始查询拿响应原文 | `new DatalakeHttpClient(baseUrl).query(sdl)` | `POST {baseUrl}/query`，返回响应体字符串 |
| 查询并解析成行列表 | `new DefaultDatalakeHttpClient(baseUrl).queryAsList(sql)` | 响应必须是 JSON 数组，逐行转 `Map`，保序 |
| 解析已有响应体 | `DefaultDatalakeHttpClient.parseRows(body)` | 静态方法；空 / 非 JSON / 非数组 / 含非对象元素均抛 `IllegalArgumentException` |
| 拿标准 SQL 执行器 | `new HttpDatalakeQueryEngine(baseUrl).getExecutor()` | 返回 `SqlExecutor`：`query` / `queryPage` / `execute` |
| Engine 风格查询 | `engine.querySql(sql)`、`engine.query(sql, User.class)` | `Engine` 接口默认方法，内部走执行器 |
| 类型化行映射 | `executor.query(sql, User.class)` | Jackson `convertValue` 逐行转换 |
| 分页 | `executor.queryPage(sql, pagination)` | 无服务端分页通道：整表取回后客户端切片，`total` = 实际行数 |
| 释放资源 | `engine.close()` / `client.close()` | 均幂等；JDK `HttpClient` 无需真实关闭 |

---

## 3. 快速开始

最短可运行示例（仅依赖本模块与 common-starter）：

```java
import com.chua.datalake.support.client.DefaultDatalakeHttpClient;
import com.chua.datalake.support.engine.HttpDatalakeQueryEngine;

import java.util.List;
import java.util.Map;

public class DatalakeQueryDemo {

    public static void main(String[] args) throws Exception {
        // 数据湖-启动 API 服务地址（必须是 http/https 绝对地址）
        String baseUrl = "http://localhost:8700";

        // 用法一：HTTP 客户端直连
        DefaultDatalakeHttpClient client = new DefaultDatalakeHttpClient(baseUrl);
        List<Map<String, Object>> rows =
                client.queryAsList("SELECT id, name FROM t_user WHERE age > 18");
        System.out.println(rows);

        // 用法二：包装成标准只读 Engine
        try (HttpDatalakeQueryEngine engine = new HttpDatalakeQueryEngine(baseUrl)) {
            // Map 行
            List<Map<String, Object>> mapRows =
                    engine.querySql("SELECT id, name FROM t_user");

            // 类型化行（逐行 Jackson convertValue）
            // List<User> users = engine.querySql("SELECT id, name FROM t_user", User.class);

            // 写入类操作一律抛 UnsupportedOperationException（只读引擎），例如：
            // engine.store("t_user", List.of());
        }
    }
}
```

服务端契约（来自源码注释与实现）：

| 项 | 约定 |
| --- | --- |
| 端点 | `POST {baseUrl}/query`（服务端为「数据湖-启动」的 API 服务端） |
| 请求体 | 查询语句原文，`Content-Type: text/plain; charset=utf-8` |
| 查询响应 | JSON 数组，每个元素是一个行对象（键为列名） |
| 执行响应 | 受影响行数的整数字符串（`executor.execute` 按 `int` 解析） |
| 错误 | 状态码 ≥ 300 抛 `IOException`（消息回显 ≤512 字符响应体） |

行为限制速查：

| 场景 | 结果 |
| --- | --- |
| `query(null)` / 空语句 | `IllegalArgumentException` |
| `baseUrl` 为空 / 非法 / 非 http(s) | 构造时抛 `IllegalArgumentException` |
| 传入绑定参数（`args` 非空） | `UnsupportedOperationException`（无参数通道） |
| `executor.batch(...)` | `UnsupportedOperationException` |
| 写入 / 元数据 / Lambda 增删改 | `UnsupportedOperationException`（只读） |

---

## 4. 配置项

**无。**

依据：本模块 `src/main/resources` 下仅有 GraalVM native-image 的 `reflect-config.json`，没有任何 properties / yaml 配置文件；3 个源文件也未读取系统属性或环境变量。全部行为由**构造参数**与**源码常量**决定：

| 固定行为 | 值 | 出处 |
| --- | --- | --- |
| 查询路径 | `/query` | `DatalakeHttpClient.DEFAULT_QUERY_PATH` |
| 请求超时 | 30 秒（连接与请求同值） | `DatalakeHttpClient.REQUEST_TIMEOUT_SECONDS` |
| 错误回显上限 | 512 字符 / 200 字符 | `DatalakeHttpClient.ERROR_BODY_PREVIEW` / `DefaultDatalakeHttpClient.BODY_PREVIEW` |
| 默认数据源名 | `datalake` | `HttpDatalakeQueryEngine` 的 `defaultName` |
| `baseUrl` 约束 | http/https 绝对地址；尾部 `/` 去除；空/非法抛 `IllegalArgumentException` | `DatalakeHttpClient.requireBaseUrl` |

---

## 5. SPI 扩展点

**本模块不注册任何 SPI 扩展**：`src/main/resources` 下没有 `META-INF/extensions/` 文件。

因此：

- **不能**通过 `Engine.create("<扩展键>")` 获得本引擎（`Engine.create` 走 common 层 SPI，本模块未登记）；请直接 `new HttpDatalakeQueryEngine(baseUrl)`。
- 本模块是 common 层两个契约的**实现方**：
  - `com.chua.common.support.lang.datasource.engine.Engine` ← `HttpDatalakeQueryEngine`
  - `com.chua.common.support.lang.datasource.engine.executor.SqlExecutor` ← `HttpDatalakeQueryEngine` 的内部类 `HttpSqlExecutor`
- GraalVM native-image：提供 `META-INF/native-image/com.chua/utils-support-datalake-query-starter/reflect-config.json`，为 3 个类注册完整反射元数据（构造器/方法/字段）。

---

## 6. 构建与依赖

- 模块坐标：`com.chua:utils-support-datalake-query-starter:4.0.0.51`
- 父模块：`utils-support-datalake-parent:4.0.0.51` ← `utils-support-parent-starter:4.0.0.51`
- JDK：**25**（根 pom `maven.compiler.source/target=25`）

依赖（来自本模块 `pom.xml`）：

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `Engine` / `SqlExecutor` 契约、`Wraps`、`Json`、`StringUtils` 等公共能力 |
| `com.chua:utils-support-datalake-sink-starter` | provided | 数据湖家族模块，由宿主运行环境提供 |
| `org.projectlombok:lombok` | provided | 编译期注解（`@Slf4j`） |

编译验证：

```bash
mvn -o -q compile -DskipTests    # 在仓库根或本模块目录执行，EXIT=0 为通过
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 / 说明 |
| --- | --- |
| `/query` 服务端实现 | 「数据湖-启动」的 API 服务端；本模块只是它的 Java 客户端 |
| 数据写入、本地数据源注册 | 不支持：只读引擎，相关方法一律抛 `UnsupportedOperationException` |
| `?` 占位符绑定参数、批量执行 | 查询端点未定义参数通道，一律显式拒绝（含 `Engine.batchSql` 携参路径） |
| 服务端分页、方言、元数据、DDL、事务 | 无对应能力：`getDialect()` 返回 null、`supportsMeta()` 为 false、`meta()` 抛异常；分页只是客户端切片 |
| 数据采集 / 订阅 / 下沉（Sink）/ 实时通道 | datalake 家族其它模块：`utils-support-datalake-starter`、`utils-support-datalake-sink-starter`、`utils-support-datalake-subscribe-starter`、`utils-support-datalake-mqtt-starter`、`utils-support-datalake-webhook-starter`、`utils-support-datalake-cdc-starter` |
