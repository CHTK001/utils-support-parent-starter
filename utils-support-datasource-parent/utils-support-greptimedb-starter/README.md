# utils-support-greptimedb-starter

GreptimeDB 时序数据库 Ingester 集成模块，基于官方 gRPC Java SDK (`io.greptime:ingester-all`) 提供 `Engine` 引擎与高性能写入能力。

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

## 依赖版本注意（protobuf）

GreptimeDB Java SDK 的 proto（`greptimedb-proto 0.9.0`）由旧版 protoc 生成，依赖
`protobuf-java 3.x` 运行期。若 classpath 上存在 `protobuf-java 4.x`（如被
`utils-support-common-starter` 传递引入 4.31.1），写入时会抛出
`NoSuchMethodError: makeExtensionsImmutable()`。本模块已在 `pom.xml` 中显式固定
`protobuf-java 3.21.12` 解决该冲突。

---

## 测试

```bash
# 单元测试（无需服务）
mvn test -DskipTests=false -Dtest=GreptimeDbEngineTest

# 集成测试（需先部署 172.16.0.40:4001 的 GreptimeDB）
mvn test -DskipTests=false -Dtest=GreptimeDbIntegrationTest
```

集成测试会向 `metrics_demo` 表写入 2 行并通过 HTTP SQL 校验落库。

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
