package com.chua.sqlite.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.nativesqlite.support.SqliteHookBridge;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
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
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SqliteHookConnection implements AutoCloseable {

    private static final int BUFFER_SIZE = 512; // 缓冲大小

    /**
     * 日志记录器
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SqliteHookConnection.class);

    private final MemorySegment handle; // 处理
    private Consumer<SqliteChangeEvent> eventConsumer; // 事件consumer
    private volatile boolean closed; // 已关闭
    private final List<SqliteChangeEvent> eventBuffer = Wraps.list(); // 事件缓冲
    /**
     * 打开 CDC 钩子连接。
     *
     * @param dbPath sqlite 数据库文件路径
     * @return 连接实例；native 钩子库缺失或打开失败时返回空，失败原因见日志
     */
    public static SqliteHookConnection open(String dbPath) {
        if (!SqliteHookBridge.isAvailable()) {
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) SqliteHookBridge.hookOpen()
                    .invoke(arena.allocateFrom(dbPath, StandardCharsets.UTF_8));
            if (h == null || h.equals(MemorySegment.NULL)) {
                log.warn("SQLite CDC 钩子打开失败 dbPath={}", dbPath);
                return null;
            }
            return new SqliteHookConnection(h);
        } catch (Throwable e) {
            log.warn("SQLite CDC 钩子打开异常 dbPath={}: {}", dbPath, e.toString());
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
            int rc = (int) SqliteHookBridge.hookExec()
                    .invoke(handle, arena.allocateFrom(sql, StandardCharsets.UTF_8));
            drainBufferSync();
            return rc;
        } catch (Throwable e) {
            log.warn("SQLite CDC 钩子执行异常: {}", e.toString());
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
        List<SqliteChangeEvent> snapshot = Wraps.list(eventBuffer);
        eventBuffer.clear();
        return snapshot;
    }

    @Override
    public void close() {
        closed = true;
        try {
            SqliteHookBridge.hookClose().invoke(handle);
        } catch (Throwable e) {
            log.warn("SQLite CDC 钩子关闭失败: {}", e.toString());
        }
    }

    /**
     * 是否打开。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isOpen() {
        return !closed && handle != null && !handle.equals(MemorySegment.NULL);
    }

    /**
     * drain缓冲区Sync。
     */
    private void drainBufferSync() {
        try (var arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(BUFFER_SIZE);
            while (true) {
                int len = (int) SqliteHookBridge.hookPoll().invoke(handle, buf, BUFFER_SIZE);
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
        } catch (Throwable e) {
            log.warn("SQLite CDC 事件排空失败，缓冲区事件可能丢失: {}", e.toString());
        }
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
