# utils-support-greptimedb-starter

GreptimeDB 时序数据库集成模块：**写入**走官方 gRPC Ingester SDK（`io.greptime:ingester-all`，单表/批量/流式/Bulk 堆外写入），**查询、删除与原生 SQL** 经 MySQL 协议（默认 `4002` 端口，`com.mysql:mysql-connector-j`）在 GreptimeDB 上真实执行，通过 `Engine` 接口提供统一的 Lambda 链式 ORM 能力。

## 读写架构

| 操作 | 通道 | 说明 |
|---|---|---|
| `write(Table)` / `write(Table…)` | gRPC :4001 | 官方 SDK 异步写入，自动建表；返回 `CompletableFuture`，结果需调用方自行判断 |
| `client().streamWriter()` | gRPC :4001 | 流式限速写入 |
| `client().bulkStreamWriter()` | Arrow Flight | 堆外高性能批量写入 |
| `query(Class)` Lambda 链式 | MySQL :4002 | 投影/JOIN/WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/OFFSET 全部下推服务端执行 |
| `query(Class).page(...)` | MySQL :4002 | 先 `SELECT COUNT(*) FROM (核心查询)` 取总数，再物理分页，不在内存截取 |
| `delete(Class)` Lambda 链式 | MySQL :4002 | 真实 DELETE，返回服务端报告的影响行数（条件需命中 tag/timestamp 列） |
| `query(sql, params)` / `execute(sql, params)` | MySQL :4002 | 原生 SQL 读写（推荐入口；底层为与数据源绑定的 `getExecutor()`，该方法已 `@Deprecated`） |
| `update(Class)` / `store(name,data)` | — | **时序库无 UPDATE**；`store` 为内存旁路会造假持久化：两者均抛明确异常，见下节 |

> 列名归一化：Lambda 解析出的驼峰/去下划线标识符会自动映射回实体真实 `snake_case` 列名；
> 结果集列标签回映字段时额外支持大小写与下划线宽松匹配（`cpu_util` ↔ `cpuUtil`）。
> 注入防护：表名/库名统一走 `[A-Za-z0-9_]` 白名单校验，条件值一律用 `PreparedStatement` 绑定。
> 地址推导：JDBC 地址默认按「gRPC 端点同主机 + `4002` + 数据源库名」推导，会话时区固定 UTC；
> 非标准部署可用 `setJdbcUrl("jdbc:mysql://host:4002/public?...")` 覆盖，传空白则恢复自动推导。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-greptimedb-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `GreptimeDbEngine` | GreptimeDB 引擎实现：gRPC 异步写入 + MySQL 协议真实 SQL 查询/删除（全量下推，含分页与 COUNT）。(SPI: `greptimedb`) |
| `GreptimeDbEngineDataSource` | 数据源封装，持有 `GreptimeDB` gRPC 客户端、端点/库名/鉴权信息；`close()` 后拒绝再次使用 |
| `GreptimeDbClient` | GreptimeDB gRPC 客户端工厂（基于官方 Ingester SDK，端点与库名校验后建连） |
| `GreptimeJdbcClient` | MySQL 协议 SQL 客户端：`PreparedStatement` 参数绑定、连接失效自动重建、关闭后拒绝复用 |
| `GreptimeDialect` | 配置驱动方言（`META-INF/dialect-env/greptime.env`）：反引号引用符、原生 `LIMIT n OFFSET m` 分页、MySQL 兼容类型映射，声明不支持 upsert |

---

## 使用示例

