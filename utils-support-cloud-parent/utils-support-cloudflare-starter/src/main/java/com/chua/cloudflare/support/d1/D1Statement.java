package com.chua.cloudflare.support.d1;

/**
 * 单条 SQL 语句 + 参数。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class D1Statement {

    /**
     * SQL 语句
     */
    private final String sql;

    /**
     * 参数
     */
    private final D1SqlParameter params;

    public D1Statement(String sql, D1SqlParameter params) {
        this.sql = sql;
        this.params = params == null ? D1SqlParameter.ofPositional(new Object[0]) : params;
    }

    /**
     * 用位置参数构造。
     *
     * @param sql    SQL
     * @param params 参数
     * @return D1Statement
     */
    public static D1Statement of(String sql, Object... params) {
        return new D1Statement(sql, D1SqlParameter.ofPositional(params));
    }

    /**
     * 序列化为 D1 API 接受的 JSON 节点。
     *
     * <p>{@code {"sql": "...", "params": [...] | {...}}</p>
     *
     * @return JSON 节点（Map 形式）
     */
    public java.util.Map<String, Object> toJson() {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("sql", sql);
        map.put("params", params.toJson());
        return map;
    }

    public String getSql() {
        return sql;
    }

    public D1SqlParameter getParams() {
        return params;
    }
}