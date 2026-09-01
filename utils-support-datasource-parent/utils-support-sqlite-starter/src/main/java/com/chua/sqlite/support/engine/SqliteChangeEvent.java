package com.chua.sqlite.support.engine;

/**
 * SQLite update_hook 变更事件，由 {@link SqliteHookConnection} 通过 FFI 捕获并推送。
 *
 * <p>每条事件包含变更类型（INSERT/UPDATE/DELETE）、表名和 rowId，对应 C 侧
 * {@code sqlite3_update_hook} 回调产生的 JSON 记录。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class SqliteChangeEvent {

    /** 变更类型：INSERT / UPDATE / DELETE */
    public enum Type {
        INSERT, UPDATE, DELETE
    }

    private final Type type;
    private final String table;
    private final long rowId;

    private SqliteChangeEvent(Type type, String table, long rowId) {
        this.type = type;
        this.table = table;
        this.rowId = rowId;
    }

    public static SqliteChangeEvent insert(String table, long rowId) {
        return new SqliteChangeEvent(Type.INSERT, table, rowId);
    }

    public static SqliteChangeEvent update(String table, long rowId) {
        return new SqliteChangeEvent(Type.UPDATE, table, rowId);
    }

    public static SqliteChangeEvent delete(String table, long rowId) {
        return new SqliteChangeEvent(Type.DELETE, table, rowId);
    }

    public Type getType() {
        return type;
    }

    public String getTable() {
        return table;
    }

    public long getRowId() {
        return rowId;
    }

    @Override
    public String toString() {
        return "SqliteChangeEvent{type=" + type + ", table='" + table + "', rowId=" + rowId + "}";
    }
}
