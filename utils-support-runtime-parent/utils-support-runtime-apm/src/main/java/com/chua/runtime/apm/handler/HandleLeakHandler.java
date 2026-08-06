package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 句柄泄漏 Handler — 监控文件 / Socket / Thread / 锁等资源句柄。
 *
 * <p>记录每个句柄的创建和关闭时间，定期扫描超时未关闭的句柄
 * 生成泄漏报告。</p>
 *
 * <p>句柄分类：</p>
 * <ul>
 *   <li>FILE — FileInputStream/FileOutputStream/FileChannel</li>
 *   <li>SOCKET — Socket/ServerSocket/DatagramSocket</li>
 *   <li>THREAD — 长时间未结束的线程</li>
 *   <li>LOCK — ReentrantLock 未释放</li>
 *   <li>JDBC_CONN — JDBC Connection 未关闭</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class HandleLeakHandler implements Plugin, RuntimeSpy.Interceptor {

    /**
     * 插件名称
     */
    private static final String HANDLER_NAME = "handle-leak-handler";

    /**
     * 插件版本
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 启用配置属性 key
     */
    private static final String PROP_LEAK_ENABLED = "leak.enabled";

    /**
     * 默认启用值
     */
    private static final String DEFAULT_ENABLED = "true";

    /**
     * 句柄泄漏判定阈值（毫秒）
     */
    private static final long LEAK_THRESHOLD_MS = 60_000L;

    /**
     * 内部名：FileInputStream
     */
    private static final String FILE_INPUT_STREAM = "java/io/FileInputStream";

    /**
     * 内部名：FileOutputStream
     */
    private static final String FILE_OUTPUT_STREAM = "java/io/FileOutputStream";

    /**
     * 内部名：Socket
     */
    private static final String SOCKET = "java/net/Socket";

    /**
     * 内部名：ServerSocket
     */
    private static final String SERVER_SOCKET = "java/net/ServerSocket";

    /**
     * 句柄记录
     */
    private final Map<String, HandleRecord> handles;

    /**
     * 句柄 ID 自增
     */
    private final AtomicLong idGenerator;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    public HandleLeakHandler() {
        this.handles = new ConcurrentHashMap<>();
        this.idGenerator = new AtomicLong(0);
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
        this.enabled = DEFAULT_ENABLED.equals(context.getProperty(PROP_LEAK_ENABLED, DEFAULT_ENABLED));
        log.info("HandleLeakHandler 初始化完成，启用状态: {}", enabled);
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 拦截文件流构造（句柄创建）
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "<init>", "()V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "<init>", "(Ljava/lang/String;)V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "<init>", "()V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "<init>", "(Ljava/lang/String;)V", InterceptPoint.ENTRY, this);
        // 拦截关闭（句柄释放）
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(SOCKET, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(SERVER_SOCKET, "close", "()V", InterceptPoint.EXIT, this);
        log.info("HandleLeakHandler 启动完成，句柄泄漏检测已启用");
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        log.info("HandleLeakHandler 停止");
    }

    @Override
    public String status() {
        return String.format("HandleLeakHandler[enabled=%s, handles=%d, leaks=%d]",
                enabled, handles.size(), detectLeaks().size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    @Override
    public void onIntercept(com.chua.runtime.spy.InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        String methodName = ctx.getMethodName();
        String className = ctx.getClassName();
        if ("<init>".equals(methodName)) {
            recordOpen(className);
        } else if ("close".equals(methodName)) {
            recordClose(className);
        }
    }

    /**
     * 记录句柄打开。
     *
     * @param className 类内部名
     */
    private void recordOpen(String className) {
        String handleId = "h-" + idGenerator.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        HandleKind kind = inferKind(className);
        HandleRecord record = new HandleRecord();
        record.setHandleId(handleId);
        record.setKind(kind);
        record.setName(className);
        record.setOwnerThread(Thread.currentThread().getName());
        record.setCreatedAt(System.currentTimeMillis());
        record.setLastUsedAt(System.currentTimeMillis());
        record.setClosed(false);
        handles.put(handleId, record);
        log.trace("[Handle] OPEN: kind={} id={} thread={}", kind, handleId, record.getOwnerThread());
    }

    /**
     * 记录句柄关闭（简化：移除最早同类的句柄）。
     *
     * @param className 类内部名
     */
    private void recordClose(String className) {
        // 找到本线程最老的一个同类型句柄移除
        HandleKind kind = inferKind(className);
        String threadName = Thread.currentThread().getName();
        HandleRecord oldest = null;
        for (HandleRecord r : handles.values()) {
            if (r.getKind() == kind && !r.isClosed() && threadName.equals(r.getOwnerThread())) {
                if (oldest == null || r.getCreatedAt() < oldest.getCreatedAt()) {
                    oldest = r;
                }
            }
        }
        if (oldest != null) {
            oldest.setClosed(true);
            oldest.setLastUsedAt(System.currentTimeMillis());
            handles.remove(oldest.getHandleId());
            log.trace("[Handle] CLOSE: kind={} id={} age={}ms", kind, oldest.getHandleId(),
                    oldest.getLastUsedAt() - oldest.getCreatedAt());
        }
    }

    /**
     * 根据类名推断句柄类型。
     *
     * @param className 类内部名
     * @return 句柄类型
     */
    private HandleKind inferKind(String className) {
        if (className == null) {
            return HandleKind.UNKNOWN;
        }
        if (className.contains("FileInputStream") || className.contains("FileOutputStream")
                || className.contains("FileChannel") || className.contains("RandomAccessFile")) {
            return HandleKind.FILE;
        }
        if (className.contains("Socket")) {
            return HandleKind.SOCKET;
        }
        if (className.contains("Connection") || className.contains("DataSource")) {
            return HandleKind.JDBC_CONN;
        }
        return HandleKind.UNKNOWN;
    }

    /**
     * 检测超时未关闭的句柄。
     *
     * @return 泄漏的句柄列表
     */
    public java.util.List<HandleRecord> detectLeaks() {
        long now = System.currentTimeMillis();
        java.util.List<HandleRecord> leaks = new java.util.ArrayList<>();
        for (HandleRecord r : handles.values()) {
            if (!r.isClosed() && now - r.getCreatedAt() > LEAK_THRESHOLD_MS) {
                leaks.add(r);
            }
        }
        return leaks;
    }

    /**
     * 获取所有句柄记录。
     *
     * @return 不可修改的句柄映射
     */
    public Map<String, HandleRecord> getHandles() {
        return Collections.unmodifiableMap(handles);
    }

    /**
     * 清空所有句柄记录。
     */
    public void clear() {
        handles.clear();
    }

    /**
     * 句柄类型枚举。
     */
    public enum HandleKind {
        /**
         * 文件
         */
        FILE,

        /**
         * Socket
         */
        SOCKET,

        /**
         * 线程
         */
        THREAD,

        /**
         * 锁
         */
        LOCK,

        /**
         * JDBC 连接
         */
        JDBC_CONN,

        /**
         * 未知
         */
        UNKNOWN
    }

    /**
     * 句柄记录。
     */
    @Data
    public static class HandleRecord {

        /**
         * 句柄唯一 ID
         */
        private String handleId;

        /**
         * 句柄类型
         */
        private HandleKind kind;

        /**
         * 句柄名（类名 / 文件名 / URL）
         */
        private String name;

        /**
         * 持有者线程
         */
        private String ownerThread;

        /**
         * 创建时间（毫秒）
         */
        private long createdAt;

        /**
         * 最后使用时间（毫秒）
         */
        private long lastUsedAt;

        /**
         * 是否已关闭
         */
        private boolean closed;

        /**
         * 句柄存活时长（毫秒）
         *
         * @return 存活时长
         */
        public long age() {
            return System.currentTimeMillis() - createdAt;
        }
    }
}
