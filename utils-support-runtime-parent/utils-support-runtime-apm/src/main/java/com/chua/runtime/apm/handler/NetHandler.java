package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络拦截器 — 劫持 Socket/HTTP/TCP/UDP 通信。
 *
 * <p>字节码插桩实现：</p>
 * <p>对目标网络类（如 java/net/Socket）的 connect/read/write 方法，
 * 在方法入口/出口插入 RuntimeSpy.onIntercept()。</p>
 *
 * <p>ASM 插入的字节码：</p>
 * <pre>
 * Socket.connect(InetSocketAddress, int):
 *   LDC "java/net/Socket"            // className
 *   LDC "connect"                     // methodName
 *   LDC "(Ljava/net/SocketAddress;I)V" // descriptor
 *   LDC "net_connect_pre"             // pointKey
 *   INVOKESTATIC RuntimeSpy.onIntercept
 *   // 原始方法体...
 *   LDC "java/net/Socket"             // className
 *   LDC "connect"                     // methodName
 *   LDC "(Ljava/net/SocketAddress;I)V" // descriptor
 *   LDC "net_connect_post"            // pointKey
 *   INVOKESTATIC RuntimeSpy.onIntercept
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NetHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(NetHandler.class.getName());

    /**
     * java.net.Socket 类名
     */
    private static final String SOCKET_CLASS = "java/net/Socket";

    /**
     * java.net.DatagramSocket 类名
     */
    private static final String DATAGRAM_CLASS = "java/net/DatagramSocket";

    /**
     * java.net.HttpURLConnection 类名
     */
    private static final String HTTP_URL_CONN = "java/net/HttpURLConnection";

    /**
     * 网络记录列表
     */
    private final BoundedRecordList<NetRecord> records;

    /**
     * 最大记录数
     */
    private static final int MAX_RECORDS = 10000;

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

    /** 创建 NetHandler 实例 */
    public NetHandler() {
        this.records = new BoundedRecordList<>(MAX_RECORDS);
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** Name */
    public String name() {
        return "net-handler";
    }

    @Override
    /** Version */
    public String version() {
        return "1.0.0";
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = "true".equals(context.getProperty("net.enabled", "true"));
        LOG.log(Level.INFO, String.format("NetHandler 初始化完成，启用状态: %s", enabled));
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
        registerSocketIntercepts();
        registerDatagramIntercepts();
        registerHttpIntercepts();
        LOG.log(Level.INFO, "NetHandler 启动完成，已注册网络拦截点");
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "NetHandler 停止");
    }

    @Override
    /** Status */
    public String status() {
        return String.format("NetHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 接收插桩事件。
     *
     * @param context 插桩上下文
     */
    @Override
    public void onIntercept(InterceptContext context) {
        if (!enabled) {
            return;
        }
        InterceptPoint point = context.getPoint();
        switch (point) {
            case NET_CONNECT_PRE -> recordConnection(context, "connecting");
            case NET_CONNECT_POST -> recordConnection(context, "connected");
            case NET_WRITE_PRE -> updateLast(context, NetRecord::incrementSent);
            case NET_READ_PRE -> updateLast(context, NetRecord::incrementReceived);
            case HTTP_REQUEST_PRE -> recordHttp(context, "request");
            case HTTP_RESPONSE_POST -> recordHttp(context, "response");
            default -> {
            }
        }
    }

    /**
     * 记录连接事件。
     *
     * @param ctx    插桩上下文
     * @param status 连接状态
     */
    private void recordConnection(InterceptContext ctx, String status) {
        NetRecord record = new NetRecord();
        record.setTimestamp(ctx.getTimestamp());
        record.setProtocol(resolveProtocol(ctx.getClassName()));
        record.setSourceAddress("");
        record.setTargetAddress("");
        record.setBytesSent(0);
        record.setBytesReceived(0);
        record.setDuration(0);
        record.setStatus(status);
        record.setClassName(ctx.getReadableClassName());
        record.setMethodName(ctx.getMethodName());
        addRecord(record);
    }

    /**
     * 记录 HTTP 事件。
     *
     * @param ctx    插桩上下文
     * @param status 请求/响应状态
     */
    private void recordHttp(InterceptContext ctx, String status) {
        NetRecord record = new NetRecord();
        record.setTimestamp(ctx.getTimestamp());
        record.setProtocol("HTTP");
        record.setSourceAddress("");
        record.setTargetAddress("");
        record.setBytesSent(0);
        record.setBytesReceived(0);
        record.setDuration(0);
        record.setStatus(status);
        record.setClassName(ctx.getReadableClassName());
        record.setMethodName(ctx.getMethodName());
        addRecord(record);
    }

    /**
     * 更新最后一条记录的字节计数。
     *
     * @param ctx    插桩上下文
     * @param action 更新动作
     */
    private void updateLast(InterceptContext ctx, UpdateAction action) {
        if (records.size() == 0) {
            return;
        }
        java.util.List<NetRecord> tail = records.tail(1);
        if (tail.isEmpty()) {
            return;
        }
        NetRecord record = tail.get(0);
        action.apply(record);
        LOG.log(Level.FINE, String.format("[Net] %s %s 字节计数更新", ctx.getReadableClassName(), ctx.getMethodName()));
    }

    /**
     * 注册 Socket 连接拦截。
     */
    private void registerSocketIntercepts() {
        RuntimeSpy.registerInterceptor(SOCKET_CLASS, "connect",
                "(Ljava/net/SocketAddress;I)V", InterceptPoint.NET_CONNECT_PRE, this);
        RuntimeSpy.registerInterceptor(SOCKET_CLASS, "connect",
                "(Ljava/net/SocketAddress;I)V", InterceptPoint.NET_CONNECT_POST, this);
        LOG.log(Level.FINE, "已注册 Socket 拦截点");
    }

    /**
     * 注册 DatagramSocket (UDP) 拦截。
     */
    private void registerDatagramIntercepts() {
        RuntimeSpy.registerInterceptor(DATAGRAM_CLASS, "send",
                "(Ljava/net/DatagramPacket;)V", InterceptPoint.NET_WRITE_PRE, this);
        RuntimeSpy.registerInterceptor(DATAGRAM_CLASS, "receive",
                "(Ljava/net/DatagramPacket;)V", InterceptPoint.NET_READ_PRE, this);
        LOG.log(Level.FINE, "已注册 DatagramSocket 拦截点");
    }

    /**
     * 注册 HttpURLConnection 拦截。
     */
    private void registerHttpIntercepts() {
        RuntimeSpy.registerInterceptor(HTTP_URL_CONN, "connect",
                "()V", InterceptPoint.HTTP_REQUEST_PRE, this);
        RuntimeSpy.registerInterceptor(HTTP_URL_CONN, "connect",
                "()V", InterceptPoint.HTTP_RESPONSE_POST, this);
        LOG.log(Level.FINE, "已注册 HttpURLConnection 拦截点");
    }

    /**
     * 解析协议类型。
     *
     * @param className 内部类名
     * @return 协议
     */
    private String resolveProtocol(String className) {
        if (SOCKET_CLASS.equals(className)) {
            return "TCP";
        }
        if (DATAGRAM_CLASS.equals(className)) {
            return "UDP";
        }
        if (HTTP_URL_CONN.equals(className)) {
            return "HTTP";
        }
        return "UNKNOWN";
    }

    /**
     * 添加网络记录。
     *
     * @param record 网络记录
     */
    public void addRecord(NetRecord record) {
        records.add(record);
    }

    /**
     * 获取所有网络记录。
     *
     * @return 网络记录列表
     */
    public List<NetRecord> getRecords() {
        return records.snapshot();
    }

    /**
     * 获取最近 N 条网络记录。
     *
     * @param n 条数
     * @return 网络记录列表
     */
    public List<NetRecord> tail(int n) {
        return records.tail(n);
    }

    /**
     * 清空网络记录。
     */
    public void clear() {
        records.clear();
    }

    /**
     * 更新动作函数接口。
     */
    private interface UpdateAction {

        /**
         * 执行更新。
         *
         * @param record 网络记录
         */
        void apply(NetRecord record);
    }

    /**
     * 网络记录。
     *
     * @since 4.0.0.42
     */
    @Data
    public static class NetRecord {

        /**
         * 时间戳
         */
        private long timestamp;

        /**
         * 协议类型
         */
        private String protocol;

        /**
         * 源地址
         */
        private String sourceAddress;

        /**
         * 目标地址
         */
        private String targetAddress;

        /**
         * 发送字节数
         */
        private long bytesSent;

        /**
         * 接收字节数
         */
        private long bytesReceived;

        /**
         * 耗时（毫秒）
         */
        private long duration;

        /**
         * 状态
         */
        private String status;

        /**
         * 类名
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;

        /**
         * 增加发送字节数。
         */
        public void incrementSent() {
            this.bytesSent++;
        }

        /**
         * 增加接收字节数。
         */
        public void incrementReceived() {
            this.bytesReceived++;
        }
    }
}
