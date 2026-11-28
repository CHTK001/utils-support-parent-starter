# utils-support-middleware-parent

消息中间件、注册/配置中心、可观测客户端和容器适配的 Maven 聚合模块。版本 `4.0.0.51`、JDK 25。父 POM 不安装或启动中间件服务，应用按实际协议添加具体 jar。

## 模块目录

| 模块 README | 主要能力 |
|---|---|
| [kafka](utils-support-kafka-starter/README.md) | Kafka 分发与数据同步 |
| [nats](utils-support-nats-starter/README.md) | Pub/Sub、JetStream、KV、Object Store |
| [rabbitmq](utils-support-rabbitmq-starter/README.md) | RabbitMQ 消息分发 |
| [mqtt](utils-support-mqtt-starter/README.md) | Paho MQTT 分发器及订阅 |
| [chronicle](utils-support-chronicle-starter/README.md) | Chronicle Queue 本地持久化队列 |
| [redis](utils-support-redis-starter/README.md) | Redis 缓存、锁、服务发现等 SPI |
| [mongodb](utils-support-mongodb-starter/README.md) | 官方同步驱动、连接探测、库/集合浏览与只读查询 |
| [nacos](utils-support-nacos-starter/README.md) | Nacos 注册与配置 |
| [zookeeper](utils-support-zookeeper-starter/README.md) | ZooKeeper 服务发现 |
| [apollo](utils-support-apollo-starter/README.md) | Apollo 配置接入 |
| [opentelemetry](utils-support-opentelemetry-starter/README.md) | OpenTelemetry 客户端、Engine 与装配 |
| [prometheus](utils-support-prometheus-starter/README.md) | PromQL 客户端、Engine 与装配 |
| [skywalking](utils-support-skywalking-starter/README.md) | OAP GraphQL、拓扑/链路/指标与 OtnlEngine |
| [minio](utils-support-minio-starter/README.md) | MinIO/S3 FileStorage |
| [docker](utils-support-docker-starter/README.md) | docker-java 客户端 |
| [guacamole](utils-support-guacamole-starter/README.md) | guacd 远控参数及会话 URL |
| [sentinel](utils-support-sentinel-starter/README.md) | Sentinel 限流适配 |
| [resilience4j](utils-support-resilience4j-starter/README.md) | 熔断、限流及重试适配 |
| [tomcat](utils-support-tomcat-starter/README.md) | 嵌入式 Tomcat 容器 |
| [undertow](utils-support-undertow-starter/README.md) | 嵌入式 Undertow 容器 |

## 安装与选择

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-redis-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

连接地址、认证、topic/namespace、TLS 等按具体 README 配置。消息分发不等于业务事务；服务发现的 TTL/注销语义也依实现而异。Prometheus/OpenTelemetry/SkyWalking 当前含 Spring 装配，接入前需评估宿主环境与传递依赖。

仓库根构建：`mvn -pl utils-support-middleware-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
