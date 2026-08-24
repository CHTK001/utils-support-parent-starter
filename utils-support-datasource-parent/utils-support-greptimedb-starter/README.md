# utils-support-greptimedb-starter

GreptimeDB 时序数据库集成模块：**写入**走官方 gRPC Ingester SDK（`io.greptime:ingester-all`，单表/批量/流式/Bulk 堆外写入），**查询与删除**经 HTTP `/v1/sql` 执行真实 SQL，通过 `Engine` 接口提供统一的 Lambda 链式 ORM 能力。

## 读写架构

| 操作 | 通道 | 说明 |
|---|---|---|
| `write(Table)` / `write(Table...)` | gRPC :4001 | 官方 SDK 异步写入，自动建表 |
| `client().streamWriter()` | gRPC :4001 | 流式限速写入 |
| `client().bulkStreamWriter()` | Arrow Flight | 堆外高性能批量写入 |
| `query(Class)` Lambda 链式 | HTTP /v1/sql | WHERE/ORDER BY 翻译为真实 SQL 下推 |
| `delete(Class)` Lambda 链式 | HTTP /v1/sql | 真实 DELETE（条件需命中 tag/time 列） |
| `update(Class)` | — | **时序库无 UPDATE**：相同 tag+时间戳重新 write 即覆盖(upsert)，调用将抛出明确异常 |

> 查询列名归一化：Lambda 解析出的驼峰/去下划线标识符会自动映射回实体真实 snake_case 列名。
> gRPC 端点默认推导同主机 HTTP 端口 4000；非标准部署可 `setHttpEndpoint("http://host:port")` 覆盖。

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
| `GreptimeDbEngine` | GreptimeDB 引擎实现，基于内存过滤查询 + gRPC 异步写入。(SPI: `greptimedb`) |
| `GreptimeDbEngineDataSource` | 持有 `GreptimeDB` gRPC 客户端的数据源封装 |
| `GreptimeDbClient` | GreptimeDB 客户端工厂（基于官方 Ingester SDK） |

---

## 使用示例

```java
// 创建引擎（SPI 扩展键 "greptimedb"）
GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");

// 添加 GreptimeDB 数据源（gRPC 端点 127.0.0.1:4001，数据库 public）
engine.addDataSource("default", "127.0.0.1:4001", "public", "username", "password");

// 使用官方 SDK 构建表并写入（无 schema 自动建表）
TableSchema schema = TableSchema.newBuilder("metrics")
        .addTag("host", DataType.String)
        .addField("cpu_util", DataType.Float64)
        .addTimestamp("ts", DataType.TimestampMillisecond)
        .build();
Table table = Table.from(schema);
table.addRow("host1", 0.42, System.currentTimeMillis());

// gRPC 异步写入
engine.write(table).thenAccept(result -> {
    if (result.isOk()) {
        System.out.println("写入成功: " + result.getOk().getSuccessRows());
    }
});
```

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
  --http-addr 0.0.0.0:4000 --grpc-bind-addr 0.0.0.0:4001
```

- HTTP 接口：`http://172.16.0.40:4000`（健康检查 `/health`、SQL `/v1/sql`）
- gRPC 写入接口（SDK 使用）：`172.16.0.40:4001`
- 默认库 `public`，无鉴权

> 注意：GreptimeDB 默认命令行参数中 gRPC 绑定参数为 `--grpc-bind-addr`；
> 默认仅绑定 `127.0.0.1`，容器化部署必须显式指定 `0.0.0.0` 否则宿主机无法访问。
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

4. **查询/删除的鉴权与地址**：HTTP SQL 通道复用数据源配置的用户名密码（Basic Auth）；
   数据库名默认 `public`。

---

## 测试

```bash
# 单元测试（无需服务）
mvn test -DskipTests=false -Dtest=GreptimeDbEngineTest

# 集成测试（需先部署 172.16.0.40:4001 的 GreptimeDB）
mvn test -DskipTests=false -Dtest=GreptimeDbIntegrationTest

# 全功能集成测试（普通/批量/流式/Bulk 写入 + 多数据源分支，需 GreptimeDB 服务）
mvn test -DskipTests=false -Dtest=GreptimeDbFullIntegrationTest
```

集成测试会向 `metrics_demo` 等表写入数据并通过 HTTP SQL 校验落库。

---

## 配置说明

本模块为零配置模块，引入依赖并通过 `addDataSource` 指定 GreptimeDB 端点即可使用。

---

## 依赖关系

```
utils-support-greptimedb-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
└── io.greptime:ingester-all:0.15.0
```
