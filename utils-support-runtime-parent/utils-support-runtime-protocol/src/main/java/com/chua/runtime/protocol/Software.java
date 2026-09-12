package com.chua.runtime.protocol;

/**
 * 软件栈枚举 — 区分同一协议的不同实现。
 *
 * <p>例如 Redis 可以是 Jedis / Lettuce / Redisson；
 * ZooKeeper 可以是 Apache Curator / 原生客户端；
 * HTTP 服务端可以是 Tomcat / Jetty / Netty。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Software {

    /**
     * JDK 内置 HTTP 服务端（com.sun.net.httpserver）
     */
    JDK_HTTP_SERVER("JDK HttpServer"),

    /**
      * JDK 内置 HTTP 客户端（httpurlconnection）
     */
    JDK_HTTP_CLIENT("JDK HttpURLConnection"),

    /**
     * Apache Tomcat
     */
    TOMCAT("Apache Tomcat"),

    /**
     * Eclipse Jetty
     */
    JETTY("Eclipse Jetty"),

    /**
     * Netty（服务端 / 客户端均可）
     */
    NETTY("Netty"),

    /**
     * Undertow
     */
    UNDERTOW("Undertow"),

    /**
      * Apache HTTP客户端
     */
    APACHE_HTTPCLIENT("Apache HttpClient"),

    /**
     * OkHttp
     */
    OKHTTP("OkHttp"),

    /**
     * Apache ZooKeeper 原生客户端
     */
    ZOOKEEPER_NATIVE("ZooKeeper Native"),

    /**
     * Apache Curator
     */
    CURATOR("Apache Curator"),

    /**
     * Jedis（Redis Java 客户端）
     */
    JEDIS("Jedis"),

    /**
     * Lettuce（Redis Java 客户端）
     */
    LETTUCE("Lettuce"),

    /**
     * Redisson（Redis Java 客户端）
     */
    REDISSON("Redisson"),

    /**
     * MySQL JDBC 驱动
     */
    MYSQL_DRIVER("MySQL JDBC"),

    /**
     * PostgreSQL JDBC 驱动
     */
    POSTGRESQL_DRIVER("PostgreSQL JDBC"),

    /**
     * Apache Kafka Producer
     */
    KAFKA_PRODUCER("Kafka Producer"),

    /**
     * Apache Kafka Consumer
     */
    KAFKA_CONSUMER("Kafka Consumer"),

    /**
     * Apache RocketMQ Producer
     */
    ROCKETMQ_PRODUCER("RocketMQ Producer"),

    /**
     * Apache RocketMQ Consumer
     */
    ROCKETMQ_CONSUMER("RocketMQ Consumer"),

    /**
     * Apache Dubbo RPC
     */
    DUBBO("Dubbo"),

    /**
     * Alibaba Nacos
     */
    NACOS("Nacos"),

    /**
     * Netflix Eureka
     */
    EUREKA("Eureka"),

    /**
      * hashicorp Consul
     */
    CONSUL("Consul"),

    /**
      * Spring Web客户端（响应式 HTTP 客户端）
     */
    WEB_CLIENT("Spring WebClient"),

    /**
     * Oracle JDBC 驱动
     */
    ORACLE_DRIVER("Oracle JDBC"),

    /**
      * Microsoft SQL 服务端 JDBC 驱动
     */
    SQLSERVER_DRIVER("SQL Server JDBC"),

    /**
     * IBM DB2 JDBC 驱动
     */
    DB2_DRIVER("DB2 JDBC"),

    /**
      * click房子 JDBC 驱动
     */
    CLICKHOUSE_DRIVER("ClickHouse JDBC"),

    /**
     * 达梦 JDBC 驱动
     */
    DAMENG_DRIVER("达梦 JDBC"),

    /**
      * kingbasees JDBC 驱动
     */
    KINGBASE_DRIVER("金仓 JDBC"),

    /**
     * Cassandra Java Driver
     */
    CASSANDRA_DRIVER("Cassandra Driver"),

    /**
      * Apache HBase 客户端
     */
    HBASE_CLIENT("HBase Client"),

    /**
      * Apache Pulsar 客户端
     */
    PULSAR("Pulsar"),

    /**
     * Apache Thrift RPC
     */
    THRIFT("Thrift"),

    /**
      * Apache 分库分表sphere
     */
    SHARDING_SPHERE("ShardingSphere"),

    /**
     * Neo4j Java Driver
     */
    NEO4J_DRIVER("Neo4j Driver"),

    /**
      * Hazelcast 客户端
     */
    HAZELCAST("Hazelcast"),

    /**
      * Apache Solr 客户端
     */
    SOLR("Solr"),

    /**
      * JMS 客户端 (ActiveMQ/Artemis)
     */
    JMS_CLIENT("JMS Client"),

    /**
      * Couchbase 客户端
     */
    COUCHBASE("Couchbase"),

    /**
      * Etcd 客户端
     */
    ETCD("Etcd"),

    /**
      * Apache Ignite 客户端
     */
    IGNITE("Ignite"),

    /**
      * r套接字 客户端
     */
    RSOCKET("RSocket"),

    /**
      * NATS 客户端
     */
    NATS("NATS"),

    /**
      * 图计算ql Java
     */
    GRAPHQL_JAVA("GraphQL Java"),

    /**
     * Spring Cloud Gateway
     */
    SPRING_CLOUD_GATEWAY("Spring Cloud Gateway"),

    /**
      * 异步http客户端
     */
    ASYNC_HTTP_CLIENT("AsyncHttpClient"),

    /**
      * influxdb Java 客户端
     */
    INFLUXDB_CLIENT("InfluxDB Client"),

    /**
      * RabbitMQ Java 客户端
     */
    RABBITMQ_CLIENT("RabbitMQ Client"),

    /**
     * Eclipse Paho MQTT 客户端
     */
    PAHO_MQTT("Eclipse Paho MQTT"),

    /**
      * 打开Feign（Spring Cloud 声明式 HTTP 客户端）
     */
    FEIGN("OpenFeign"),

    /**
     * Spring RestTemplate
     */
    REST_TEMPLATE("Spring RestTemplate"),

    /**
     * MongoDB Java Driver
     */
    MONGODB_DRIVER("MongoDB Driver"),

    /**
     * gRPC Stub
     */
    GRPC("gRPC"),

    /**
      * Memcached Java 客户端
     */
    MEMCACHED("Memcached"),

    /**
      * Elasticsearch 客户端
     */
    ELASTICSEARCH("Elasticsearch"),

