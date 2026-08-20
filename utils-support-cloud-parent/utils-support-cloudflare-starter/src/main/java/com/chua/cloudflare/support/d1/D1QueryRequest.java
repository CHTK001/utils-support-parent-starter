package com.chua.cloudflare.support.d1;

import java.util.List;

/**
 * D1 单条查询请求。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class D1QueryRequest {

    /**
     * SQL 语句（含 params）
     */
    private final D1Statement statement;

    /**
     * 是否返回元数据（默认 true）
     */
    private final boolean returnMetadata = true;

    public D1QueryRequest(D1Statement statement) {
        this.statement = statement;
    }

    /**
     * 构造单条查询请求。
     *
     * @param databaseId 数据库 ID（可空）
     * @param statement  单条语句
     * @return 请求对象
     */
    public static D1QueryRequest single(String databaseId, D1Statement statement) {
        return new D1QueryRequest(statement);
    }

    /**
     * 序列化为 D1 API 请求体。
     *
     * <p>{@code {"sql": "...", "params": [...], "return_metadata": true}}</p>
     */
    public Object toJson() {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.putAll(statement.toJson());
        map.put("return_metadata", returnMetadata);
        return map;
    }

    public D1Statement getStatement() {
        return statement;
    }

    public boolean isReturnMetadata() {
        return returnMetadata;
    }

    /**
     * 占位，供 D1Engine 内部使用，不参与序列化。
     */
    public List<Object> __placeholder() {
        return java.util.Collections.emptyList();
    }
}