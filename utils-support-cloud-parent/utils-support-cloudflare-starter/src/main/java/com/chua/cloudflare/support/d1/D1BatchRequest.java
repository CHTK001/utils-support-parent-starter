package com.chua.cloudflare.support.d1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
* D1 批量查询请求（多条 SQL 一次 HTTP 调用）。
*
* @author CH
* @since 4.0.0.42
* @param statements 对账单
* @param sequential sequential
* @return d1batch请求的结果
 */
public record D1BatchRequest(List<D1Statement> statements, Boolean sequential) {

    /**
    * 构造批量请求。
    *
    * @param databaseId 数据库 标识（可空，使用默认）
    * @param statements 多条语句
    * @return 请求对象
    */
    public static D1BatchRequest of(String databaseId, List<D1Statement> statements) {
        return new D1BatchRequest(statements, null);
    }

    /**
    * 序列化为 D1 API 请求体。
    *
    * <p>{@code {"batch": [{"sql":..., "params":...}, ...]}}</p>
    *
    * @return JSON 节点
    */
    public Object toJson() {
        var list = new ArrayList<Object>(statements.size());
        for (var s : statements) {
            list.add(s.toJson());
        }
        return java.util.Map.of("batch", list);
    }
}
