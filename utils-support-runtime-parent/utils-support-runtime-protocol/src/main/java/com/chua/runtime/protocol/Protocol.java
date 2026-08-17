package com.chua.runtime.protocol;

/**
 * 网络协议枚举 — 用于标识传输层 / 应用层协议。
 *
 * <p>Socket 层通过端口号映射推断协议；应用层 Handler
 * （如 Jedis/ZK/HttpClient）显式声明协议。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Protocol {

    /**
     * 传输控制协议
     */
    TCP("TCP", 0, true),

    /**
     * 用户数据报协议
     */
    UDP("UDP", 0, false),

    /**
     * HTTP（明文）
     */
    HTTP("HTTP", 80, true),

    /**
     * HTTPS（TLS）
     */
    HTTPS("HTTPS", 443, true),

    /**
     * gRPC（基于 HTTP/2）
     */
    GRPC("gRPC", 0, true),

    /**
     * Apache ZooKeeper
     */
    ZOOKEEPER("ZooKeeper", 2181, false),

    /**
     * Redis
     */
    REDIS("Redis", 6379, false),

    /**
     * MySQL
     */
    MYSQL("MySQL", 3306, false),

    /**
     * PostgreSQL
     */
    POSTGRESQL("PostgreSQL", 5432, false),

    /**
     * Apache Kafka
     */
    KAFKA("Kafka", 9092, false),

    /**
     * Apache RocketMQ
     */
    ROCKETMQ("RocketMQ", 9876, false),

    /**
     * Dubbo RPC（基于 TCP 的自定义协议）
     */
    DUBBO("Dubbo", 20880, false),

    /**
     * Alibaba Nacos（注册/配置中心）
     */
    NACOS("Nacos", 8848, false),

    /**
     * Elasticsearch
     */
    ELASTICSEARCH("Elasticsearch", 9200, false),

    /**
     * Memcached
     */
    MEMCACHED("Memcached", 11211, false),

    /**
     * RabbitMQ
     */
    RABBITMQ("RabbitMQ", 5672, false),

    /**
     * MongoDB
     */
    MONGODB("MongoDB", 27017, false),

    /**
     * Oracle Database
     */
    ORACLE("Oracle", 1521, false),

    /**
     * Microsoft SQL Server
     */
    SQLSERVER("SQL Server", 1433, false),

    /**
     * 进程内调用（无网络）
     */
    INTERNAL("Internal", 0, false),

    /**
     * 未知协议
     */
    UNKNOWN("Unknown", 0, false);

    private final String displayName;
    private final int defaultPort;
    private final boolean text;

    Protocol(String displayName, int defaultPort, boolean text) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.text = text;
    }

    /**
     * 获取协议显示名。
     *
     * @return 显示名
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 获取协议默认端口。
     *
     * @return 默认端口（无固定端口返回 0）
     */
    public int defaultPort() {
        return defaultPort;
    }

    /**
     * 是否文本协议（HTTP/1.1 是文本，gRPC/Kafka 是二进制）。
     *
     * @return 文本协议返回 true
     */
    public boolean isText() {
        return text;
    }

    /**
     * 根据端口号推断协议（Socket 层 Handler 用）。
     *
     * @param port 端口
     * @return 推断的协议
     */
    public static Protocol inferByPort(int port) {
        if (port <= 0) {
            return UNKNOWN;
        }
        return switch (port) {
            case 80, 8080, 8000, 8888 -> HTTP;
            case 443, 8443 -> HTTPS;
            case 2181, 3181, 4181 -> ZOOKEEPER;
            case 6379, 6380 -> REDIS;
            case 3306 -> MYSQL;
            case 5432 -> POSTGRESQL;
            case 9092 -> KAFKA;
            case 9876 -> ROCKETMQ;
            case 20880 -> DUBBO;
            case 8848, 9848 -> NACOS;
            case 9200, 9300 -> ELASTICSEARCH;
            case 11211 -> MEMCACHED;
            case 5672, 15672 -> RABBITMQ;
            case 27017 -> MONGODB;
            case 1521 -> ORACLE;
            case 1433 -> SQLSERVER;
            default -> UNKNOWN;
        };
    }
}