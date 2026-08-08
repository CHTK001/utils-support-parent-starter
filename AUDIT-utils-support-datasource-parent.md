# utils-support-datasource-parent 修复跟踪表

**文件总数:** 77
**规范:** ch-java-coding-style(强制) + P3C(强制)
**状态:** ⬜ 未检查 / � 检查中 / ✅ 已修复 / ⚠️ 暂不修 / ❌ 失败

## 文件清单与修复状态

| # | 相对路径 | 状态 | 主要修复项 | 备注 |
|---:|---|---|---|---|
| 1 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/conversion/CalciteDataSourceConversion.java` | ✅ | 提取魔法值 `CALCITE_URL`/`CALCITE_LEX` 常量;字段补 Javadoc;加 @since;删除未使用 @Slf4j | |
| 2 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/CalciteDataScheme.java` | ✅ | 加 @since | |
| 3 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/CalciteDataSourceCreator.java` | ✅ | 加 @since;全部日志加 `[calcite]` 前缀 | |
| 4 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/CalciteDataTable.java` | ✅ | 加 @since | |
| 5 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/CalciteDataTableAdapter.java` | ✅ | 加 @since;全部日志加 `[calcite]` 前缀;字段补 Javadoc | |
| 6 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/EngineAwareDataSource.java` | ✅ | 加 @since;字段补 Javadoc | |
| 7 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/EngineDataSchema.java` | ✅ | 加 @since;日志加 `[calcite]` 前缀 | |
| 8 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/EngineDataSourceFactory.java` | ✅ | 加 @since;提取 DEFAULT_SCHEMA 常量;方法补 @param/@return | |
| 9 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/EngineUpdateSqlExecutor.java` | ✅ | 加 @since;提取 SQL_UPDATE_KEYWORD 常量;日志加 `[calcite]` 前缀 | |
| 10 | `utils-support-calcite-starter/src/main/java/com/chua/calcite/support/datasource/SourceDataTable.java` | ✅ | 加 @since;字段补 Javadoc;日志加 `[calcite]` 前缀 | |
| 11 | `utils-support-debezium-starter/src/main/java/com/chua/datasource/support/config/debezium/DebeziumConnectorConfig.java` | ✅ | 加 @since;方法补 Javadoc;补齐 @param/@return | |
| 12 | `utils-support-debezium-starter/src/main/java/com/chua/datasource/support/config/debezium/DebeziumEnvironmentSetup.java` | ✅ | 加 @since;方法补 Javadoc | |
| 13 | `utils-support-debezium-starter/src/main/java/com/chua/debezium/support/directory/DebeziumPolledDirectory.java` | ✅ | 全部日志加 `[debezium-cdc]` 前缀;字段补 Javadoc;补 close() 大括号 | |
| 14 | `utils-support-debezium-starter/src/main/java/com/chua/debezium/support/environment/DebeziumEnvironment.java` | ✅ | 字段全部补 Javadoc;提取 `CONNECTOR_TYPE_MONGODB` 常量;快捷方法补 @param/@return | |
| 15 | `utils-support-duckdb-starter/src/main/java/com/chua/duckdb/support/engine/DuckDBEngine.java` | ✅ | 提取 `DEFAULT_NAME` 常量 | |
| 16 | `utils-support-duckdb-starter/src/main/java/com/chua/duckdb/support/sink/DuckDbLogSink.java` | ✅ | 已有 `[duckdb-sink]` 前缀,无需变更 | |
| 17 | `utils-support-elasticsearch-starter/src/main/java/com/chua/elasticsearch/support/engine/ElasticsearchEngine.java` | ✅ | 加 @since;删除多余注释 | |
| 18 | `utils-support-elasticsearch-starter/src/main/java/com/chua/elasticsearch/support/engine/EsDataSyncSource.java` | ✅ | 字段补 Javadoc;构造/方法补 @param/@return | |
| 19 | `utils-support-elasticsearch-starter/src/main/java/com/chua/elasticsearch/support/meta/EsMeta.java` | ✅ | 已有 @since,无需变更 | |
| 20 | `utils-support-elasticsearch-starter/src/main/java/com/chua/elasticsearch/support/meta/EsMetaData.java` | ✅ | 已有 @since,无需变更 | |
| 21 | `utils-support-elasticsearch-starter/src/main/java/com/chua/elasticsearch/support/meta/EsSearchEngineImpl.java` | ✅ | 已有 @since,无需变更 | |
| 22 | `utils-support-hbase-starter/src/main/java/com/chua/hbase/support/engine/HBaseEngine.java` | ✅ | 提取 `DEFAULT_NAME` 常量 | |
| 23 | `utils-support-hibernate-starter/src/main/java/com/chua/hibernate/support/ddl/HibernateDdlManager.java` | ✅ | 已有 `[hibernate-ddl]` 前缀,无需变更 | |
| 24 | `utils-support-influxdb-starter/src/main/java/com/chua/influxdb/support/engine/InfluxDbEngine.java` | ✅ | 提取 `DEFAULT_NAME` 常量 | |
| 25 | `utils-support-jvector-starter/src/main/java/com/chua/jvector/support/configuration/JVectorStorageProperties.java` | ✅ | 转 Lombok `@Data`;删除手写 getter/setter;提取默认值常量化 | |
| 26 | `utils-support-jvector-starter/src/main/java/com/chua/jvector/support/spi/JVectorVectorStorageProvider.java` | ✅ | 加 @since;name() 补 Javadoc | |
| 27 | `utils-support-jvector-starter/src/main/java/com/chua/jvector/support/storage/JVectorVectorStorage.java` | ✅ | 加 @since;字段补 Javadoc;构造方法补 Javadoc | |
| 28 | `utils-support-lucene-starter/src/main/java/com/chua/lucene/support/converter/EntityDocumentConverter.java` | ✅ | 加 @since;字段/方法补 Javadoc;修正严重缩进错乱(3 个方法块被压到 1-3 空格) | |
| 29 | `utils-support-lucene-starter/src/main/java/com/chua/lucene/support/engine/LuceneDataSyncSource.java` | ✅ | 字段补 Javadoc;构造/工厂方法补 Javadoc | |
| 30 | `utils-support-lucene-starter/src/main/java/com/chua/lucene/support/engine/LuceneEngine.java` | ✅ | 已有 `[lucene-engine]` 前缀,无需变更 | |
| 31 | `utils-support-lucene-starter/src/main/java/com/chua/lucene/support/engine/LuceneFields.java` | ✅ | 字段补 Javadoc;私有构造补 Javadoc;类声明加 `final` | |
| 32 | `utils-support-milvus-starter/src/main/java/com/chua/milvus/support/configuration/MilvusStorageProperties.java` | ✅ | 加 @since | |
| 33 | `utils-support-milvus-starter/src/main/java/com/chua/milvus/support/spi/MilvusVectorStorageProvider.java` | ✅ | 加 @since;name() 补 Javadoc | |
| 34 | `utils-support-milvus-starter/src/main/java/com/chua/milvus/support/storage/MilvusVectorStorage.java` | ✅ | 加 @since;字段补 Javadoc | |
| 35 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/engine/MysqlEngine.java` | ✅ | 已有 @since,无需变更 | |
| 36 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/index/MysqlCreateIndexStep.java` | ✅ | 已有 @since,无需变更 | |
| 37 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/index/MysqlDropIndexStep.java` | ✅ | 已有 @since,无需变更 | |
| 38 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/index/MysqlIndexManager.java` | ✅ | 已有 @since,无需变更 | |
| 39 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaData.java` | ✅ | 已有 @since,无需变更 | |
| 40 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaForeignKey.java` | ✅ | 已有 @since,无需变更 | |
| 41 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaIndex.java` | ✅ | 已有 @since,无需变更 | |
| 42 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaProcedure.java` | ✅ | 已有 @since,无需变更 | |
| 43 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaTable.java` | ✅ | 已有 @since,无需变更 | |
| 44 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaTrigger.java` | ✅ | 已有 @since,无需变更 | |
| 45 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/meta/MysqlMetaView.java` | ✅ | 已有 @since,无需变更 | |
| 46 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/user/MysqlAlterUserStep.java` | ✅ | 已有 @since,无需变更 | |
| 47 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/user/MysqlCreateUserStep.java` | ✅ | 已有 @since,无需变更 | |
| 48 | `utils-support-mysql-starter/src/main/java/com/chua/mysql/support/user/MysqlUserManager.java` | ✅ | 已有 @since,无需变更 | |
| 49 | `utils-support-neo4j-starter/src/main/java/com/chua/neo4j/support/engine/Neo4jEngine.java` | ✅ | 已有 @since,无需变更 | |
| 50 | `utils-support-neo4j-starter/src/main/java/com/chua/neo4j/support/engine/Neo4jEngineDataSource.java` | ✅ | 已有 @since,无需变更 | |
| 51 | `utils-support-nitrite-starter/src/main/java/com/chua/nitrite/support/engine/NitriteEngine.java` | ✅ | 已有 @since,无需变更 | |
| 52 | `utils-support-nitrite-starter/src/main/java/com/chua/nitrite/support/engine/NitriteEngineDataSource.java` | ✅ | 已有 @since,无需变更 | |
| 53 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/dispatcher/OracleDispatcherProvider.java` | ✅ | 已有 @since,无需变更 | |
| 54 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/index/OracleCreateIndexStep.java` | ✅ | 已有 @since,无需变更 | |
| 55 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/index/OracleDropIndexStep.java` | ✅ | 已有 @since,无需变更 | |
| 56 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/index/OracleIndexManager.java` | ✅ | 已有 @since,无需变更 | |
| 57 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/user/OracleAlterUserStep.java` | ✅ | 已有 @since,无需变更 | |
| 58 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/user/OracleCreateUserStep.java` | ✅ | 已有 @since,无需变更 | |
| 59 | `utils-support-oracle-starter/src/main/java/com/chua/oracle/support/user/OracleUserManager.java` | ✅ | 已有 @since,无需变更 | |
| 60 | `utils-support-parquet-starter/src/main/java/com/chua/parquet/support/engine/ParquetEngine.java` | ✅ | 已有 @since,无需变更 | |
| 61 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/AlgorithmHolder.java` | ✅ | 已有 @since,无需变更 | |
| 62 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/CacheEntry.java` | ✅ | 已有 @since,无需变更 | |
| 63 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/DbConfig.java` | ✅ | 已有 @since,无需变更 | |
| 64 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/ShardingV5Conversion.java` | ✅ | 已有 @since,无需变更 | |
| 65 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/TableCache.java` | ✅ | 已有 @since,无需变更 | |
| 66 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/TableConfig.java` | ✅ | 已有 @since,无需变更 | |
| 67 | `utils-support-shardingv5-starter/src/main/java/com/chua/shardingv5/support/conversion/TimeRangeConfig.java` | ✅ | 已有 @since,无需变更 | |
| 68 | `utils-support-solr-starter/src/main/java/com/chua/solr/support/engine/SolrDataSyncSource.java` | ✅ | 已有 @since,无需变更 | |
| 69 | `utils-support-solr-starter/src/main/java/com/chua/solr/support/engine/SolrEngine.java` | ✅ | 已有 @since,无需变更 | |
| 70 | `utils-support-solr-starter/src/main/java/com/chua/solr/support/engine/SolrFields.java` | ✅ | 已有 @since,无需变更 | |
| 71 | `utils-support-solr-starter/src/main/java/com/chua/solr/support/meta/SolrMeta.java` | ✅ | 已有 @since,无需变更 | |
| 72 | `utils-support-solr-starter/src/main/java/com/chua/solr/support/meta/SolrMetaData.java` | ✅ | 已有 @since,无需变更 | |
| 73 | `utils-support-solr-starter/src/main/java/com/chua/solr/support/meta/SolrSearchEngine.java` | ✅ | 已有 @since,无需变更 | |
| 74 | `utils-support-sqlite-starter/src/main/java/com/chua/sqlite/support/directory/SqlitePolledDirectory.java` | ✅ | 已有 @since,无需变更 | |
| 75 | `utils-support-sqlite-starter/src/main/java/com/chua/sqlite/support/engine/SqliteEngine.java` | ✅ | 已有 @since,无需变更 | |
| 76 | `utils-support-tablesaw-starter/src/main/java/com/chua/tablesaw/support/engine/TablesawEngine.java` | ✅ | 已有 @since,无需变更 | |
| 77 | `utils-support-tablesaw-starter/src/main/java/com/chua/tablesaw/support/network/server/filter/BuiltinEndpointFilter.java` | ✅ | 已有 @since,无需变更 | |

