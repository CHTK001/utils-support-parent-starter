package com.chua.sqlite.support.engine;

import com.chua.common.support.utils.NativeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQLite update_hook 原生连接封装。
 *
 * <p>通过 Java FFM（Project Panama）绑定 {@code sqlite3_hook.dll} / {@code libsqlite3_hook.so}，
 * 在后台守护线程中以 {@code hook_poll} 非阻塞轮询变更事件，通过 {@link Sinks.Many}
 * 将 {@link SqliteChangeEvent} 以响应式流方式推送给订阅者。</p>
 *
 * <p>所有通过 {@link #exec(String)} 执行的 SQL 均会触发 update_hook，
 * 变更事件可通过 {@link #changes()} 订阅。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class SqliteHookConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteHookConnection.class);

    /** 共享 Arena，用于符号查找和字符串分配 */
    private static final Arena SHARED_ARENA = Arena.ofShared();
    /** 原生链接器 */
    private static final Linker LINKER = Linker.nativeLinker();
    /** 符号查找表 */
    private static volatile SymbolLookup SYM_LOOKUP;

    /** FFM MethodHandle 缓存 */
    private static volatile MethodHandle HOOK_OPEN_HANDLE;
    private static volatile MethodHandle HOOK_POLL_HANDLE;
    private static volatile MethodHandle HOOK_EXEC_HANDLE;
    private static volatile MethodHandle HOOK_FREE_HANDLE;
    private static volatile MethodHandle HOOK_CLOSE_HANDLE;
    /** 原生库是否已加载 */
    private static volatile boolean LIBRARY_LOADED = false;

    /** hook_open 返回的不透明句柄（MemorySegment，代表 native void*） */
    private final MemorySegment handle;
    /** 事件发射器（多订阅者，安全释放） */
    private final Sinks.Many<SqliteChangeEvent> sink;
    /** 轮询守护线程 */
    private final Thread pollThread;
    /** 轮询间隔（毫秒） */
    private static final int POLL_INTERVAL_MS = 50;

    /**
     * 打开 SQLite 数据库并注册 update_hook。
     *
     * <p>若原生库尚未加载，自动尝试从 classpath 或系统路径加载 {@code sqlite3_hook} 动态库。
     * 加载失败时返回 {@code null}，调用方应降级到纯 JDBC 模式。</p>
     *
     * @param dbPath SQLite 数据库文件路径（UTF-8）
     * @return 连接实例，失败返回 {@code null}
     */
    public static SqliteHookConnection open(String dbPath) {
        if (!loadLibrary()) {
            log.warn("[sqlite-hook] 无法加载 sqlite3_hook 原生库，将降级为纯 JDBC 模式");
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(dbPath, StandardCharsets.UTF_8);
            MemorySegment h = (MemorySegment) HOOK_OPEN_HANDLE.invoke(pathSeg);
            if (h == null || h.equals(MemorySegment.NULL)) {
                log.error("[sqlite-hook] hook_open 失败: dbPath={}", dbPath);
                return null;
            }
            return new SqliteHookConnection(h);
        } catch (Throwable e) {
            log.error("[sqlite-hook] 初始化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SqliteHookConnection(MemorySegment handle) {
        this.handle = handle;
        /* REPLAY：新订阅者仍能收到历史事件（缓冲区上限 1024）*/
        @SuppressWarnings("unchecked")
        Sinks.Many<SqliteChangeEvent> s = (Sinks.Many<SqliteChangeEvent>) (Sinks.Many<?>) Sinks.many().replay().limit(1024);
        this.sink = s;
        /* 守护轮询线程 */
        this.pollThread = new Thread(this::pollLoop, "sqlite-hook-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /* ==================== FFM 绑定辅助 ==================== */

    /**
     * 加载原生库并绑定 FFM 函数句柄。线程安全（首次调用后不再执行）。
     */
    private static boolean loadLibrary() {
        if (LIBRARY_LOADED) {
            return SYM_LOOKUP != null;
        }
        synchronized (SqliteHookConnection.class) {
            if (LIBRARY_LOADED) {
                return SYM_LOOKUP != null;
            }
            try {
                NativeUtils.load("sqlite3_hook", null);
                SYM_LOOKUP = SymbolLookup.loaderLookup();
                bindFunctions();
                LIBRARY_LOADED = true;
                log.info("[sqlite-hook] 原生库 sqlite3_hook 加载成功");
                return true;
            } catch (Throwable e) {
                log.warn("[sqlite-hook] 原生库加载失败，降级为纯 JDBC 模式: {}", e.getMessage());
                LIBRARY_LOADED = true; /* 标记已尝试，避免重复失败 */
                return false;
            }
        }
    }

    private static void bindFunctions() {
        /* void* hook_open(const char*) */
        HOOK_OPEN_HANDLE = bind("hook_open",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        /* char* hook_poll(void*) → 返回 strdup 字符串，需 hook_free */
        HOOK_POLL_HANDLE = bind("hook_poll",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        /* int hook_exec(void*, const char*) */
        HOOK_EXEC_HANDLE = bind("hook_exec",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        /* void hook_free(void*) */
        HOOK_FREE_HANDLE = bind("hook_free",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        /* void hook_close(void*) */
        HOOK_CLOSE_HANDLE = bind("hook_close",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        MemorySegment sym = SYM_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("符号未找到: " + name));
        return LINKER.downcallHandle(sym, desc);
    }

    /* ==================== 公共 API ==================== */

    /**
     * 通过已注册 update_hook 的连接执行 SQL。
     *
     * <p>只有通过此方法执行的 INSERT/UPDATE/DELETE 才会触发 update_hook 回调，
     * 从而被 {@link #changes()} 捕获。SELECT 也可通过此方法执行，但建议读操作走 JDBC 以利用连接池。</p>
     *
     * @param sql UTF-8 编码的 SQL 语句
     * @return SQLite 错误码（0 = SQLITE_OK 成功）
     */
    public int exec(String sql) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment sqlSeg = arena.allocateFrom(sql, StandardCharsets.UTF_8);
            return (int) HOOK_EXEC_HANDLE.invoke(handle, sqlSeg);
        } catch (Throwable e) {
            log.error("[sqlite-hook] exec 失败: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 变更事件响应式流。
     *
     * <p>订阅此 Flux 将在订阅时自动启动轮询线程（若尚未运行），
     * 持续推送 INSERT/UPDATE/DELETE 事件直到连接关闭或订阅被取消。</p>
     *
     * <pre>{@code
     * engine.changes().subscribe(event ->
     *     log.info("变更: {} on table '{}' rowId={}", event.getType(), event.getTable(), event.getRowId()));
     * }</pre>
     *
     * @return 变更事件 Flux（replay，支持多订阅者）
     */
    public Flux<SqliteChangeEvent> changes() {
        return sink.asFlux();
    }

    /**
     * 关闭连接，注销 update_hook，释放所有资源。
     * 调用后 {@link #changes()} 将 onComplete 并停止推送。
     */
    @Override
    public void close() {
        sink.tryEmitComplete();
        pollThread.interrupt();
        try {
            HOOK_CLOSE_HANDLE.invoke(handle);
        } catch (Throwable ignored) {
        }
        log.info("[sqlite-hook] 连接已关闭");
    }

    /**
     * 是否已开启（句柄有效）。
     */
    public boolean isOpen() {
        return handle != null && !handle.equals(MemorySegment.NULL);
    }

    /* ==================== 轮询循环 ==================== */

    private void pollLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    try (var arena = Arena.ofConfined()) {
                        MemorySegment result = (MemorySegment) HOOK_POLL_HANDLE.invoke(handle);
                        if (result != null && !result.equals(MemorySegment.NULL)) {
                            String json = result.getString(0, StandardCharsets.UTF_8);
                            SqliteChangeEvent event = parseEvent(json);
                            if (event != null) {
                                sink.tryEmitNext(event);
                            }
                            /* 释放 strdup 返回的字符串 */
                            HOOK_FREE_HANDLE.invoke(result);
                        }
                    }
                } catch (Throwable e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        log.debug("[sqlite-hook] poll 异常（继续轮询）: {}", e.getMessage());
                    }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            sink.tryEmitComplete();
        }
    }

    /* ==================== JSON 解析 ==================== */

    /**
     * 将 C 侧序列化的 JSON 事件字符串解析为 {@link SqliteChangeEvent}。
     *
     * <p>JSON 格式：
     * {@code {"type":"INSERT|UPDATE|DELETE","database":"main","table":"users","rowId":42}}</p>
     *
     * @param json 事件 JSON 字符串
     * @return 解析后的事件，格式错误返回 {@code null}
     */
    static SqliteChangeEvent parseEvent(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            String type = extractString(json, "type");
            String table = extractString(json, "table");
            long rowId = extractLong(json, "rowId");
            SqliteChangeEvent.Type eventType;
            switch (type == null ? "" : type.toUpperCase()) {
                case "INSERT"  -> eventType = SqliteChangeEvent.Type.INSERT;
                case "UPDATE"  -> eventType = SqliteChangeEvent.Type.UPDATE;
                case "DELETE"  -> eventType = SqliteChangeEvent.Type.DELETE;
                default        -> eventType = SqliteChangeEvent.Type.UPDATE;
            }
            String safeTable = (table == null || table.isEmpty()) ? "(unknown)" : table;
            return switch (eventType) {
                case INSERT  -> SqliteChangeEvent.insert(safeTable, rowId);
                case UPDATE  -> SqliteChangeEvent.update(safeTable, rowId);
                case DELETE  -> SqliteChangeEvent.delete(safeTable, rowId);
            };
        } catch (Exception e) {
            log.debug("[sqlite-hook] JSON 解析失败: {} -> {}", json, e.getMessage());
            return null;
        }
    }

    private static String extractString(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return null;
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) return null;
        int si = json.indexOf('"', ci + 1);
        if (si < 0) return null;
        int ei = json.indexOf('"', si + 1);
        if (ei < 0) ei = json.length() - 1;
        return json.substring(si + 1, ei);
    }

    private static long extractLong(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return 0L;
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) return 0L;
        int end = ci + 1;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Long.parseLong(json.substring(ci + 1, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
