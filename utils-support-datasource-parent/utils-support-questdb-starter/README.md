# utils-support-questdb-starter

QuestDB 时序数据库集成模块，提供统一时序契约 `SeriesEngine` 的 QuestDB 实现（SPI 别名 `questdb`）。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-questdb-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 通过 SPI 获取引擎

```java
Properties props = new Properties();
props.setProperty("url", "http://127.0.0.1:9000");   // 必填，QuestDB HTTP 地址

SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("questdb", props);
engine.writePoint("ms_monitor_metric_cpu", 1L, "cpu_usage", 25.5, System.currentTimeMillis());
List<List<Object>> curve = engine.series("ms_monitor_metric_cpu", 1L, "cpu_usage", 1, "5m");
```

---

## 实现说明

| 能力 | 通道 | 说明 |
|------|------|------|
| 写入 `writePoint` / `writeBatch` | QuestDB 官方 Sender（ILP over HTTP，`/write?precision=n`） | `target` = 表名，`monitor_id` = tag，指标名 = 数值列，逐点同步刷写、失败即抛 |
| 指标发现 `metrics` | HTTP REST `/exec` | 读取样本行列元数据，仅返回数值列 |
| 曲线查询 `series` | HTTP REST `/exec` | 在 Java 侧按统一窗口均值降采样，与其他后端保持同一聚合语义 |
| 保留期清理 `purge` | HTTP REST `/exec` | 执行 `DELETE FROM ... WHERE timestamp < ...`，条数不可精确统计时返回 `-1` |

- 时间列：ILP 自动创建的设计时间戳列，默认名为 `timestamp`，实现会优先按表元数据探测真实列名。
- 聚合语义：与 DataSource / Redis / InfluxDB 实现共用 `SeriesEngine.downsample`。
- `database` 配置项被忽略：QuestDB 以表为存储单元，没有 InfluxDB 式的数据库概念；保留该属性仅为兼容 monitor 的统一装配路径。

---

## 配置说明

| 属性 | 必填 | 说明 |
|------|------|------|
| `url` | 是 | QuestDB HTTP 地址，如 `http://127.0.0.1:9000`；缺省 scheme 视作 `http` |
| `token` | 否 | HTTP 鉴权 token |
| `username` / `password` | 否 | HTTP 基本鉴权，成对使用 |
| `database` | 否 | 忽略 |

### 在 monitor 中启用

`spring-support-monitor-starter` 引入本依赖后，将 `monitor.timeseries.engine` 置为 `questdb`
并配置 `monitor.timeseries.url` 即可，装配统一走 `SeriesEngineConfiguration` 的 SPI 分支。

```yaml
monitor:
  timeseries:
    engine: questdb
    url: http://127.0.0.1:9000
```

---

## 依赖关系

```
utils-support-questdb-starter
├── utils-support-common-starter
├── utils-support-datasource-starter (provided)
└── org.questdb:questdb (官方客户端)
```
