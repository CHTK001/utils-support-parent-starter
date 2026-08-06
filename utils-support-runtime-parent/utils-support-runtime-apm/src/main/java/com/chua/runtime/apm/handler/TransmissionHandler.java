package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.SpanKind;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
 *   <li>通过 Socket.getRemoteSocketAddress() 拿到 host:port</li>
 *   <li>通过端口号推断协议（HTTP=80, ZK=2181, Redis=6379, MySQL=3306...）</li>
 *   <li>与 RuntimeSpy 当前 traceId/spanId 关联</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TransmissionHandler implements Plugin, RuntimeSpy.Interceptor {

    /**
     * 插件名称
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
     * 拦截方法列表
     */
    private static final String[] SOCKET_METHODS = {"connect", "getInputStream", "getOutputStream", "close"};
    private static final String[] SERVER_SOCKET_METHODS = {"accept"};
    private static final String[] DATAGRAM_METHODS = {"send", "receive"};
    private static final String[] HTTP_METHODS = {"connect"};

    /**
     * 传输记录列表
     */
    private final List<TransmissionRecord> records;

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

    public TransmissionHandler() {
        this.records = Collections.synchronizedList(new ArrayList<>());
        this.connectionCount = new ConcurrentHashMap<>();
        this.started = new AtomicBoolean(false);
    }

    @Override
    public String name() {
        return HANDLER_NAME;
    }

    @Override
    public String version() {
        return HANDLER_VERSION;
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = DEFAULT_ENABLED.equals(context.getProperty(PROP_TRANSMISSION_ENABLED, DEFAULT_ENABLED));
        log.info("TransmissionHandler 初始化完成，启用状态: {}", enabled);
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registerSocketInterceptors();
        log.info("TransmissionHandler 启动完成，传输链路追踪已启用");
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        log.info("TransmissionHandler 停止");
    }

    @Override
    public String status() {
        return String.format("TransmissionHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 注册 Socket / ServerSocket / DatagramSocket / HttpURLConnection 的拦截器。
     */
    private void registerSocketInterceptors() {
        // Socket.connect — 客户端主动连接（推断远端 host:port）
        for (String method : SOCKET_METHODS) {
            RuntimeSpy.registerInterceptor(SOCKET, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(SOCKET, method, "", InterceptPoint.EXIT, this);
        }
        // ServerSocket.accept — 服务端接收连接
        for (String method : SERVER_SOCKET_METHODS) {
            RuntimeSpy.registerInterceptor(SERVER_SOCKET, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(SERVER_SOCKET, method, "", InterceptPoint.EXIT, this);
        }
        // DatagramSocket.send / receive
        for (String method : DATAGRAM_METHODS) {
            RuntimeSpy.registerInterceptor(DATAGRAM_SOCKET, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(DATAGRAM_SOCKET, method, "", InterceptPoint.EXIT, this);
        }
        // HttpURLConnection.connect — JDK HTTP 客户端
        for (String method : HTTP_METHODS) {
            RuntimeSpy.registerInterceptor(HTTP_URL_CONNECTION, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(HTTP_URL_CONNECTION, method, "", InterceptPoint.EXIT, this);
        }
    }

    @Override
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
     * 方法入口 — 解析参数并准备传输记录。
     *
     * @param ctx 插桩上下文
     */
    private void handleEntry(InterceptContext ctx) {
        try {
            Object targetObj = ctx.getUserData() != null ? ctx.getUserData() : ctx;
            TransmissionRecord record = new TransmissionRecord();
            record.setSpanId(ctx.getSpanId());
            record.setTraceId(ctx.getTraceId());
            record.setStartTime(System.currentTimeMillis());
            record.setProtocol(inferProtocolFromClass(ctx.getClassName()));
            record.setSoftware(inferSoftwareFromClass(ctx.getClassName()));
            // 通过当前 Thread 局部变量关联
            TRANSMISSION_HOLDER.set(record);
            record.setOperation(ctx.getMethodName());
        } catch (Exception e) {
            log.debug("TransmissionHandler.entry 处理异常: {}", e.getMessage());
        }
    }

    /**
     * 方法出口 — 完成传输记录。
     *
     * @param ctx 插桩上下文
     */
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

            // 累加连接计数（依赖图数据）
            String hostPort = record.getTarget() != null
                    ? record.getTarget().nodeId()
                    : record.getProtocol().name();
            connectionCount.merge(hostPort, 1L, Long::sum);

            if (records.size() >= MAX_RECORDS) {
                records.remove(0);
            }
            records.add(record);

            log.trace("[Transmission] {} {} -> {} {}ms",
                    record.getProtocol(),
                    record.getOperation(),
                    record.getTarget() != null ? record.getTarget().displayLabel() : "?",
                    record.getDuration());
        } catch (Exception e) {
            log.debug("TransmissionHandler.exit 处理异常: {}", e.getMessage());
        }
    }

    /**
     * 推断协议（基于类内部名）。
     *
     * @param internalName 类内部名
     * @return 协议
     */
    private Protocol inferProtocolFromClass(String internalName) {
        if (internalName == null) {
            return Protocol.UNKNOWN;
        }
        if (internalName.equals(SOCKET)) {
            return Protocol.TCP;
        }
        if (internalName.equals(SERVER_SOCKET)) {
            return Protocol.TCP;
        }
        if (internalName.equals(DATAGRAM_SOCKET)) {
            return Protocol.UDP;
        }
        if (internalName.contains("HttpURLConnection")) {
            return Protocol.HTTP;
        }
        return Protocol.UNKNOWN;
    }

    /**
     * 推断软件栈（基于类内部名）。
     *
     * @param internalName 类内部名
     * @return 软件栈
     */
    private Software inferSoftwareFromClass(String internalName) {
        if (internalName == null) {
            return Software.UNKNOWN;
        }
        if (internalName.contains("HttpURLConnection")) {
            return Software.JDK_HTTP_CLIENT;
        }
        if (internalName.equals(SOCKET) || internalName.equals(SERVER_SOCKET)) {
            return Software.JDK_HTTP_SERVER;
        }
        if (internalName.equals(DATAGRAM_SOCKET)) {
            return Software.JDK_HTTP_SERVER;
        }
        return Software.UNKNOWN;
    }

    /**
     * 传输记录线程局部存储（用于关联 ENTRY 和 EXIT）。
     */
    private static final ThreadLocal<TransmissionRecord> TRANSMISSION_HOLDER =
            new ThreadLocal<>();

    /**
     * 获取所有传输记录。
     *
     * @return 不可修改的列表
     */
    public List<TransmissionRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    /**
     * 获取连接计数（按 host:port 分组）。
     *
     * @return host:port → 计数
     */
    public Map<String, Long> getConnectionCount() {
        return Collections.unmodifiableMap(connectionCount);
    }

    /**
     * 清空所有记录。
     */
    public void clear() {
        records.clear();
        connectionCount.clear();
    }
}
