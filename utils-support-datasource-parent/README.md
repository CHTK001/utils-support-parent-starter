# utils-support-datasource-parent

数据库、搜索、向量与文件表引擎的 Maven 聚合模块。共享 Engine、包装器、方言和元数据实现位于 [核心 datasource](../utils-support-core-parent/utils-support-datasource-starter/README.md)，本目录提供具体后端；各引擎支持的 SQL/ORM 子集不同，以对应 README 的边界说明为准。

## 模块目录

| 模块 README | 用途 |
|---|---|
| [calcite](utils-support-calcite-starter/README.md) | 跨数据源 SQL/联邦查询 |
| [shardingv5](utils-support-shardingv5-starter/README.md) | ShardingSphere 5 分库分表 |
| [hibernate](utils-support-hibernate-starter/README.md) | Hibernate 元数据及 DDL |
| [mysql](utils-support-mysql-starter/README.md) | MySQL 引擎、元数据/用户/索引、向量与响应式适配 |
| [postgresql](utils-support-postgresql-starter/README.md) | PostgreSQL、元数据、向量及响应式适配 |
| [oracle](utils-support-oracle-starter/README.md) | Oracle 引擎、CDC 与响应式适配 |
| [sqlserver](utils-support-sqlserver-starter/README.md) | SQL Server 引擎、元数据与响应式适配 |
| [clickhouse](utils-support-clickhouse-starter/README.md) | ClickHouse JDBC/R2DBC 接入 |
| [sqlite](utils-support-sqlite-starter/README.md) | SQLite 方言、目录轮询和响应式引擎 |
| [h2](utils-support-h2-starter/README.md) | H2 嵌入式引擎及 LIKE 检索；测试/临时环境使用 |
| [duckdb](utils-support-duckdb-starter/README.md) | DuckDB 嵌入式分析 |
| [tablesaw](utils-support-tablesaw-starter/README.md) | CSV 内存表及查询包装器 |
| [parquet](utils-support-parquet-starter/README.md) | Parquet 列式文件 |
| [neo4j](utils-support-neo4j-starter/README.md) | 图数据库适配 |
| [nitrite](utils-support-nitrite-starter/README.md) | 嵌入式文档数据库 |
| [rocksdb](utils-support-rocksdb-starter/README.md) | KV/文档/全文领域接口；拒绝通用 ORM 语义 |
| [hbase](utils-support-hbase-starter/README.md) | HBase 宽列表 |
| [elasticsearch](utils-support-elasticsearch-starter/README.md) | Elasticsearch 索引与搜索 |
| [lucene](utils-support-lucene-starter/README.md) | 本地全文索引 |
| [solr](utils-support-solr-starter/README.md) | Solr 索引与查询 |
| [milvus](utils-support-milvus-starter/README.md) | Milvus 向量引擎 |
| [jvector](utils-support-jvector-starter/README.md) | JVector CPU 向量索引 |
| [zvec](utils-support-zvec-starter/README.md) | Zvec JNI 嵌入式向量库 |
| [vector](utils-support-vector-starter/README.md) | cuVS GPU 向量检索与 CPU fallback |
| [influxdb](utils-support-influxdb-starter/README.md) | InfluxDB 时序写入/查询 |
| [questdb](utils-support-questdb-starter/README.md) | ILP 写入与 HTTP 时序查询 |
| [greptimedb](utils-support-greptimedb-starter/README.md) | gRPC 写入与 MySQL 协议时序查询 |
| [debezium](utils-support-debezium-starter/README.md) | Debezium 嵌入式 CDC |

## 安装与使用

选择具体数据库的 jar，版本统一 `4.0.0.51`，例如：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mysql-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

连接地址和账号由应用提供。先按对应引擎 README 注册数据源，再使用 Engine 的查询/更新接口；不支持的 JOIN、事务、游标或聚合能力不能由统一接口的存在推断。生产驱动由部署方按实际后端选用，H2 不进入生产依赖。

JDK 25；从仓库根构建：`mvn -pl utils-support-datasource-parent -am compile -DskipTests`。本父 POM 不启动数据库。返回 [完整目录](../README.md)。