/**
     * Spring MVC
     */
    SPRING_MVC("Spring MVC"),

    /**
     * MyBatis
     */
    MYBATIS("MyBatis"),

    /**
     * WebSocket
     */
    WEBSOCKET("WebSocket"),

    /**
     * Hibernate ORM
     */
    HIBERNATE("Hibernate"),

    /**
      * Spring Cloud 流
     */
    SPRING_CLOUD_STREAM("Spring Cloud Stream"),

    /**
     * Vert.x
     */
    VERTX("Vert.x"),

    /**
      * Play 框架
     */
    PLAY("Play Framework"),

    /**
      * Spring 数据 JPA
     */
    SPRING_DATA_JPA("Spring Data JPA"),

    /**
      * 响应式 流
     */
    REACTIVE_STREAMS("Reactive Streams"),

    /**
      * XXL-作业（分布式任务调度）
     */
    XXL_JOB("XXL-Job"),

    /**
     * Alibaba Sentinel（限流熔断）
     */
    SENTINEL("Sentinel"),

    /**
     * Seata（分布式事务）
     */
    SEATA("Seata"),

    /**
      * Apache CXF / JAX-WS web服务
     */
    CXF("Apache CXF"),

    /**
      * 打开搜索
     */
    OPENSEARCH("OpenSearch"),

    /**
     * Apache Hadoop HDFS
     */
    HDFS("Apache HDFS"),

    /**
     * Apache Spark
     */
    SPARK("Apache Spark"),

    /**
     * Apache Flink
     */
    FLINK("Apache Flink"),

    /**
     * Spring Integration
     */
    SPRING_INTEGRATION("Spring Integration"),

    /**
      * Kubernetes 客户端
     */
    KUBERNETES("Kubernetes"),

    /**
     * AWS SDK
     */
    AWS_SDK("AWS SDK"),

    /**
     * H2 JDBC Driver
     */
    H2_DRIVER("H2 JDBC"),

    /**
      * 石英石 调度器
     */
    QUARTZ("Quartz"),

    /**
      * Spring 批量
     */
    SPRING_BATCH("Spring Batch"),

    /**
     * Java 线程（线程创建 / 任务提交）
     */
    THREAD("Java Thread"),

    /**
     * UNKNOWN
     */
    UNKNOWN("Unknown");

    /**
      * display 名称
     */
    private final String displayName;

    Software(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取软件显示名。
     *
     * @return 显示名
     */
    public String displayName() {
        return displayName;
    }
}