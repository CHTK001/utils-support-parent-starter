package com.chua.sqlite.support.engine;

import com.chua.common.support.utils.NativeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * SQLite update_hook 原生连接封装。
 *
 * <p>通过 Java FFM（Project Panama）绑定 {@code sqlite3_hook.dll} / {@code libsqlite3_hook.so}。
 * 内部维护一条 drain 守护线程，在 OS 层面阻塞等待 pipe 信号（Windows:
 * WaitForSingleObject，Linux/macOS: select），C 侧 hook 回调写入 pipe 后立即唤醒，
 * 无 CPU 轮询开销。</p>
 *
 * <p>写操作通过 {@link #exec(String)} 执行，变更事件通过 {@link #changes()} 响应式订阅。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class SqliteHookConnection implements AutoCloseable {

    /** 共享 Arena，用于字符串分配和符号查找 */
    private static final Arena SHARED_ARENA = Arena.ofShared();
    /** 原生链接器 */
    private static final Linker LINKER = Linker.nativeLinker();
    /** 符号查找表 */
    private static volatile SymbolLookup SYM_LOOKUP;

    /** FFM MethodHandle 缓存 */
    private static volatile MethodHandle HOOK_OPEN_HANDLE;
    private static volatile MethodHandle HOOK_WAIT_HANDLE;
    private static volatile MethodHandle HOOK_EXEC_HANDLE;
    private static volatile MethodHandle HOOK_FREE_HANDLE;
    private static volatile MethodHandle HOOK_PIPE_CLOSE_HANDLE;
    private static volatile MethodHandle HOOK_CLOSE_HANDLE;
    /** 原生库是否已加载（含失败标记，避免重复尝试） */
    private static volatile boolean LIBRARY_RESOLVED = false;
    /** 原生库是否加载成功 */
    private static volatile boolean LIBRARY_OK = false;

    /** hook_open 返回的 native 句柄（MemorySegment，代表 void*） */
    private final MemorySegment handle;
    /** 事件发射器（replay，支持多订阅者，缓冲区上限 1024） */
    private final reactor.core.publisher.Sinks.Many<SqliteChangeEvent> sink;
    /** drain 守护线程 */
    private final Thread drainThread;

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
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(dbPath, StandardCharsets.UTF_8);
            MemorySegment h = (MemorySegment) HOOK_OPEN_HANDLE.invoke(pathSeg);
            if (h == null || h.equals(MemorySegment.NULL)) {
                return null;
            }
            return new SqliteHookConnection(h);
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private SqliteHookConnection(MemorySegment handle) {
        this.handle = handle;
        /* multicast：多订阅者共享同一 drain 线程，backpressureBuffer 上限 1024 */
        @SuppressWarnings("rawtypes")
        reactor.core.publisher.Sinks.Many<?> rawSink = Sinks.many().multicast().onBackpressureBuffer(1024);
        this.sink = (reactor.core.publisher.Sinks.Many<SqliteChangeEvent>) rawSink;
        /* drain 守护线程：阻塞在 hook_wait，有事件时写入 sink */
        this.drainThread = new Thread(this::drainLoop, "sqlite-hook-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    /* ==================== FFM 绑定辅助 ==================== */

    private static boolean loadLibrary() {
        if (LIBRARY_RESOLVED) return LIBRARY_OK;
        synchronized (SqliteHookConnection.class) {
            if (LIBRARY_RESOLVED) return LIBRARY_OK;
            try {
                NativeUtils.load("sqlite3_hook", null);
                SYM_LOOKUP = SymbolLookup.loaderLookup();
                bindFunctions();
                LIBRARY_OK = true;
                LIBRARY_RESOLVED = true;
                return true;
            } catch (Throwable e) {
                LIBRARY_RESOLVED = true;
                return false;
            }
        }
    }

    private static void bindFunctions() {
        HOOK_OPEN_HANDLE       = bind("hook_open",       FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        HOOK_WAIT_HANDLE       = bind("hook_wait",       FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        HOOK_EXEC_HANDLE       = bind("hook_exec",       FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        HOOK_FREE_HANDLE       = bind("hook_free",       FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        HOOK_PIPE_CLOSE_HANDLE = bind("hook_pipe_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        HOOK_CLOSE_HANDLE      = bind("hook_close",      FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
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
     * 从而被 {@link #changes()} 捕获。</p>
     *
     * @param sql UTF-8 编码的 SQL 语句
     * @return SQLite 错误码（0 = SQLITE_OK 成功）
     */
    public int exec(String sql) {
        try (var arena = Arena.ofConfined()) {
            MemorySegment sqlSeg = arena.allocateFrom(sql, StandardCharsets.UTF_8);
            return (int) HOOK_EXEC_HANDLE.invoke(handle, sqlSeg);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 变更事件响应式流。
     *
     * <p>订阅时自动启动 drain 线程（若尚未运行），阻塞等待 C 侧 hook 回调写入 pipe，
     * 事件以 replay 模式推送给所有订阅者。</p>
     *
     * @return 变更事件 Flux
     */
    public Flux<SqliteChangeEvent> changes() {
        return sink.asFlux();
    }

    /**
     * 关闭连接，注销 update_hook，关闭 pipe，释放所有资源。
     */
    @Override
    public void close() {
        sink.tryEmitComplete();
        drainThread.interrupt();
        try { HOOK_PIPE_CLOSE_HANDLE.invoke(handle); } catch (Throwable ignored) {}
        try { HOOK_CLOSE_HANDLE.invoke(handle); } catch (Throwable ignored) {}
    }

    public boolean isOpen() {
        return handle != null && !handle.equals(MemorySegment.NULL);
    }

    /* ==================== drain 循环（阻塞等待） ==================== */

    /**
     * drain 守护线程主循环。
     *
     * <p>策略：
     * <ol>
     *   <li>ring buffer 有事件时立即 drain（poll + tryEmitNext），无订阅者时自动跳过</li>
     *   <li>ring buffer 为空时调用 hook_wait(500ms) 阻塞等待，避免 busy loop</li>
     * </ol>
     * </p>
     */
    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try (var arena = Arena.ofConfined()) {
                    MemorySegment jsonSeg = (MemorySegment) HOOK_WAIT_HANDLE.invoke(handle, 500);
                    if (jsonSeg != null && !jsonSeg.equals(MemorySegment.NULL)) {
                        String json = jsonSeg.getString(0, StandardCharsets.UTF_8);
                        SqliteChangeEvent event = parseEvent(json);
                        if (event != null) {
                            sink.tryEmitNext(event);
                        }
                        HOOK_FREE_HANDLE.invoke(jsonSeg);
                    }
                    /* 一次性排空所有累积事件 */
                    drainBuffer();
                } catch (Throwable e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(100);
                    }
                }
            }
        } finally {
            sink.tryEmitComplete();
        }
    }

    /**
     * 排空 ring buffer 中所有累积事件，避免事件丢失。
     */
    private void drainBuffer() {
        try (var arena = Arena.ofConfined()) {
            while (true) {
                MemorySegment jsonSeg = (MemorySegment) HOOK_WAIT_HANDLE.invoke(handle, 0);
                if (jsonSeg == null || jsonSeg.equals(MemorySegment.NULL)) break;
                String json = jsonSeg.getString(0, StandardCharsets.UTF_8);
                SqliteChangeEvent event = parseEvent(json);
                if (event != null) sink.tryEmitNext(event);
                try { HOOK_FREE_HANDLE.invoke(jsonSeg); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /* ==================== JSON 解析 ==================== */

    static SqliteChangeEvent parseEvent(String json) {
        if (json == null || json.isEmpty()) return null;
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
        try { return Long.parseLong(json.substring(ci + 1, end)); }
        catch (NumberFormatException e) { return 0L; }
    }
}
