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
