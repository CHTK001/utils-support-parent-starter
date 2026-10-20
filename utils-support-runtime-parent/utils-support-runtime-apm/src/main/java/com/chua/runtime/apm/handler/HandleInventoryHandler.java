package com.chua.runtime.apm.handler;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 句柄清单处理器 — 识别并保存当前进程的<b>全部句柄</b>与分配堆栈。
 *
 * <p>与 {@link HandleLeakHandler} 只记录超时未关闭泄漏不同，该处理器提供
 * 完整句柄清单（文件 / Socket / 线程 / 锁 / JDBC 连接 / 临时文件），
 * 每次快照同时捕获句柄分配时的调用栈，供前端「句柄检测」页面查看。</p>
 *
 * <p>能力：</p>
 * <ul>
 *   <li>字节码插桩：文件流 / Socket / ServerSocket / 线程 创建与关闭</li>
 *   <li>运行时枚举：当前进程 FD 近似（unix 目录）、全部线程、内存池</li>
 *   <li>分配堆栈：句柄创建时捕获调用栈</li>
 * </ul>
 *
 * @author CH
 * @since 4.1.0
 */
public class HandleInventoryHandler implements Plugin, RuntimeSpy.Interceptor {

    private static final Logger LOG = Logger.getLogger(HandleInventoryHandler.class.getName());

    /**
     * 处理器名称。
     */
    private static final String HANDLER_NAME = "handle-inventory-handler";

    /**
     * 处理器版本。
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 启用配置键。
     */
    private static final String PROP_ENABLED = "handle.inventory.enabled";

    /**
     * 默认启用。
     */
    private static final String DEFAULT_ENABLED = "true";

    /**
     * 句柄最大保留条数（防内存膨胀）。
     */
    private static final int MAX_HANDLES = 10000;

    /**
     * 文件流内部名。
     */
    private static final String FILE_INPUT_STREAM = "java/io/FileInputStream";

    /**
     * 文件输出流内部名。
     */
    private static final String FILE_OUTPUT_STREAM = "java/io/FileOutputStream";

    /**
     * Socket 内部名。
     */
    private static final String SOCKET = "java/net/Socket";

    /**
     * ServerSocket 内部名。
     */
    private static final String SERVER_SOCKET = "java/net/ServerSocket";

    /**
     * Thread 内部名。
     */
    private static final String THREAD = "java/lang/Thread";

    /**
     * 句柄映射（handleId -> record）。
     */
    private final Map<String, HandleRecord> handles = new ConcurrentHashMap<>();

    /**
     * handleId 生成器。
     */
    private final AtomicLong idGenerator = new AtomicLong(0);

    /**
     * 是否启用。
     */
    private boolean enabled;

