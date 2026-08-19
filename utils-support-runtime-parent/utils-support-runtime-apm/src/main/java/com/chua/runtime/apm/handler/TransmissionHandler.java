package com.chua.runtime.apm.handler;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.storage.StorageManager;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.spy.RuntimeSpy;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 传输链路 Handler — 拦截 Socket / ServerSocket / DatagramSocket / HttpURLConnection。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>记录每次 TCP/UDP/HTTP 传输事件（传输链路对象）</li>
 *   <li>通过 Socket 远程地址提取远端 host:port</li>
 *   <li>通过端口号推断协议（Redis=6379, MySQL=3306, ZK=2181 等）</li>
 *   <li>通过调用栈分析识别三方软件栈（Jedis/Lettuce/Redisson/MySQL/Jedis/Kafka 等）</li>
 *   <li>与 RuntimeSpy 当前 traceId/spanId 关联</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TransmissionHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(TransmissionHandler.class.getName());

    /**
     * handler 名称
     */
    private static final String HANDLER_NAME = "transmission-handler";

    /**
     * 插件版本
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 启用配置属性 key
     */
    private static final String PROP_TRANSMISSION_ENABLED = "transmission.enabled";

    /**
     * 默认启用值
     */
    private static final String DEFAULT_ENABLED = "true";

    /**
     * 最大传输记录数
     */
    private static final int MAX_RECORDS = 10000;

    /**
     * Socket 内部名（java.net.Socket）
     */
    private static final String SOCKET = "java/net/Socket";

    /**
     * ServerSocket 内部名
     */
    private static final String SERVER_SOCKET = "java/net/ServerSocket";

    /**
     * DatagramSocket 内部名
     */
    private static final String DATAGRAM_SOCKET = "java/net/DatagramSocket";

    /**
     * HttpURLConnection 内部名
     */
    private static final String HTTP_URL_CONNECTION = "java/net/HttpURLConnection";

    /**
     * Socket 拦截方法列表（连接/读取/写入/关闭）
     */
    private static final String[] SOCKET_METHODS = {"connect", "getInputStream", "getOutputStream", "close"};

    /**
     * ServerSocket 拦截方法列表（接收连接）
     */
    private static final String[] SERVER_SOCKET_METHODS = {"accept"};

    /**
     * DatagramSocket 拦截方法列表（发送/接收）
     */
    private static final String[] DATAGRAM_METHODS = {"send", "receive"};

    /**
     * HttpURLConnection 拦截方法列表（连接）
     */
    private static final String[] HTTP_METHODS = {"connect"};

    /**
     * 传输记录列表
     */
    private final com.chua.runtime.apm.handler.BoundedRecordList<TransmissionRecord> records;

    /**
     * host:port → 计数（依赖图数据）
     */
    private final Map<String, Long> connectionCount;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 插件上下文
     */
    private PluginContext context;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /**
     * 传输记录线程局部存储（用于关联 ENTRY 和 EXIT）。
     */
    private static final ThreadLocal<TransmissionRecord> TRANSMISSION_HOLDER =
            new ThreadLocal<>();

    /** 创建 TransmissionHandler 实例 */
    public TransmissionHandler() {
        this.records = new com.chua.runtime.apm.handler.BoundedRecordList<>(10000);
        this.connectionCount = new ConcurrentHashMap<>();
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** Name */
    public String name() {
        return HANDLER_NAME;
    }

    @Override
    /** Version */
    public String version() {
        return HANDLER_VERSION;
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = DEFAULT_ENABLED.equals(context.getProperty(PROP_TRANSMISSION_ENABLED, DEFAULT_ENABLED));
        LOG.log(Level.INFO, String.format("TransmissionHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    /** 开始 */
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registerSocketInterceptors();
        LOG.log(Level.INFO, "TransmissionHandler 启动完成，传输链路追踪已启用");
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "TransmissionHandler 停止");
    }

    @Override
    /** Status */
    public String status() {
        return String.format("TransmissionHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /** 注册SocketInterceptors */
    private void registerSocketInterceptors() {
        for (String method : SOCKET_METHODS) {
            RuntimeSpy.registerInterceptor(SOCKET, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(SOCKET, method, "", InterceptPoint.EXIT, this);
            // EXCEPTION 拦截 JDK 核心类（java/net/Socket 等）会触发 VerifyError，
            // 因为 AdviceAdapter 的 onMethodExit 与 try/catch 包装产生 frame 冲突。
            // Socket.connect 抛 ConnectException 的场景由 NetHandler.NET_CONNECT_POST 捕获即可。
        }
        for (String method : SERVER_SOCKET_METHODS) {
            RuntimeSpy.registerInterceptor(SERVER_SOCKET, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(SERVER_SOCKET, method, "", InterceptPoint.EXIT, this);
            // 同上：EXCEPTION 注册会在 ServerSocket.accept 等热点方法触发 VerifyError
        }
        for (String method : DATAGRAM_METHODS) {
            RuntimeSpy.registerInterceptor(DATAGRAM_SOCKET, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(DATAGRAM_SOCKET, method, "", InterceptPoint.EXIT, this);
        }
        for (String method : HTTP_METHODS) {
            RuntimeSpy.registerInterceptor(HTTP_URL_CONNECTION, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(HTTP_URL_CONNECTION, method, "", InterceptPoint.EXIT, this);
        }
    }

    @Override
    /** OnIntercept */
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        InterceptPoint point = ctx.getPoint();
        if (point == InterceptPoint.ENTRY) {
            handleEntry(ctx);
        } else if (point == InterceptPoint.EXIT) {
            handleExit(ctx);
        }
    }

    /**
     * 本地网络身份缓存 — 避免在 handleEntry 阶段重复解析 InetAddress.getLocalHost()
     */
    private static volatile String LOCAL_HOST;
    /**
     * local 端口 hint
     */
    private static volatile int LOCAL_PORT_HINT = -1;

    /** 解析LocalHost */
    private static String resolveLocalHost() {
        if (LOCAL_HOST != null) {
            return LOCAL_HOST;
        }
        try {
            LOCAL_HOST = java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            LOCAL_HOST = "localhost";
        }
        return LOCAL_HOST;
    }

    /** 处理Entry */
    private void handleEntry(InterceptContext ctx) {
        try {
            String className = ctx.getClassName();
            String operation = ctx.getMethodName();
            TransmissionRecord record = new TransmissionRecord();
            record.setTraceId(ctx.getTraceId());
            record.setSpanId(ctx.getSpanId());
            record.setStartTime(System.currentTimeMillis());
            record.setOperation(operation);

            // 通过栈分析识别发起方软件栈
            Software software = SoftwareDetector.detectSoftwareFromStack();
            record.setSoftware(software);

            // 协议推断：优先使用栈分析识别，其次用类名兜底
            Protocol protocol = inferProtocolFromStackAndClass(software, className);
            record.setProtocol(protocol);

            // 端点角色 + 源端点：
            //   - ServerSocket.accept → SERVER（接收端）
            //   - Socket/HttpURLConnection → CLIENT（发起端）
            Object instance = ctx.getUserData();
            if (SERVER_SOCKET.equals(className)) {
                record.setSource(Endpoint.builder().kind(EndpointKind.SERVER).build());
            } else if (HTTP_URL_CONNECTION.equals(className) && instance != null) {
                // 反射提取 HTTP URL 推算 source host:port
                String url = SoftwareDetector.extractHttpUrl(instance);
                String sourceHost = resolveLocalHost();
                int sourcePort = LOCAL_PORT_HINT;
                try {
                    java.net.URL u = new java.net.URL(url);
                    // 客户端连接的对端端口不影响本地端口
                    sourcePort = u.getDefaultPort();
                } catch (Exception ignore) {
                    // url 解析失败，使用兜底值
                }
                record.setSource(Endpoint.builder()
                        .kind(EndpointKind.CLIENT)
                        .protocol(protocol)
                        .software(software)
                        .host(sourceHost)
                        .port(sourcePort >= 0 ? sourcePort : 0)
                        .path("/")
                        .build());
            } else if (SOCKET.equals(className) && instance != null) {
                String sourceHost = resolveLocalHost();
                int sourcePort = 0;
                try {
                    java.net.InetSocketAddress local = (java.net.InetSocketAddress) instance.getClass()
                            .getMethod("getLocalSocketAddress").invoke(instance);
                    if (local != null) {
                        sourcePort = local.getPort();
                    }
                } catch (Exception ignore) {
                    // socket 未连接或访问异常
                }
                record.setSource(Endpoint.builder()
                        .kind(EndpointKind.CLIENT)
                        .protocol(protocol)
                        .software(software)
                        .host(sourceHost)
                        .port(sourcePort)
                        .path("/")
                        .build());
            } else {
                record.setSource(Endpoint.builder()
                        .kind(EndpointKind.CLIENT)
                        .software(software)
                        .build());
            }

            TRANSMISSION_HOLDER.set(record);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("TransmissionHandler.entry 处理异常: %s", e.getMessage()));
        }
    }

    /** 处理Exit */
    private void handleExit(InterceptContext ctx) {
        try {
            TransmissionRecord record = TRANSMISSION_HOLDER.get();
            if (record == null) {
                return;
            }
            TRANSMISSION_HOLDER.remove();

            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.OK);

            // 补充目标端点信息（EXIT 时再次调用，覆盖 ENTRY 阶段未到位的状态）
            enrichTargetEndpoint(ctx, record);

            // 累加连接计数（依赖图数据）
            String hostPort = record.getTarget() != null
                    ? record.getTarget().nodeId()
                    : record.getProtocol().name();
            connectionCount.merge(hostPort, 1L, Long::sum);

            records.add(record);

            // 同步到依赖图（DependencyGraphHandler）
            emitToDependencyGraph(record);

            // 持久化（SPI 接入存储层）
            StorageManager.appendTransmission(record);

            LOG.log(Level.FINE, String.format("[Transmission] %s %s -> %s %sms software=%s", record.getProtocol(), record.getOperation(), record.getTarget() != null ? record.getTarget().displayLabel() : "?", record.getDuration(), record.getSoftware()));
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("TransmissionHandler.exit 处理异常: %s", e.getMessage()));
        }
    }

    /**
     * 把单次传输事件同步到 DependencyGraphHandler，生成 source → target 边。
     *
     * @param record 传输记录
     */
    private void emitToDependencyGraph(TransmissionRecord record) {
        try {
            DependencyGraphHandler handler = ApmBootstrap.getGlobalHandler(DependencyGraphHandler.class);
            if (handler == null) {
                return;
            }
            Endpoint source = record.getSource();
            Endpoint target = record.getTarget();
            if (source == null || target == null) {
                return;
            }
            handler.record(source, target, record.getProtocol(),
                    record.getSoftware(), record.getDuration(), false, null);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("emitToDependencyGraph 异常: %s", e.getMessage()));
        }
    }

    /**
     * 补充目标端点信息（port / host / software / operation）。
     */
    private void enrichTargetEndpoint(InterceptContext ctx, TransmissionRecord record) {
        String className = ctx.getClassName();
        try {
            if (SOCKET.equals(className) || SERVER_SOCKET.equals(className)) {
                enrichSocketEndpoint(ctx, record);
            } else if (DATAGRAM_SOCKET.equals(className)) {
                enrichDatagramEndpoint(ctx, record);
            } else if (HTTP_URL_CONNECTION.equals(className)) {
                enrichHttpEndpoint(ctx, record);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("enrichTargetEndpoint 异常: %s", e.getMessage()));
        }
    }

    /** EnrichSocketEndpoint */
    private void enrichSocketEndpoint(InterceptContext ctx, TransmissionRecord record) {
        try {
            // 反射获取 Socket 实例（从 InterceptContext 的 userData 或自身）
            Object socket = resolveSocketObject(ctx);
            if (socket != null) {
                String host = "?";
                int port = 0;
                try {
                    java.net.InetSocketAddress remote = (java.net.InetSocketAddress) socket.getClass()
                            .getMethod("getRemoteSocketAddress").invoke(socket);
                    if (remote != null) {
                        host = remote.getHostString();
                        port = remote.getPort();
                    }
                } catch (Exception e) {
                    // socket 未连接
                }
                Protocol protocol = record.getProtocol();
                if (protocol == null || protocol == Protocol.UNKNOWN) {
                    protocol = SoftwareDetector.inferProtocolFromSocket((Socket) socket);
                }
                record.setProtocol(protocol);
                record.setTarget(Endpoint.builder()
                        .kind(EndpointKind.SERVER)
                        .protocol(protocol)
                        .software(record.getSoftware())
                        .host(host)
                        .port(port)
                        .build());
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("enrichSocketEndpoint 异常: %s", e.getMessage()));
        }
    }

    /** EnrichDatagramEndpoint */
    private void enrichDatagramEndpoint(InterceptContext ctx, TransmissionRecord record) {
        // DatagramSocket 的 send/receive 目标地址来自 DatagramPacket 参数
        // 此处通过栈分析已有 software 信息，protocol 已设为 UDP
        if (record.getTarget() == null) {
            record.setTarget(Endpoint.builder()
                    .kind(EndpointKind.SERVER)
                    .protocol(Protocol.UDP)
                    .software(record.getSoftware())
                    .build());
        }
    }

    /** EnrichHttpEndpoint */
    private void enrichHttpEndpoint(InterceptContext ctx, TransmissionRecord record) {
        try {
            Object conn = resolveHttpConnection(ctx);
            if (conn != null) {
                String url = SoftwareDetector.extractHttpUrl(conn);
                String method = SoftwareDetector.extractHttpMethod(conn);
                record.setOperation(method + " " + url);
                String host = "?";
                int port = 0;
                try {
                    java.net.URL u = new java.net.URL(url);
                    host = u.getHost();
                    port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
                } catch (Exception e) {
                    // url 解析失败
                }
                record.setTarget(Endpoint.builder()
                        .kind(EndpointKind.SERVER)
                        .protocol(Protocol.HTTP)
                        .software(record.getSoftware())
                        .host(host)
                        .port(port)
                        .path(url)
                        .build());
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("enrichHttpEndpoint 异常: %s", e.getMessage()));
        }
    }

    /**
     * 推断协议：优先用栈分析识别的软件栈反查默认协议，其次用类名兜底。
     */
    private Protocol inferProtocolFromStackAndClass(Software software, String className) {
        // 已识别软件栈 → 反查默认协议
        switch (software) {
            case JEDIS, LETTUCE, REDISSON -> { return Protocol.REDIS; }
            case MYSQL_DRIVER -> { return Protocol.MYSQL; }
            case POSTGRESQL_DRIVER -> { return Protocol.POSTGRESQL; }
            case ZOOKEEPER_NATIVE, CURATOR -> { return Protocol.ZOOKEEPER; }
            case KAFKA_PRODUCER, KAFKA_CONSUMER -> { return Protocol.KAFKA; }
            case ROCKETMQ_PRODUCER, ROCKETMQ_CONSUMER -> { return Protocol.ROCKETMQ; }
            case RABBITMQ_CLIENT -> { return Protocol.RABBITMQ; }
            case PAHO_MQTT -> { return Protocol.MQTT; }
            case FEIGN, REST_TEMPLATE, OKHTTP, APACHE_HTTPCLIENT, WEB_CLIENT -> { return Protocol.HTTP; }
            case NACOS -> { return Protocol.NACOS; }
            case EUREKA -> { return Protocol.EUREKA; }
            case CONSUL -> { return Protocol.CONSUL; }
            case MONGODB_DRIVER -> { return Protocol.MONGODB; }
            case CASSANDRA_DRIVER -> { return Protocol.CASSANDRA; }
            case HBASE_CLIENT -> { return Protocol.HBASE; }
            case PULSAR -> { return Protocol.PULSAR; }
            case THRIFT -> { return Protocol.THRIFT; }
            case SHARDING_SPHERE -> { return Protocol.SHARDING_SPHERE; }
            case NEO4J_DRIVER -> { return Protocol.NEO4J; }
            case HAZELCAST -> { return Protocol.HAZELCAST; }
            case SOLR -> { return Protocol.SOLR; }
            case JMS_CLIENT -> { return Protocol.JMS; }
            case SPRING_CLOUD_GATEWAY -> { return Protocol.HTTP; }
            case ASYNC_HTTP_CLIENT -> { return Protocol.HTTP; }
            case COUCHBASE -> { return Protocol.COUCHBASE; }
            case ETCD -> { return Protocol.ETCD; }
            case IGNITE -> { return Protocol.IGNITE; }
            case RSOCKET -> { return Protocol.RSOCKET; }
            case NATS -> { return Protocol.NATS; }
            case GRAPHQL_JAVA -> { return Protocol.HTTP; }
            case INFLUXDB_CLIENT -> { return Protocol.INFLUXDB; }
            case DB2_DRIVER -> { return Protocol.DB2; }
            case DAMENG_DRIVER -> { return Protocol.DAMENG; }
            case KINGBASE_DRIVER -> { return Protocol.KINGBASE; }
            case GRPC -> { return Protocol.GRPC; }
            case TOMCAT, JETTY, NETTY, UNDERTOW -> { return Protocol.HTTP; }
            case WEBSOCKET -> { return Protocol.WEBSOCKET; }
            case HIBERNATE, MYBATIS, SPRING_DATA_JPA -> { return Protocol.SQL; }
            case SPRING_CLOUD_STREAM, REACTIVE_STREAMS -> { return Protocol.MESSAGE; }
            case VERTX, PLAY, CXF -> { return Protocol.HTTP; }
             case OPENSEARCH -> { return Protocol.ELASTICSEARCH; }
             case XXL_JOB, SENTINEL, SEATA -> { return Protocol.INTERNAL; }
case HDFS, SPARK, FLINK -> { return Protocol.INTERNAL; }
            case SPRING_INTEGRATION -> { return Protocol.MESSAGE; }
            case THREAD -> { return Protocol.INTERNAL; }
            case H2_DRIVER -> { return Protocol.H2; }
             case KUBERNETES, AWS_SDK -> { return Protocol.HTTP; }
             case QUARTZ, SPRING_BATCH -> { return Protocol.INTERNAL; }
             default -> {
                // 软件栈未识别 → 用类名兜底
                return inferProtocolFromClass(className);
            }
        }
    }

    /** InferProtocolFromClass */
    private Protocol inferProtocolFromClass(String internalName) {
        if (internalName == null) {
            return Protocol.UNKNOWN;
        }
        if (SOCKET.equals(internalName) || SERVER_SOCKET.equals(internalName)) {
            return Protocol.TCP;
        }
        if (DATAGRAM_SOCKET.equals(internalName)) {
            return Protocol.UDP;
        }
        if (internalName.contains("HttpURLConnection")) {
            return Protocol.HTTP;
        }
        return Protocol.UNKNOWN;
    }

    /** 解析SocketObject */
    private Object resolveSocketObject(InterceptContext ctx) {
        // 从 userData 优先；否则从 ctx 自身的 this 引用尝试获取
        return ctx.getUserData() != null ? ctx.getUserData() : null;
    }

    /** 解析HttpConnection */
    private Object resolveHttpConnection(InterceptContext ctx) {
        return ctx.getUserData() != null ? ctx.getUserData() : null;
    }

    /** 获取Records */
    public List<TransmissionRecord> getRecords() {
        return records.snapshot();
    }

    /** 获取Connection计算数量 */
    public Map<String, Long> getConnectionCount() {
        return Collections.unmodifiableMap(connectionCount);
    }

    /** Clear */
    public void clear() {
        records.clear();
        connectionCount.clear();
    }
}
