package com.chua.sqlite.support.engine;

/**
 * sqlite 变更事件，由 更新_hook 触发。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SqliteChangeEvent {
    /**
     * 类型枚举。
     *
     * @author CH
     * @since 4.0.0
     * @return 获取类型的结果
     * @param table table
     * @param rowId rowid
     */

    public enum Type { INSERT, UPDATE, DELETE }

    private final Type type; // 类型
    /**
     * sqlite改变事件。
     * @param type 类型
     * @param table table
     * @param rowId rowid
     * @return sqlite改变事件的结果
     */
    private final String table;
    private final long rowId; // rowid

    /**
     * sqlite改变事件。
     * @param type 类型
     * @param table table
     * @param rowId rowId
     * @return sqlite改变事件的结果
     */
    private SqliteChangeEvent(Type type, String table, long rowId) {
        this.type = type;
        this.table = table;
        /**
         * 插入。
         * @param table table
         * @param rowId rowid
         * @return 插入的结果
         */
        this.rowId = rowId;
    }

    /**
     * 插入。
     *
     * @param table 表，不允许为 null
     * @param rowId 行ID，不允许为 null
     * @return SqliteChangeEvent 对象
     */
    public static SqliteChangeEvent insert(String table, long rowId) {
        return new SqliteChangeEvent(Type.INSERT, table, rowId);
    }

    /**
     * 更新。
     *
     * @param table 表，不允许为 null
     * @param rowId 行ID，不允许为 null
     * @return SqliteChangeEvent 对象
     */
    public static SqliteChangeEvent update(String table, long rowId) {
        return new SqliteChangeEvent(Type.UPDATE, table, rowId);
    }

    /**
     * 删除。
     *
     * @param table 表，不允许为 null
     * @param rowId 行ID，不允许为 null
     * @return SqliteChangeEvent 对象
     */
    public static SqliteChangeEvent delete(String table, long rowId) {
        return new SqliteChangeEvent(Type.DELETE, table, rowId);
    }

    public Type getType() { return type; }
    /**
     * 获取table。
     * @return 获取table的结果
     */
    public String getTable() { return table; }
    /**
     * 获取rowid。
     * @return 获取rowid的结果
     */
    public long getRowId() { return rowId; }

    @Override
    public String toString() {
        return "SqliteChangeEvent{type=" + type + ", table='" + table + "', rowId=" + rowId + "}";
    }
}