```java
// 创建引擎（SPI 扩展键 "greptimedb"）
GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");

// 添加 GreptimeDB 数据源（gRPC 端点 127.0.0.1:4001，数据库 public，用户名/密码留空表示无鉴权）
engine.addDataSource("default", "127.0.0.1:4001", "public", "", "");

// 查询/删除走 MySQL 协议：默认按「同主机 + 4002 + 库名」自动推导，非标准部署可覆盖
engine.setJdbcUrl("jdbc:mysql://127.0.0.1:4002/public?useSSL=false");

// 使用官方 SDK 构建表并写入（无 schema 自动建表）
TableSchema schema = TableSchema.newBuilder("metrics")
        .addTag("host", DataType.String)
        .addField("cpu_util", DataType.Float64)
        .addTimestamp("ts", DataType.TimestampMillisecond)
        .build();
Table table = Table.from(schema);
table.addRow("host1", 0.42, System.currentTimeMillis());

// gRPC 异步写入：future 只表示请求已提交，写入结果必须由调用方判断
engine.write(table).thenAccept(result -> {
    if (!result.isOk()) {
        throw new IllegalStateException("写入失败: " + result.getError());
    }
});

// Lambda 查询：投影 / WHERE / GROUP BY / ORDER BY / LIMIT / OFFSET 全部下推服务端
List<Metric> top10 = engine.query(Metric.class)
        .ge("ts", startMillis)
        .orderByDesc("ts")
        .limit(10)
        .list();

// 物理分页：先 COUNT 再取当页数据（总数为服务端真实计数，非内存 size）
Page<Metric> page = engine.query(Metric.class).eq("host", "host1").page(1, 20);
long total = engine.query(Metric.class).eq("host", "host1").count();

// 原生 SQL（列名可用驼峰/去下划线变体，值一律参数绑定）
List<Map<String, Object>> rows = engine.query(
        "SELECT host, AVG(cpu_util) AS avg_util FROM metrics WHERE ts > ? GROUP BY host", startMillis);
engine.execute("INSERT INTO metrics(host, cpu_util, ts) VALUES (?, ?, ?)", "host1", 0.42, now);

// 多数据源路由：getExecutor(name) 已 @Deprecated，但可用于把原生 SQL 精确绑定到指定数据源
// engine.getExecutor("shard-2").query("SELECT ...");

// 真实 DELETE：返回服务端影响行数；条件必须命中 tag/timestamp 列，否则 GreptimeDB 会拒绝
int deleted = engine.delete(Metric.class).lt("ts", cutoffMillis).remove();
```

> `update(Class)…update()` 与时序库语义冲突（相同 tag + 时间戳重新 `write` 即为覆盖 upsert）；
> `store(name, data)` 是内存旁路写入，会造成"看似成功、进程退出即丢失"的假持久化，本引擎直接禁止。
> 两者均抛出带说明的 `UnsupportedOperationException`，不会静默返回 0 制造"写入成功"假象。

---

## Docker 部署（172.16.0.40）

```bash
# 拉取镜像
docker pull greptime/greptimedb:latest

# 启动 standalone（注意需绑定 0.0.0.0，且需 seccomp=unconfined 以允许 Rust/tokio 创建线程）
docker run -d --name greptimedb \
  --security-opt seccomp=unconfined \
  -p 4000:4000 -p 4001:4001 -p 4002:4002 -p 4003:4003 \
  greptime/greptimedb:latest standalone start \
  --http-addr 0.0.0.0:4000 --grpc-bind-addr 0.0.0.0:4001 --mysql-addr 0.0.0.0:4002
```

- gRPC 写入接口（SDK 使用）：`172.16.0.40:4001`
- MySQL 协议查询/删除接口：`172.16.0.40:4002`（本模块自动按 gRPC 端点同主机推导该地址）
- HTTP `4000`：本模块不使用，仅供 `/health` 健康检查等运维用途
- 默认库 `public`，无鉴权

> 注意：GreptimeDB 各监听地址默认仅绑定 `127.0.0.1`，容器化部署必须逐个显式指定 `0.0.0.0`，
> 否则即使 `-p` 已映射，宿主机仍连不上（**尤其别忘了 `--mysql-addr`，查询通道依赖它**；
> 部分版本该参数写作 `--mysql-bind-addr`，以 `greptime standalone start --help` 为准）。
> Rust 运行时在部分 Docker seccomp 配置下会因 `Operation not permitted` 无法创建线程，
> 需加 `--security-opt seccomp=unconfined`。

---

## 依赖版本注意（protobuf / netty）

1. **protobuf 固定 3.25.1**：项目传递引入的 `protobuf-java 4.31.1` 会使 GreptimeDB proto 抛
   `NoSuchMethodError: makeExtensionsImmutable()`；而 3.21.12 又缺少 Arrow Flight 14 生成代码所需的
   `LazyStringArrayList.emptyList()`。**3.25.1**（GreptimeDB SDK 自身 dependencyManagement 所用版本）
   同时兼容两者，本模块已显式固定。

