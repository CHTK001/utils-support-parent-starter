package com.chua.runtime.apm.handler;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

/**
 * 软件栈检测工具 — 通过调用栈分析识别第三方库。
 *
 * <p>核心原理：从当前线程调用栈中自顶向下扫描，跳过 JDK/Agent 框架内部调用，
 * 通过包名前缀匹配识别发起连接的第三方客户端库。</p>
 *
 * <p>不依赖编译期类加载，零版本绑定，覆盖市面上主流中间件客户端。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SoftwareDetector {

    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(SoftwareDetector.class.getName());
    /**
     * 创建 softwaredetector 实例
    */
    private SoftwareDetector() {
    }

    /**
     * 包名前缀 → 软件栈映射（匹配顺序：前缀短的优先）。
     */
    private static final Map<String, Software> SOFTWARE_PATTERNS = new LinkedHashMap<>() {{
        // Redis
        put("redis.clients.jedis", Software.JEDIS);
        put("io.lettuce.core", Software.LETTUCE);
        put("org.redisson", Software.REDISSON);

        // MySQL
        put("com.mysql.cj", Software.MYSQL_DRIVER);
        put("com.mysql.jdbc", Software.MYSQL_DRIVER);

        // PostgreSQL
        put("org.postgresql", Software.POSTGRESQL_DRIVER);

        // ZooKeeper
        put("org.apache.zookeeper", Software.ZOOKEEPER_NATIVE);
        put("org.apache.curator", Software.CURATOR);

        // Kafka
        put("org.apache.kafka.clients.producer", Software.KAFKA_PRODUCER);
        put("org.apache.kafka.clients.consumer", Software.KAFKA_CONSUMER);

        // RocketMQ
        put("org.apache.rocketmq.client.producer", Software.ROCKETMQ_PRODUCER);
        put("org.apache.rocketmq.client.consumer", Software.ROCKETMQ_CONSUMER);

        // Dubbo
        put("org.apache.dubbo", Software.DUBBO);

        // Nacos
        put("com.alibaba.nacos", Software.NACOS);

        // Eureka
        put("com.netflix.discovery", Software.EUREKA);
        put("org.springframework.cloud.netflix.eureka", Software.EUREKA);

        // Consul
        put("com.ecwid.consul", Software.CONSUL);
        put("org.springframework.cloud.consul", Software.CONSUL);

        // Spring WebClient (WebFlux)
        put("org.springframework.web.reactive.function.client", Software.WEB_CLIENT);
        put("org.springframework.web.reactive", Software.WEB_CLIENT);

        // RabbitMQ
        put("com.rabbitmq", Software.RABBITMQ_CLIENT);
        put("org.springframework.amqp", Software.RABBITMQ_CLIENT);

        // MQTT (Paho)
        put("org.eclipse.paho", Software.PAHO_MQTT);

 // 打开Feign
        put("feign", Software.FEIGN);
        put("org.springframework.cloud.openfeign", Software.FEIGN);

        // Spring RestTemplate
        put("org.springframework.web.client", Software.REST_TEMPLATE);

        // MongoDB
        put("com.mongodb", Software.MONGODB_DRIVER);

        // Oracle
        put("oracle.jdbc", Software.ORACLE_DRIVER);
        put("oracle.ucp", Software.ORACLE_DRIVER);

 // SQL 服务端
        put("com.microsoft.sqlserver", Software.SQLSERVER_DRIVER);

        // gRPC
        put("io.grpc", Software.GRPC);

        // HTTP 客户端
        put("okhttp3", Software.OKHTTP);
        put("retrofit2", Software.OKHTTP);
        put("org.apache.http", Software.APACHE_HTTPCLIENT);
        put("org.springframework.http.client", Software.APACHE_HTTPCLIENT);
        put("org.springframework.web.client", Software.APACHE_HTTPCLIENT);
        put("feign", Software.APACHE_HTTPCLIENT);
        put("com.netflix.http", Software.APACHE_HTTPCLIENT);

        // HTTP 服务器
        put("org.apache.catalina", Software.TOMCAT);
        put("org.apache.tomcat", Software.TOMCAT);
        put("org.eclipse.jetty", Software.JETTY);
        put("org.springframework.boot.web.embedded.tomcat", Software.TOMCAT);
        put("org.springframework.boot.web.embedded.jetty", Software.JETTY);

        // WebFlux / Netty
        put("io.netty", Software.NETTY);
        put("org.springframework.web.reactive", Software.NETTY);
        put("org.springframework.webflux", Software.NETTY);
        put("org.springframework.boot.web.embedded.netty", Software.NETTY);

        // Undertow
        put("io.undertow", Software.UNDERTOW);
        put("org.springframework.boot.web.embedded.undertow", Software.UNDERTOW);

        // RocketMQ
        put("org.apache.rocketmq", Software.ROCKETMQ_PRODUCER);

        // Memcached
        put("net.spy.memcached", Software.MEMCACHED);

        // Elasticsearch
        put("org.elasticsearch", Software.ELASTICSEARCH);

 // influxdb
        put("org.influxdb", Software.INFLUXDB_CLIENT);

 // click房子
        put("com.clickhouse", Software.CLICKHOUSE_DRIVER);

 // tidb
        put("com.pingcap", Software.UNKNOWN);

 // oceanbase
        put("com.oceanbase", Software.UNKNOWN);

        // DM 达梦
        put("dm.jdbc", Software.DAMENG_DRIVER);

        // 金仓
        put("com.kingbase8", Software.KINGBASE_DRIVER);

        // 华为高斯
        put("com.huawei.gaussdb", Software.UNKNOWN);
        put("com.huawei.opengauss", Software.UNKNOWN);

        // Snowflake
        put("net.snowflake", Software.UNKNOWN);

        // Oracle
        put("oracle.jdbc", Software.ORACLE_DRIVER);
        put("oracle.ucp", Software.ORACLE_DRIVER);

        // IBM DB2
        put("com.ibm.db2", Software.DB2_DRIVER);

        // Cassandra
        put("com.datastax.oss.driver", Software.CASSANDRA_DRIVER);
        put("com.datastax.driver", Software.CASSANDRA_DRIVER);

        // HBase
        put("org.apache.hadoop.hbase", Software.HBASE_CLIENT);

        // Pulsar
        put("org.apache.pulsar", Software.PULSAR);

        // Thrift
        put("org.apache.thrift", Software.THRIFT);

 // 分库分表sphere
        put("org.apache.shardingsphere", Software.SHARDING_SPHERE);

        // Neo4j
        put("org.neo4j.driver", Software.NEO4J_DRIVER);

        // Hazelcast
        put("com.hazelcast", Software.HAZELCAST);

        // Solr
        put("org.apache.solr", Software.SOLR);

        // JMS
        put("javax.jms", Software.JMS_CLIENT);
        put("jakarta.jms", Software.JMS_CLIENT);
        put("org.apache.activemq", Software.JMS_CLIENT);
        put("org.apache.activemq.artemis", Software.JMS_CLIENT);

        // Spring Cloud Gateway
        put("org.springframework.cloud.gateway", Software.SPRING_CLOUD_GATEWAY);

 // Spring Cloud 流
        put("org.springframework.cloud.stream", Software.SPRING_CLOUD_STREAM);

        // WebSocket
        put("javax.websocket", Software.WEBSOCKET);
        put("jakarta.websocket", Software.WEBSOCKET);

        // Hibernate
        put("org.hibernate", Software.HIBERNATE);

 // Spring 数据 JPA
        put("org.springframework.data.jpa", Software.SPRING_DATA_JPA);

        // Vert.x
        put("io.vertx", Software.VERTX);

 // Play 框架
        put("play", Software.PLAY);

 // 响应式 流
        put("org.reactivestreams", Software.REACTIVE_STREAMS);

 // XXL-作业
        put("com.xxl.job", Software.XXL_JOB);

        // Sentinel
        put("com.alibaba.csp.sentinel", Software.SENTINEL);

        // Seata
        put("io.seata", Software.SEATA);

        // Apache CXF
        put("org.apache.cxf", Software.CXF);

 // 打开搜索
        put("org.opensearch", Software.OPENSEARCH);

        // HDFS
        put("org.apache.hadoop.hdfs", Software.HDFS);
        put("org.apache.hadoop.fs", Software.HDFS);

        // Spark
        put("org.apache.spark", Software.SPARK);

        // Flink
        put("org.apache.flink", Software.FLINK);

        // Spring Integration
        put("org.springframework.integration", Software.SPRING_INTEGRATION);

        // Kubernetes
        put("io.kubernetes", Software.KUBERNETES);
        put("io.kubernetes.client", Software.KUBERNETES);

        // AWS SDK
        put("software.amazon.awssdk", Software.AWS_SDK);
        put("com.amazonaws", Software.AWS_SDK);

 // 石英石
        put("org.quartz", Software.QUARTZ);

 // Spring 批量
        put("org.springframework.batch", Software.SPRING_BATCH);

        // Java 线程
        put("java.util.concurrent", Software.THREAD);

        // H2
        put("org.h2", Software.H2_DRIVER);

 // 异步http客户端
        put("org.asynchttpclient", Software.ASYNC_HTTP_CLIENT);

        // Couchbase
        put("com.couchbase.client", Software.COUCHBASE);

        // Etcd
        put("io.etcd.jetcd", Software.ETCD);

        // Ignite
        put("org.apache.ignite", Software.IGNITE);

 // r套接字
        put("io.rsocket", Software.RSOCKET);

        // NATS
        put("io.nats", Software.NATS);

        // gRPC
        put("io.grpc", Software.GRPC);

        // Feign
        put("feign", Software.FEIGN);

 // 分库分表sphere
        put("org.apache.shardingsphere", Software.SHARDING_SPHERE);

        // GraphQL
        put("graphql.GraphQL", Software.GRAPHQL_JAVA);
        put("graphql.schema", Software.GRAPHQL_JAVA);

        // SAP HANA
        put("com.sap.cloud.db", Software.UNKNOWN);
    }};

    /**
     * 需跳过的包名前缀（JDK + 本 智能体 框架自身）。
     */
    private static final List<String> SKIP_PREFIXES = Arrays.asList(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "com.oracle.",
            "com.chua.runtime", "com.chua.plugin",
            "org.objectweb.asm",
            "org.springframework.cglib",
            "org.springframework.aop",
            "net.bytebuddy",
            "com.google.common",
            "org.apache.commons"
    );

    /**
     * 栈帧跳过深度 — 跳过当前方法 + ASM 注入字节码 + runtimespy.onintercept + JDK 内部。
     */
    private static final int SKIP_FRAMES = 5;

    /**
     * 从当前线程调用栈分析发起连接的软件栈。
     *
     * <p>跳过 SKIP_FRAMES 层栈帧后，逐帧检查类名是否匹配已知三方库前缀，
     * 首个命中即返回。</p>
     *
     * @return 识别到的软件栈，无命中返回 UNKNOWN
     */
    public static Software detectSoftwareFromStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = SKIP_FRAMES; i < stack.length; i++) {
            String className = stack[i].getClassName();
            if (className == null || className.isEmpty()) {
                continue;
            }
            // 跳过 JDK 和自身框架
            if (shouldSkip(className)) {
                continue;
            }
            // 匹配包名前缀
            Software matched = matchSoftware(className);
            if (matched != null && matched != Software.UNKNOWN) {
                return matched;
            }
        }
        return Software.UNKNOWN;
    }

    /**
     * 从 套接字 实例的远程地址推断协议（通过端口号）。
     *
     * <p>使用反射避免编译时依赖，调用方负责捕获 NoSuchMethodException。</p>
     *
     * @param socket 套接字 实例
     * @return 推断的协议，获取失败返回 UNKNOWN
     */
    public static Protocol inferProtocolFromSocket(Socket socket) {
        try {
            InetSocketAddress addr = (InetSocketAddress) ReflectUtils.invoke(socket, "getRemoteSocketAddress", InetSocketAddress.class);
            if (addr != null) {
                return Protocol.inferByPort(addr.getPort());
            }
        } catch (Exception e) {
            // 未连接或获取失败，返回 TCP（默认）
        }
        return Protocol.TCP;
    }

    /**
     * 从 inet套接字地址 实例推断协议（通过端口号）。
     *
     * @param addr inet套接字地址 实例
     * @return 推断的协议
     */
    public static Protocol inferProtocolFromAddress(InetSocketAddress addr) {
        if (addr == null) {
            return Protocol.UNKNOWN;
        }
        return Protocol.inferByPort(addr.getPort());
    }

    /**
     * 从 套接字 实例提取目标 端点 信息（用于依赖图）。
     *
     * <p>反射调用 Socket.getRemoteSocketAddress() + Socket.getLocalSocketAddress()。</p>
     *
     * @param socket 套接字 实例
     * @return Endpoint 描述（主机:端口），获取失败返回 "?"
     */
    public static String extractSocketTarget(Socket socket) {
        try {
            InetSocketAddress addr = (InetSocketAddress) ReflectUtils.invoke(socket, "getRemoteSocketAddress", InetSocketAddress.class);
            if (addr != null && addr.isUnresolved()) {
                return addr.getHostName() + ":" + addr.getPort();
            }
        } catch (Exception e) {
            return "?";
        }
        return "?";
    }

    /**
     * 从 httpurlconnection 提取目标 URL。
     *
     * @param conn httpurlconnection 实例
     * @return URL 字符串，获取失败返回 "?"
     */
    public static String extractHttpUrl(Object conn) {
        try {
            Object url = ReflectUtils.invoke(conn, "getURL", Object.class);
            return url != null ? url.toString() : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 从 httpurlconnection 提取 HTTP 方法。
     *
     * @param conn httpurlconnection 实例
     * @return HTTP 方法，获取失败返回 "获取"
     */
    public static String extractHttpMethod(Object conn) {
        try {
            return (String) ReflectUtils.invoke(conn, "getRequestMethod", String.class);
        } catch (Exception e) {
            return "GET";
        }
    }

    /**
     * 判断类名是否应跳过（JDK / 本框架 / 通用库）。
     *
     * @param className 全限定类名
     * @return 应跳过返回 true
     */
    private static boolean shouldSkip(String className) {
        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通过包名前缀匹配软件栈。
     *
     * @param className 全限定类名
     * @return 匹配到的软件栈，无匹配返回 空
     */
    private static Software matchSoftware(String className) {
        for (Map.Entry<String, Software> entry : SOFTWARE_PATTERNS.entrySet()) {
            if (className.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
