package com.chua.sqlite.support.engine;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * sqlite 更新_hook CDC 封装。
 *
 * <p>通过 Java FFM（Project Panama）绑定 native 库，
 * 提供同步 CDC 接口：{@link #exec(String)} 执行 SQL，
 * 变更事件通过 {@link Consumer} 回调推送。支持批量消费
 * {@link #drain()} 获取当前缓冲的全部事件。</p>
 *
 * <pre>{@code
 * try (SqliteHookConnection cdc = SqliteHookConnection.open("mydb.sqlite")) {
 *     cdc.onEvent(e -> System.out.println(e.getType() + " on " + e.getTable()));
 *     cdc.exec("INSERT INTO users(name) VALUES('Alice')");
 *
 *     // 批量消费缓冲事件
 *     List<SqliteChangeEvent> events = cdc.drain();
 * }
 * }</pre>ent> events = cdc.drain();
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @param handle 处理
 */
public final class SqliteHookConnection implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker(); // 链接
    private static volatile SymbolLookup SYM_LOOKUP; // SYM_LOOKUP
    private static final int BUFFER_SIZE = 512; // 缓冲大小

    private static volatile MethodHandle HOOK_OPEN_HANDLE; // hook打开处理
    private static volatile MethodHandle HOOK_POLL_HANDLE; // hookpoll处理
    private static volatile MethodHandle HOOK_EXEC_HANDLE; // hook执行处理
    private static volatile MethodHandle HOOK_CLOSE_HANDLE; // hook关闭处理
    private static volatile boolean LIBRARY_RESOLVED = false; // 图书馆resolved
    private static volatile boolean LIBRARY_OK = false; // 图书馆ok

    private final MemorySegment handle; // 处理
    private Consumer<SqliteChangeEvent> eventConsumer; // 事件consumer
    private final List<SqliteChangeEvent> eventBuffer = new ArrayList<>(); // 事件缓冲
/**
 * 打开。
 * @param dbPath db路径
 * @return 打开的结果
 * @param handle 处理
 */

    public static SqliteHookConnection open(String dbPath) {
        if (!loadLibrary()) {
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) HOOK_OPEN_HANDLE.invoke(arena.allocateFrom(dbPath, StandardCharsets.UTF_8));
            return (h != null && !h.equals(MemorySegment.NULL)) ? new SqliteHookConnection(h) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 构造方法，创建 SqliteHook连接 实例。
     *
     * @param handle 处理，不允许为 null
     */
    private SqliteHookConnection(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * 设置变更事件回调。每次 {@link #exec(String)} 后自动触发。
     * @param consumer consumer
     */
    public void onEvent(Consumer<SqliteChangeEvent> consumer) {
        this.eventConsumer = consumer;
    }

    /**
     * 执行 SQL，变更事件通过回调推送并缓存。
     *
     * @return SQLite 返回 编码，0 表示成功
     * @param sql SQL
     */
    public int exec(String sql) {
        try (var arena = Arena.ofConfined()) {
            int rc = (int) HOOK_EXEC_HANDLE.invoke(handle, arena.allocateFrom(sql, StandardCharsets.UTF_8));
            drainBufferSync();
            return rc;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 同步排空事件缓冲区，返回所有已缓存但未消费的事件。
     * 调用后缓冲区清空，事件已推送给回调。
     *
     * @return 本次排空的变更事件列表（缓冲区快照）
     */
    public List<SqliteChangeEvent> drain() {
        drainBufferSync();
        List<SqliteChangeEvent> snapshot = new ArrayList<>(eventBuffer);
        eventBuffer.clear();
        return snapshot;
    }

    @Override
    public void close() {
        /**
         * 是否打开。
         * @return 是否打开的结果
         */
        try {
            HOOK_CLOSE_HANDLE.invoke(handle);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 是否打开。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isOpen() {
        /**
         * drain缓冲同步。
         */
        return handle != null && !handle.equals(MemorySegment.NULL);
    }

    /**
     * drain缓冲区Sync。
     */
    private void drainBufferSync() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(BUFFER_SIZE);
            while (true) {
                int len = (int) HOOK_POLL_HANDLE.invoke(handle, buf, BUFFER_SIZE);
                if (len <= 0) {
                    break;
                }
                String json = buf.getString(0, StandardCharsets.UTF_8);
                SqliteChangeEvent event = parseEvent(json);
                if (event != null) {
                    eventBuffer.add(event);
                    if (eventConsumer != null) {
                        eventConsumer.accept(event);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 加载Library。
     *
     * @return 是否成功（true 表示成功）
     */
    private static boolean loadLibrary() {
        if (LIBRARY_RESOLVED) {
            return LIBRARY_OK;
        }
        synchronized (SqliteHookConnection.class) {
            if (LIBRARY_RESOLVED) {
                return LIBRARY_OK;
            }
            try {
                NativeLoader.of("sqlite3-hook")
                        .glob(NativeUtils.getLibraryFileName("sqlite3_hook"))
                        .toTarget(NativeUtils.tempRoot().resolve("sqlite3-hook").toFile().getAbsolutePath())
                        .load();
                SYM_LOOKUP = SymbolLookup.loaderLookup();
                HOOK_OPEN_HANDLE  = bind("hook_open",  FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_POLL_HANDLE  = bind("hook_poll",  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                HOOK_EXEC_HANDLE  = bind("hook_exec",  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                HOOK_CLOSE_HANDLE = bind("hook_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                LIBRARY_OK = true;
                LIBRARY_RESOLVED = true;
                return true;
            } catch (Throwable e) {
                LIBRARY_RESOLVED = true;
                return false;
            }
        }
    }

    /**
     * 绑定。
     *
     * @param name 名称，不允许为 null
     * @param desc 描述，不允许为 null
     * @return 方法处理 对象
     */
    private static MethodHandle bind(String name, FunctionDescriptor desc) {
        MemorySegment sym = SYM_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("符号未找到: " + name));
        /**
         * 解析事件。
         * @param json json
         * @return 解析事件的结果
         */
        return LINKER.downcallHandle(sym, desc);
    }

    /**
     * 解析Event。
     *
     * @param json 方法入参 json
     * @return SqliteChangeEvent 对象
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
            return null;
        /**
         * extract字符串。
         * @param json json
         * @param key 键
         * @return extract字符串的结果
         */
        }
    }

    /**
     * extract字符串。
     *
     * @param json 方法入参 json
     * @param key 键，不允许为 null
     * @return 结果字符串
     */
    private static String extractString(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) {
            return null;
        }
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) {
            return null;
        }
        int si = json.indexOf('"', ci + 1);
        if (si < 0) {
            return null;
        }
        int ei = json.indexOf('"', si + 1);
        if (ei < 0) {
            ei = json.length() - 1;
        }
        return json.substring(si + 1, ei);
    }

    /**
     * extractLong。
     *
     * @param json 方法入参 json
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private static long extractLong(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) {
            return 0L;
        }
        int ci = json.indexOf(':', ki + key.length() + 2);
        if (ci < 0) {
            return 0L;
        }
        int end = ci + 1;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        try { return Long.parseLong(json.substring(ci + 1, end)); }
        catch (NumberFormatException e) { return 0L; }
    }
}