2. **Netty 全工程统一 4.2.15（Bulk 写入兼容方案）**：GreptimeDB 的 Bulk 写入依赖 Apache Arrow 14，
   其默认的 `arrow-memory-netty` 分配器会反射读取 Netty `PoolArena.chunkSize` 字段，
   该字段在 Netty 4.2 中已被移除。本模块的处理方式：
   - 从 `ingester-all` 中 **exclude 掉 `arrow-memory-netty`**；
   - 引入 **`arrow-memory-unsafe` 14.0.2**（Unsafe 堆外分配器，完全不依赖 Netty）；
   - JVM 需追加参数（Arrow 官方要求，模块 surefire 已配置）：
     `--add-opens=java.base/java.nio=ALL-UNNAMED`
   - 业务应用集成本模块并使用 Bulk 写入时，同样需要为应用 JVM 追加上述参数。

   由此 Arrow 不再对 Netty 版本有任何要求，全工程可统一使用 Netty 4.2.x。

3. **Engine 为 SPI 单例**：`Engine.create("greptimedb")` 经 ServiceProvider 返回共享实例；
   多数据源场景下切换默认数据源请显式调用 `setDefaultDataSourceName(name)`。
   每个数据源各自缓存一个 MySQL 协议连接（以 `URL|用户名|密码` 指纹识别，配置变更即关闭旧连接重建），
   查询不会串库；`getExecutor(name)` 返回的执行器与该名称绑定。

4. **查询/删除的鉴权与地址**：MySQL 协议通道复用数据源配置的用户名密码（为空即匿名连接）；
   数据库名默认 `public`，非法库名（非 `[A-Za-z0-9_]`）在推导 URL 阶段即拒绝。
   会话时区固定为 UTC，保证服务端返回的时间戳与写入值一致。

5. **生命周期**：`close()` 会关闭全部 gRPC 数据源与 JDBC 连接并置关闭标志，
   之后再取用数据源/执行 SQL 会抛 `IllegalStateException`；重新 `addDataSource` 可恢复可用。

---

## 测试

本模块的测试全部为离线纯逻辑断言，无需部署 GreptimeDB 服务：

```bash
# 在 utils-support-parent-starter 目录下执行（根 pom 默认 skipTests=true，需显式关闭）
mvn -DskipTests=false -DforkCount=0 test \
    -pl utils-support-datasource-parent/utils-support-greptimedb-starter
```

| 测试类 | 覆盖内容 |
|---|---|
| `GreptimeJdbcClientTest` | gRPC 端点 → JDBC URL 推导（端口/库名/协议前缀/IPv6/参数不泄漏）、非法端点与库名拒绝、连接串关闭后拒绝复用、空 SQL 与空批次守卫 |
| `GreptimeDbEngineTest` | SQL 下推渲染（投影/JOIN/GROUP BY/HAVING/ORDER BY/LIMIT/OFFSET、offset 无 limit 时仍生效）、表名白名单、未配数据源与已关闭时的 fail-fast、UPDATE/内存旁路拒绝、数据源封装生命周期与方言解析 |

> `-DforkCount=0` 用于在本机 Maven/JDK 组合下规避 surefire 派生 JVM 的参数传递问题；
> 若只需跑单个测试类，追加 `-Dtest=GreptimeJdbcClientTest`。
> 模块未提供集成测试（依赖真实服务，不适合默认构建）。

---

## 配置说明

本模块为零配置模块，引入依赖并通过 `addDataSource` 指定 GreptimeDB gRPC 端点即可使用；
查询/删除所需的 MySQL 协议地址由端点自动推导，无需额外配置。

---

## 依赖关系

```
utils-support-greptimedb-starter
├── utils-support-common-starter        (Engine SPI)
├── utils-support-datasource-starter    (provided，注解与方言基类)
├── io.greptime:ingester-all:0.15.0     (gRPC 写入，exclude arrow-memory-netty)
├── org.apache.arrow:arrow-memory-unsafe:14.0.2
├── com.mysql:mysql-connector-j         (版本由根 parent 统一管理)
├── com.google.protobuf:protobuf-java:3.25.1
└── org.junit.jupiter:junit-jupiter     (test)
```
