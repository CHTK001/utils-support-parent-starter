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
     * JDK 内置 HTTP 客户端（HttpURLConnection）
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
     * Apache HttpClient
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
     * HashiCorp Consul
     */
    CONSUL("Consul"),

    /**
     * Spring WebClient（响应式 HTTP 客户端）
     */
    WEB_CLIENT("Spring WebClient"),

    /**
     * Oracle JDBC 驱动
     */
    ORACLE_DRIVER("Oracle JDBC"),

    /**
     * Microsoft SQL Server JDBC 驱动
     */
    SQLSERVER_DRIVER("SQL Server JDBC"),

    /**
     * IBM DB2 JDBC 驱动
     */
    DB2_DRIVER("DB2 JDBC"),

    /**
     * ClickHouse JDBC 驱动
     */
    CLICKHOUSE_DRIVER("ClickHouse JDBC"),

    /**
     * 达梦 JDBC 驱动
     */
    DAMENG_DRIVER("达梦 JDBC"),

    /**
     * KingbaseES JDBC 驱动
     */
    KINGBASE_DRIVER("金仓 JDBC"),

    /**
     * Cassandra Java Driver
     */
    CASSANDRA_DRIVER("Cassandra Driver"),

    /**
     * InfluxDB Java Client
     */
    INFLUXDB_CLIENT("InfluxDB Client"),

    /**
     * RabbitMQ Java Client
     */
    RABBITMQ_CLIENT("RabbitMQ Client"),

    /**
     * Eclipse Paho MQTT 客户端
     */
    PAHO_MQTT("Eclipse Paho MQTT"),

    /**
     * OpenFeign（Spring Cloud 声明式 HTTP 客户端）
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
     * Memcached Java Client
     */
    MEMCACHED("Memcached"),

    /**
     * Elasticsearch Client
     */
    ELASTICSEARCH("Elasticsearch"),

    /**
     * 进程内直接调用
     */
    PROCESS("Process"),

    /**
     * 未知
     */
    UNKNOWN("Unknown");

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