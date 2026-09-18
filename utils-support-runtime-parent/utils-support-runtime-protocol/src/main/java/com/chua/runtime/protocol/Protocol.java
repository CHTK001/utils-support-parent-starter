package com.chua.runtime.protocol;

/**
* 网络协议枚举 — 用于标识传输层 / 应用层协议。
*
* <p>Socket 层通过端口号映射推断协议；应用层 Handler
* （如 Jedis/ZK/HTTP客户端）显式声明协议。</p>
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
    * Netflix Eureka（注册中心，HTTP 协议）
     */
    EUREKA("Eureka", 8761, false),

    /**
    * hashicorp Consul（注册/配置中心，HTTP 协议）
     */
    CONSUL("Consul", 8500, false),

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
    * MQTT（消息队列遥测传输）
     */
    MQTT("MQTT", 1883, false),

    /**
    * MongoDB
     */
    MONGODB("MongoDB", 27017, false),

    /**
    * Oracle Database
     */
    ORACLE("Oracle", 1521, false),

    /**
    * Microsoft SQL 服务端
     */
    SQLSERVER("SQL Server", 1433, false),

    /**
    * IBM DB2
     */
    DB2("DB2", 50000, false),

    /**
    * click房子（OLAP 列式数据库）
     */
    CLICKHOUSE("ClickHouse", 8123, false),

    /**
    * 达梦数据库（DM，国产关系型数据库）
     */
    DAMENG("达梦", 5236, false),

    /**
    * kingbasees（金仓，国产关系型数据库）
     */
    KINGBASE("金仓", 54321, false),

    /**
    * Apache Cassandra
     */
    CASSANDRA("Cassandra", 9042, false),

    /**
    * Apache HBase
     */
    HBASE("HBase", 16020, false),

    /**
    * Apache Pulsar
     */
    PULSAR("Pulsar", 6650, false),

    /**
    * Apache Thrift RPC
     */
    THRIFT("Thrift", 9090, false),

    /**
    * Apache 分库分表sphere（分库分表）
     */
    SHARDING_SPHERE("ShardingSphere", 0, false),

    /**
    * Neo4j 图数据库
     */
    NEO4J("Neo4j", 7687, false),

    /**
    * Hazelcast 分布式缓存
     */
    HAZELCAST("Hazelcast", 5701, false),

    /**
    * Apache Solr 搜索引擎
     */
    SOLR("Solr", 8983, false),

    /**
    * JMS (ActiveMQ/Artemis etc.)
     */
    JMS("JMS", 61616, false),

    /**
    * Couchbase
     */
    COUCHBASE("Couchbase", 8091, false),

    /**
    * Etcd
     */
    ETCD("Etcd", 2379, false),

    /**
    * Apache Ignite
     */
    IGNITE("Ignite", 10800, false),

/**
* r套接字
     */
    RSOCKET("RSocket", 7000, false),

    /**
    * NATS
     */
    NATS("NATS", 4222, false),

    /**
    * influxdb
     */
    INFLUXDB("InfluxDB", 8086, false),

    /**
    * H2 Database（嵌入式，TCP 服务默认 8082）
     */
    H2("H2", 8082, false),

    /**
    * 进程内调用（无网络）
     */
    INTERNAL("Internal", 0, false),

/**
* SQL (generic database 协议)
     */
    SQL("SQL", 0, false),

    /**
    * WebSocket
     */
    WEBSOCKET("WebSocket", 80, false),

    /**
    * 通用消息（Spring Cloud 流 等跨协议消息通道）
     */
    MESSAGE("Message", 0, false),

    /**
    * UNKNOWN
    * @param 0 方法入参 0
    * @param false 方法入参 false
     */
    UNKNOWN("Unknown", 0, false);

    /**
    * display 名称
     */
    private final String displayName;
    /**
    * 默认 端口
     */
    private final int defaultPort;
    /**
    * 文本
     */
    private final boolean text;

    /**
     * 构造方法，创建 Protocol 实例。
     *
     * @param displayName display名称，不允许为 null
     * @param defaultPort default端口，不允许为 null
     * @param text 文本，不允许为 null
     */
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
    * 根据端口号推断协议（套接字 层 处理器 用）。
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
            case 8761 -> EUREKA;
            case 8500 -> CONSUL;
            case 9200, 9300 -> ELASTICSEARCH;
            case 11211 -> MEMCACHED;
            case 5672, 15672 -> RABBITMQ;
            case 1883, 8883 -> MQTT;
            case 27017 -> MONGODB;
            case 1521 -> ORACLE;
            case 1433 -> SQLSERVER;
            case 50000 -> DB2;
            case 8123, 9000 -> CLICKHOUSE;
            case 5236 -> DAMENG;
            case 54321 -> KINGBASE;
            case 9042 -> CASSANDRA;
            case 16020 -> HBASE;
            case 6650 -> PULSAR;
            case 9090 -> THRIFT;
            case 7687 -> NEO4J;
            case 5701 -> HAZELCAST;
            case 8983 -> SOLR;
            case 61616 -> JMS;
            case 8091 -> COUCHBASE;
            case 2379 -> ETCD;
            case 10800 -> IGNITE;
            case 7000 -> RSOCKET;
            case 4222 -> NATS;
            case 8086 -> INFLUXDB;
            case 8082 -> H2;
            default -> UNKNOWN;
        };
    }
}