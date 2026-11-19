# utils-support-cloudflare-starter

Cloudflare 集成模块：提供基于 **Cloudflare API v4** 的 **D1（SQLite）HTTP 客户端**与 **DNS 记录管理**能力，是 `utils-support-*` 最底层工具库中的纯 Java 模块。

- **坐标**：`com.chua:utils-support-cloudflare-starter:4.0.0.51`（版本随父模块 `utils-support-cloud-parent` 管理）
- **JDK**：25（随仓库根 `java.version=25`）
- **包名**：`com.chua.cloudflare.support`（子包 `com.chua.cloudflare.support.d1`、`com.chua.cloudflare.support.dns`）
- **能力起始版本**：4.0.0.42（源码 `@since` 标记）

## 目录

1. [它是什么 / 不是什么](#它是什么--不是什么)
2. [核心 API 与速查表](#核心-api-与速查表)
3. [快速开始](#快速开始)
4. [配置项](#配置项)
5. [SPI 扩展点](#spi-扩展点)
6. [构建与依赖](#构建与依赖)
7. [边界：这些能力不在本模块](#边界这些能力不在本模块)

## 它是什么 / 不是什么

| 它是 | 说明 |
|---|---|
| Cloudflare API v4 通用 HTTP 客户端 | `CloudflareClient`：Bearer Token 认证头注入、JSON 序列化、`result` 字段提取与错误解析 |
| D1 SQLite 查询引擎 | `CloudflareD1Engine`：建表/插入/更新/查询/批量/多语句，支持 `?` 位置参数与 `:name` 命名参数 |
| DNS 记录管理 SPI 实现 | `CloudflareDnsProvider`：A/AAAA/TXT/CNAME 等记录的添加、删除、查询，可用于 ACME DNS-01 验证 |
| 薄壳委托层 | 不做本地缓存、不做 SQL 方言翻译，SQL 原样提交给 D1 服务端执行 |

| 它不是 | 依据 |
|---|---|
| 不是完整的 Cloudflare SDK | 仅实现通用调用 + D1 `/query` + DNS 记录三类入口；无 R2/KV/Workers 等产品 API |
| 不是 Spring Boot Starter（无自动装配） | `src/main/resources` 下仅有 SPI 注册文件，无 `AutoConfiguration.imports`、无配置键 |
| 不是 JDBC 实现 | `D1Statement`、`D1SqlParameter` 等均为自定义 record，未实现任何 `java.sql.*` 接口 |
| 不是本地 SQLite 驱动 | D1 是 Cloudflare 托管数据库，本模块全程走 HTTPS API，不内嵌 SQLite 文件引擎 |
| 不是渠道/业务模块 | 属于 `utils-support-*` 最底层工具库，不持有任何业务表与业务规则 |

## 核心 API 与速查表

### 速查表（最常查的 8 个入口）

| # | 需求 | 入口 |
|---|---|---|
| 1 | 构造配置 | `new CloudflareConfig().setToken(...).setAccountId(...).setDatabaseId(...)` |
| 2 | 通用 API 调用 | `new CloudflareClient(config)` → `get(path)` / `post(path, payload)` / `call(method, path, payload)` |
| 3 | 创建 D1 引擎 | `new CloudflareD1Engine(client)`（或 `new CloudflareD1Engine(client, databaseId)` 显式指定库） |
| 4 | 执行写 SQL | `engine.execute(sql, params...)`；命名参数用 `engine.executeNamed(sql, namedMap)` |
| 5 | 执行查询 | `engine.query(sql, params...)` → `List<Map<String, Object>>`；命名参数用 `engine.queryNamed(...)` |
| 6 | 批量 / 多语句 | `engine.batch(List.of(D1Statement.of(...), ...))`；同一 SQL 多组参数用 `engine.multi(sql, paramsList)` |
| 7 | 取影响行数 / 自增主键 | `D1Result#getChanges()` / `D1Result#getLastRowId()` |
| 8 | DNS 记录管理（SPI） | `DnsProvider.create("cloudflare").config(DnsConfig.builder()...build())` → `addRecord` / `removeRecord` / `listRecords` |

### 类一览

| 类 | 职责 |
|---|---|
| `CloudflareConfig` | 配置：token / accountId / databaseId / baseUrl / timeoutMs，链式 setter |
| `CloudflareClient` | API v4 通用 HTTP 客户端：认证头、超时配置承载、非 2xx 与 `success=false` 错误解析 |
| `CloudflareD1Engine` | D1 引擎：`execute` / `executeNamed` / `query` / `queryNamed` / `batch` / `multi` |
| `D1Statement` | 单条 SQL + 参数（record），`of(sql, params...)` 工厂，`toJson()` 序列化为 `{"sql":..., "params":[...]}` |
| `D1SqlParameter` | 参数绑定（record）：`ofPositional` / `ofNamed`，命名参数按 SQL 中 `:name` 出现顺序展开为数组 |
| `D1Result` | 响应映射（record）：`meta` + 行列表（每行一个列名 Map）+ `success` |
| `D1BatchRequest` | 批量请求体（record）：序列化为 `{"batch": [...]}` |
| `CloudflareDnsProvider` | `DnsProvider` 的 Cloudflare 实现，`@Spi("cloudflare")` |

### D1 API 协议对照（与源码实现一致）

- 路径：`POST /accounts/{account_id}/d1/database/{database_id}/query`
- 单条请求体：`{"sql": "...", "params": [...]}`；无参数时省略 `params`；命名参数在客户端侧已按出现顺序转为数组
- 批量请求体：`{"batch": [{"sql": "...", "params": [...]}, ...]}`
- 响应体：`{"success": true, "result": [{"results": [{...}], "success": true, "meta": {"last_row_id":..., "changes":..., "duration":...}}]}`
- 单条执行时，客户端取 `result` 数组第一个元素映射为 `D1Result`

### 异常行为（源码实际抛出）

| 场景 | 异常 |
|---|---|
| `CloudflareConfig` 为 null / `CloudflareClient` 为 null | `IllegalArgumentException` |
| token 未设置或为空（构造 `CloudflareClient` 时） | `com.chua.common.support.exception.AuthenticationException` |
| HTTP 状态码非 2xx | `RuntimeException`（含 status 与响应体） |
| 响应 `success=false` | `RuntimeException`（含 `errors` 内容） |
| 执行 D1 时 databaseId 未配置 | `IllegalStateException` |
| DNS 未先 `config()` 就操作 / zoneId 未解析 | `IllegalStateException` |
| DNS 未提供 zoneId 且 zoneName 为空 | `IllegalArgumentException`；zone 查不到时抛 `IllegalStateException` |

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-cloudflare-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. D1：建表、插入、查询、批量（最短可运行示例）

```java
CloudflareConfig config = new CloudflareConfig()
        .setToken(System.getenv("CF_TOKEN"))
        .setAccountId(System.getenv("CF_ACCOUNT_ID"))
        .setDatabaseId(System.getenv("CF_DB_ID"));

CloudflareClient client = new CloudflareClient(config);
CloudflareD1Engine engine = new CloudflareD1Engine(client);

// 建表
engine.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)");

// 插入（? 位置参数）
engine.execute("INSERT INTO users(name) VALUES(?)", "Alice");

// 查询（每行一个 列名->值 的 Map）
List<Map<String, Object>> rows = engine.query("SELECT * FROM users WHERE id = ?", 1);

// 命名参数（:name 按 SQL 中出现顺序绑定）
engine.executeNamed(
        "UPDATE users SET name = :name WHERE id = :id",
        Map.of("name", "Bob", "id", 1));

// 批量（一次 HTTP 调用提交多条）
List<D1Result> results = engine.batch(List.of(
        D1Statement.of("INSERT INTO users(name) VALUES(?)", "Carol"),
        D1Statement.of("INSERT INTO users(name) VALUES(?)", "Dave")));

// 影响行数 / 自增主键
D1Result meta = engine.execute("INSERT INTO users(name) VALUES(?)", "Eve");
Object changes = meta.getChanges();
Object lastRowId = meta.getLastRowId();
```

### 3. DNS：通过 SPI 创建 Cloudflare 提供者（ACME DNS-01 场景）

```java
DnsProvider dns = DnsProvider.create("cloudflare")
        .config(DnsConfig.builder()
                .token("cf-api-token")
                .zoneName("example.com")   // 未显式给 zoneId 时，按 zoneName 自动解析
                .build());

// 添加 TXT 验证记录
dns.addRecord(DnsRecord.builder()
        .type("TXT")
        .name("_acme-challenge.example.com")
        .content("challenge-value")
        .build());

// 查询 / 删除
List<DnsRecord> records = dns.listRecords("_acme-challenge.example.com", "TXT");
dns.removeRecord(DnsRecord.builder()
        .type("TXT")
        .name("_acme-challenge.example.com")
        .build());
```

## 配置项

本模块**无 Spring Boot 自动装配，无 `application.yml` / `application.properties` 配置键**（resources 下仅有 SPI 注册文件）。配置通过下述 Java 配置类以构造方式注入；如需接入 Spring，`CloudflareConfig` 的 Javadoc 建议由宿主自行用 `@ConfigurationProperties` 绑定。

### `CloudflareConfig`（D1 与通用 API 共用）

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `token` | String | 无 | API 令牌或账户 API 键，访问 D1 推荐使用 API 令牌；必填，缺失时构造客户端抛 `AuthenticationException` |
| `accountId` | String | 无 | 账户标识，D1 REST 路径需要 |
| `databaseId` | String | 无 | 默认 D1 数据库标识；未显式传给引擎时必填 |
| `baseUrl` | String | `https://api.cloudflare.com/client/v4` | API 基础地址，Cloudflare 固定 |
| `timeoutMs` | long | `30000` | 单次请求超时（毫秒） |

### `DnsConfig`（DNS 场景，来自 common-starter 的通用配置）

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `token` | String | 无 | Cloudflare API Token |
| `zoneName` | String | 无 | 区域名称（主域名）；未给 `zoneId` 时必填，用于解析 zoneId |
| `zoneId` | String | 无 | 区域 ID，可选；显式给出时跳过按名称解析 |
| `baseUrl` | String | `https://api.cloudflare.com/client/v4` | API 基础地址，可选 |
| `timeoutMs` | long | `30000` | 单次请求超时（毫秒） |

## SPI 扩展点

本模块唯一的 SPI 扩展点是 **DNS 提供者**：

- **契约接口**：`com.chua.common.support.network.dns.DnsProvider`（由 `utils-support-common-starter` 提供，含 `config` / `addRecord` / `removeRecord` / `listRecords` 与静态工厂 `create(name)`）
- **本模块实现**：`com.chua.cloudflare.support.dns.CloudflareDnsProvider`，注解 `@Spi("cloudflare")`
- **注册文件**：`src/main/resources/META-INF/extensions/com.chua.common.support.network.dns.DnsProvider`，内容为：
  ```
  cloudflare=com.chua.cloudflare.support.dns.CloudflareDnsProvider
  ```
- **获取方式**：`DnsProvider.create("cloudflare")`，内部经 `ServiceProvider.of(DnsProvider.class).getNewExtension("cloudflare")` 实例化

实现行为要点（源自码）：

- `config(DnsConfig)`：用 token + baseUrl 构造内部 `CloudflareClient`；优先使用 `DnsConfig.zoneId`，否则以 `GET /zones?name={zoneName}` 解析
- `addRecord(DnsRecord)`：`POST /zones/{zoneId}/dns_records`，写入 `type/name/content/ttl`；`priority` 非空时附带；A/AAAA/CNAME 记录附带 `proxied`
- `removeRecord(DnsRecord)`：先按 `name + type` 调 `listRecords` 匹配，再对每条命中记录 `DELETE /zones/{zoneId}/dns_records/{id}`
- `listRecords(name, type)`：`GET /zones/{zoneId}/dns_records`，可选拼接 `name`、`type` 过滤参数，映射回 `DnsRecord`（含 `id`、`ttl`、`proxied`）

D1 能力（`CloudflareD1Engine` 等）为直接类调用，**不走 SPI**；本模块亦未提供其它 SPI 扩展点。

## 构建与依赖

在本仓库根目录执行（JDK 25）：

```bash
mvn -o -q compile -DskipTests
```

依赖树（`pom.xml` 实际声明）：

```text
com.chua:utils-support-cloudflare-starter:4.0.0.51
├── com.chua:utils-support-common-starter   （同版本；HTTP 抽象、JSON、SPI、异常、集合包装）
├── org.slf4j:slf4j-api
└── org.projectlombok:lombok                （provided）
```

- 父模块：`utils-support-cloud-parent`（聚合于 `utils-support-parent-starter`）
- 本模块不依赖任何 `spring-*` 上层业务 starter，符合「最底层工具库」的依赖方向约束
- 对外暴露的类型均在 `com.chua.cloudflare.support` 及其 `d1` / `dns` 子包内

## 边界：这些能力不在本模块

| 不提供 | 说明 |
|---|---|
| Cloudflare 其它产品 API | R2 对象存储、Workers、KV、Pages、Tunnel 等均未封装 |
| D1 数据库管理接口 | 未实现数据库的创建/删除/列表等管理端点，仅实现 `/query` 查询端点 |
| DNS 记录更新 | 只有添加（POST）、删除（DELETE）、查询（GET）；无 PUT/PATCH 更新入口 |
| DNS 区域管理 | 无 zone 的创建/删除；zoneId 需在 `DnsConfig` 中显式给出，否则按 `zoneName` 解析，`listRecords` 未封装分页参数 |
| 事务语义保证 | `batch` 是否原子执行取决于 D1 服务端行为，本模块不做本地事务补偿 |
| Spring 自动装配 | 无 `AutoConfiguration`、无 `spring.factories`、无配置属性类 |
| 本地 SQL 执行 | 不内嵌 SQLite，所有 SQL 均提交到 Cloudflare D1 服务端 |
