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
     * RabbitMQ Java Client
     */
    RABBITMQ_CLIENT("RabbitMQ Client"),

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