    /**
     * 是否已启动。
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 无参构造。
     */
    public HandleInventoryHandler() {
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
        this.enabled = DEFAULT_ENABLED.equals(context.getProperty(PROP_ENABLED, DEFAULT_ENABLED));
        LOG.log(Level.INFO, String.format("HandleInventoryHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 文件/Socket/线程 打开关闭插桩
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "<init>", "()V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "<init>", "(Ljava/lang/String;)V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "<init>", "(Ljava/io/File;)V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "<init>", "()V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "<init>", "(Ljava/lang/String;)V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "<init>", "(Ljava/io/File;)V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(FILE_INPUT_STREAM, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT_STREAM, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(SOCKET, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(SERVER_SOCKET, "close", "()V", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(THREAD, "start", "()V", InterceptPoint.ENTRY, this);
        LOG.log(Level.INFO, "HandleInventoryHandler 启动完成");
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "HandleInventoryHandler 停止");
    }

    @Override
    public String status() {
        return String.format("HandleInventoryHandler[enabled=%s, handles=%d]", enabled, handles.size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    @Override
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        String methodName = ctx.getMethodName();
        String className = ctx.getClassName();
        if ("<init>".equals(methodName) || ("start".equals(methodName) && THREAD.equals(className))) {
            recordOpen(className, methodName);
        } else if ("close".equals(methodName)) {
            recordClose(className);
        }
    }

    /**
     * 记录句柄打开。
     *
     * @param className  类内部名
     * @param methodName 方法名
     */
    private void recordOpen(String className, String methodName) {
        String handleId = "hi-" + idGenerator.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        HandleRecord record = new HandleRecord();
        record.setHandleId(handleId);
        record.setKind(inferKind(className));
        record.setName(className);
        record.setOwnerThread(Thread.currentThread().getName());
        record.setCreatedAt(System.currentTimeMillis());
        record.setLastUsedAt(System.currentTimeMillis());
        record.setStackTrace(captureStack());
        record.setClosed(false);
        handles.put(handleId, record);
        if (handles.size() > MAX_HANDLES) {
            // 移除最老的未关闭句柄
            HandleRecord oldest = null;
            for (HandleRecord r : handles.values()) {
                if (oldest == null || r.getCreatedAt() < oldest.getCreatedAt()) {
                    oldest = r;
                }
            }
            if (oldest != null) {
                handles.remove(oldest.getHandleId());
            }
        }
        LOG.log(Level.FINE, String.format("[HandleInventory] OPEN: kind=%s id=%s thread=%s", record.getKind(), handleId, record.getOwnerThread()));
    }

    /**
     * 记录句柄关闭（移除最早同类型句柄）。
     *
     * @param className 类内部名
     */
    private void recordClose(String className) {
        String threadName = Thread.currentThread().getName();
        HandleRecord oldest = null;
        for (HandleRecord r : handles.values()) {
            if (!r.isClosed() && threadName.equals(r.getOwnerThread()) && inferKind(className) == r.getKind()) {
                if (oldest == null || r.getCreatedAt() < oldest.getCreatedAt()) {
                    oldest = r;
                }
            }
        }
        if (oldest != null) {
            oldest.setClosed(true);
            oldest.setLastUsedAt(System.currentTimeMillis());
            handles.remove(oldest.getHandleId());
        }
    }

    /**
     * 生成全量句柄快照（含 FD 枚举 + 线程 + 最近句柄）。
     *
     * @param limit 每个分类最大条数
     * @return 快照 Map
     */
    public Map<String, Object> snapshot(int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ts", System.currentTimeMillis());
        result.put("trackedHandles", new ArrayList<>(handles.values()));
        result.put("fileDescriptors", enumerateFileDescriptors(limit));
        result.put("threads", snapshotThreads(limit));
        result.put("summary", summary());
        return result;
    }

    /**
     * 句柄分类汇总。
     *
     * @return 各分类计数
     */
    public Map<String, Long> summary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (HandleRecord r : handles.values()) {
            String key = r.getKind() == null ? "UNKNOWN" : r.getKind().name();
            summary.merge(key, 1L, Long::sum);
        }
        return summary;
    }

    /**
     * 获取全部句柄。
     *
     * @return 句柄列表
     */
    public List<HandleRecord> getHandles() {
        return Collections.unmodifiableList(new ArrayList<>(handles.values()));
    }

    /**
     * 枚举当前进程 FD（Unix 下读取 /proc/self/fd；其他平台返回线程近似）。
     *
     * @param limit 条数上限
     * @return FD 列表
     */
    private List<Map<String, Object>> enumerateFileDescriptors(int limit) {
        List<Map<String, Object>> fds = new ArrayList<>();
        try {
            Path fdDir = Path.of("/proc/self/fd");
            if (Files.isDirectory(fdDir)) {
                try (var stream = Files.list(fdDir)) {
                    stream.limit(limit > 0 ? limit : 1000).forEach(p -> {
                        Map<String, Object> fd = new LinkedHashMap<>();
                        fd.put("fd", p.getFileName().toString());
                        try {
                            fd.put("target", Files.readSymbolicLink(p).toString());
                        } catch (Exception e) {
                            fd.put("target", "");
                        }
                        fds.add(fd);
                    });
                }
            }
        } catch (Exception e) {
            // 非 Linux 或不支持，忽略
        }
        return fds;
    }

    /**
     * 线程快照。
     *
     * @param limit 条数上限
     * @return 线程列表
     */
    private List<Map<String, Object>> snapshotThreads(int limit) {
        List<Map<String, Object>> threads = new ArrayList<>();
        try {
            ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
            long[] ids = tmx.getAllThreadIds();
            int count = 0;
            for (long id : ids) {
                if (limit > 0 && count >= limit) {
                    break;
                }
                ThreadInfo info = tmx.getThreadInfo(id, 8);
                if (info == null) {
                    continue;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("threadId", info.getThreadId());
                t.put("name", info.getThreadName());
                t.put("state", info.getThreadState().name());
                t.put("blockedTime", info.getBlockedTime());
                t.put("waitedTime", info.getWaitedTime());
                threads.add(t);
                count++;
            }
        } catch (Exception e) {
            // 忽略
        }
        return threads;
    }

    /**
     * 捕获当前调用栈。
     *
     * @return 调用栈数组
     */
    private StackTraceElement[] captureStack() {
        StackTraceElement[] full = Thread.currentThread().getStackTrace();
        // 去掉本类/onIntercept 等 3 层，保留业务分配栈
        int from = Math.min(3, full.length);
        int to = Math.min(32, full.length);
        StackTraceElement[] result = new StackTraceElement[to - from];
        System.arraycopy(full, from, result, 0, result.length);
        return result;
    }

    /**
     * 推断句柄类型。
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
        if (className.contains("Socket") || className.contains("ServerSocket")) {
            return HandleKind.SOCKET;
        }
        if (className.contains("Connection") || className.contains("DataSource")) {
            return HandleKind.JDBC_CONN;
        }
        if (className.contains("Thread")) {
            return HandleKind.THREAD;
        }
        return HandleKind.UNKNOWN;
    }

    /**
     * 句柄类型。
     */
    public enum HandleKind {
        FILE,
        SOCKET,
        THREAD,
        LOCK,
        JDBC_CONN,
        UNKNOWN
    }

    /**
     * 句柄记录。
     */
    @Data
    public static class HandleRecord {
        private String handleId;
        private HandleKind kind;
        private String name;
        private String ownerThread;
        private long createdAt;
        private long lastUsedAt;
        private boolean closed;
        private StackTraceElement[] stackTrace;

        /**
         * 句柄存活时长。
         *
         * @return 存活毫秒
         */
        public long age() {
            return System.currentTimeMillis() - createdAt;
        }
    }
}
