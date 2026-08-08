# utils-support-middleware-parent 修复跟踪表

**文件总数:** 60
**规范:** ch-java-coding-style(强制) + P3C(强制)
**状态:** ⬜ 未检查 / � 检查中 / ✅ 已修复 / ⚠️ 暂不修 / ❌ 失败

## 文件清单与修复状态

| # | 相对路径 | 状态 | 主要修复项 | 备注 |
|---:|---|---|---|---|
| 1 | `utils-support-apollo-starter/src/main/java/com/chua/apollo/support/client/ApolloClient.java` | ⬜ | | |
| 2 | `utils-support-apollo-starter/src/main/java/com/chua/apollo/support/config/ApolloConfigCenter.java` | ⬜ | | |
| 3 | `utils-support-apollo-starter/src/main/java/com/chua/apollo/support/discovery/ApolloServiceDiscovery.java` | ⬜ | | |
| 4 | `utils-support-chronicle-starter/src/main/java/com/chua/chronicle/support/collector/ChronicleActiveCollector.java` | ⬜ | | |
| 5 | `utils-support-chronicle-starter/src/main/java/com/chua/chronicle/support/dispatcher/ChronicleDispatcherProvider.java` | ⬜ | | |
| 6 | `utils-support-chronicle-starter/src/main/java/com/chua/chronicle/support/kv/ChronicleMapKv.java` | ⬜ | | |
| 7 | `utils-support-chronicle-starter/src/main/java/com/chua/chronicle/support/lock/ChronicleLockProvider.java` | ⬜ | | |
| 8 | `utils-support-chronicle-starter/src/test/java/com/chua/chronicle/support/dispatcher/ChronicleDispatcherProviderTest.java` | ⬜ | | |
| 9 | `utils-support-docker-starter/src/main/java/com/chua/docker/support/client/DockerClient.java` | ⬜ | | |
| 10 | `utils-support-kafka-starter/src/main/java/com/chua/kafka/support/client/KafkaClient.java` | ⬜ | | |
| 11 | `utils-support-kafka-starter/src/main/java/com/chua/kafka/support/dispatcher/KafkaDispatcherProvider.java` | ⬜ | | |
| 12 | `utils-support-minio-starter/src/main/java/com/chua/minio/support/storage/MinioFileStorage.java` | ⬜ | | |
| 13 | `utils-support-mqtt-starter/src/main/java/com/chua/mqtt/support/client/MqttClientWrapper.java` | ⬜ | | |
| 14 | `utils-support-mqtt-starter/src/main/java/com/chua/mqtt/support/dispatcher/MqttDispatcherProvider.java` | ⬜ | | |
| 15 | `utils-support-mqtt-starter/src/main/java/com/chua/mqtt/support/parser/MqttListenerParser.java` | ⬜ | | |
| 16 | `utils-support-mqtt-starter/src/main/java/com/chua/mqtt/support/server/MqttServer.java` | ⬜ | | |
| 17 | `utils-support-nacos-starter/src/main/java/com/chua/nacos/support/client/NacosClient.java` | ⬜ | | |
| 18 | `utils-support-nacos-starter/src/main/java/com/chua/nacos/support/config/NacosConfigCenter.java` | ⬜ | | |
| 19 | `utils-support-nacos-starter/src/main/java/com/chua/nacos/support/discovery/NacosServiceDiscovery.java` | ⬜ | | |
| 20 | `utils-support-nats-starter/src/main/java/com/chua/nats/support/client/NatsClient.java` | ⬜ | | |
| 21 | `utils-support-nats-starter/src/main/java/com/chua/nats/support/client/package-info.java` | ⬜ | | |
| 22 | `utils-support-nats-starter/src/main/java/com/chua/nats/support/dispatcher/NatsDispatcherProvider.java` | ⬜ | | |
| 23 | `utils-support-nats-starter/src/main/java/com/chua/nats/support/dispatcher/package-info.java` | ⬜ | | |
| 24 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/client/PrometheusClient.java` | ⬜ | | |
| 25 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/config/PrometheusAutoConfiguration.java` | ⬜ | | |
| 26 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/config/PrometheusProperties.java` | ⬜ | | |
| 27 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/datasource/PrometheusDataSource.java` | ⬜ | | |
| 28 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/engine/PrometheusEngine.java` | ⬜ | | |
| 29 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/example/PrometheusExample.java` | ⬜ | | |
| 30 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/model/PrometheusAlert.java` | ⬜ | | |
| 31 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/model/PrometheusMetric.java` | ⬜ | | |
| 32 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/model/PrometheusRule.java` | ⬜ | | |
| 33 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/model/PrometheusTarget.java` | ⬜ | | |
| 34 | `utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/model/QueryResult.java` | ⬜ | | |
| 35 | `utils-support-rabbitmq-starter/src/main/java/com/chua/rabbitmq/support/client/RabbitmqClient.java` | ⬜ | | |
| 36 | `utils-support-rabbitmq-starter/src/main/java/com/chua/rabbitmq/support/dispatcher/RabbitmqDispatcherProvider.java` | ⬜ | | |
| 37 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/client/RedisClient.java` | ⬜ | | |
| 38 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/config/RedisConfigCenter.java` | ⬜ | | |
| 39 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/datasource/RedisDataTable.java` | ⬜ | | |
| 40 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/deduplicate/RedisDeduplicator.java` | ⬜ | | |
| 41 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/discovery/RedisServiceDiscovery.java` | ⬜ | | |
| 42 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/dispatcher/RedissonDispatcherProvider.java` | ⬜ | | |
| 43 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/engine/RediSearchEngine.java` | ⬜ | | |
| 44 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/engine/RediSearchQueryConverter.java` | ⬜ | | |
| 45 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/engine/RedisEngine.java` | ⬜ | | |
| 46 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/engine/SimpleRedisDataSource.java` | ⬜ | | |
| 47 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/lock/RedissonLockProvider.java` | ⬜ | | |
| 48 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/meta/RedisSearchEngineImpl.java` | ⬜ | | |
| 49 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/meta/RedisSearchMeta.java` | ⬜ | | |
| 50 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/meta/RedisSearchMetaData.java` | ⬜ | | |
| 51 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/rate/RedisRateLimiterProvider.java` | ⬜ | | |
| 52 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/server/EmbeddedRedisServer.java` | ⬜ | | |
| 53 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/server/RedisServer.java` | ⬜ | | |
| 54 | `utils-support-redis-starter/src/main/java/com/chua/redis/support/sink/RedisSearchSink.java` | ⬜ | | |
| 55 | `utils-support-sentinel-starter/src/main/java/com/chua/sentinel/support/rate/SentinelRateLimiterProvider.java` | ⬜ | | |
| 56 | `utils-support-tomcat-starter/src/main/java/com/chua/tomcat/support/container/TomcatWebContainer.java` | ⬜ | | |
| 57 | `utils-support-undertow-starter/src/main/java/com/chua/undertow/support/container/UndertowWebContainer.java` | ⬜ | | |
| 58 | `utils-support-zookeeper-starter/src/main/java/com/chua/zookeeper/support/client/ZookeeperClient.java` | ⬜ | | |
| 59 | `utils-support-zookeeper-starter/src/main/java/com/chua/zookeeper/support/config/ZookeeperConfigCenter.java` | ⬜ | | |
| 60 | `utils-support-zookeeper-starter/src/main/java/com/chua/zookeeper/support/discovery/ZookeeperServiceDiscovery.java` | ⬜ | | |

## 修复汇总

| 项目 | 数量 |
|---|---:|
| 总文件 | 60 |
| 已检查 | 0 |
| 已修复 | 0 |
| 暂不修 | 0 |
| 失败 | 0 |
