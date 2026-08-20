package com.chua.cloudflare.support.d1;

import java.util.List;

/**
 * D1 批量查询请求（多条 SQL 一次 HTTP 调用）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class D1BatchRequest {

    /**
     * D1 batch API 必填字段：{@code {"sql": "...", "params": ...}}
     */
    private final List<D1Statement> statements;

    /**
     * 单库场景免填；多库场景可省略，或用 {@code sequential: true} 控制顺序
     */
    private final Boolean sequential;

    public D1BatchRequest(List<D1Statement> statements, Boolean sequential) {
        this.statements = statements;
        this.sequential = sequential;
    }

    /**
     * 构造批量请求。
     *
     * @param databaseId 数据库 ID（可空，使用默认）
     * @param statements 多条语句
     * @return 请求对象
     */
    public static D1BatchRequest of(String databaseId, List<D1Statement> statements) {
        return new D1BatchRequest(statements, null);
    }

    /**
     * 序列化为 D1 API 请求体。
     *
     * @return JSON 节点
     */
    public Object toJson() {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        java.util.List<Object> list = new java.util.ArrayList<>(statements.size());
        for (D1Statement s : statements) {
            list.add(s.toJson());
        }
        map.put("statements", list);
        if (sequential != null) {
            map.put("sequential", sequential);
        }
        return map;
    }
}