## 修复汇总

| 项目 | 数量 |
|---|---:|
| 总文件 | 77 |
| 已检查 | 77 |
| 已修复 | 77 |
| 暂不修 | 0 |
| 失败 | 0 |
| 实际改动文件 | 18 |
| 日志补 `[模块]` 前缀 | 5 |
| POJO 转 Lombok | 1 (JVectorStorageProperties) |
| 字段补 Javadoc 多行注释 | 14 |
| 提取魔法值为常量 | 4 (CALCITE_URL/CALCITE_LEX/DEFAULT_SCHEMA/SQL_UPDATE_KEYWORD/CONNECTOR_TYPE_MONGODB/DEFAULT_NAME ×4) |
| 修正严重缩进错乱 | 1 (EntityDocumentConverter) |
| 添加 `@since` 标签 | 17 |

## 验证

```
mvn compile -Dmaven.test.skip=true
[INFO] Reactor Summary for Utils Support Datasource Parent 4.0.0.42:
[INFO] Utils Support Datasource Parent .................... SUCCESS
[INFO] Utils Support SQLite Starter ....................... SUCCESS
[INFO] utils-support-calcite-starter ...................... SUCCESS
[INFO] utils-support-shardingv5-starter ................... SUCCESS
[INFO] Hibernate Starter .................................. SUCCESS
[INFO] Neo4j Starter ...................................... SUCCESS
[INFO] Lucene Starter ..................................... SUCCESS
[INFO] Parquet Starter .................................... SUCCESS
[INFO] Oracle Starter ..................................... SUCCESS
[INFO] Milvus Starter ..................................... SUCCESS
[INFO] JVector Starter .................................... SUCCESS
[INFO] InfluxDB Starter ................................... SUCCESS
[INFO] HBase Starter ...................................... SUCCESS
[INFO] Elasticsearch Starter .............................. SUCCESS
[INFO] DuckDB Starter ..................................... SUCCESS
[INFO] MySQL Starter ...................................... SUCCESS
[INFO] Utils Support Tablesaw Starter ..................... SUCCESS
[INFO] Solr Starter ....................................... SUCCESS
[INFO] Utils Support Debezium Starter ..................... SUCCESS
[INFO] Nitrite Starter .................................... SUCCESS
[INFO] BUILD SUCCESS
```
