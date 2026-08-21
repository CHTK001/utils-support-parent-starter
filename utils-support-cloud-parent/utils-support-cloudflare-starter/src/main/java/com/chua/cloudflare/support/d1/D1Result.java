package com.chua.cloudflare.support.d1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D1 查询结果包装。
 *
 * <p>封装 Cloudflare D1 真实响应结构：
 * <pre>{@code
 * {
 *   "result": [
 *     {
 *       "results": [ {"col1": v1, "col2": v2}, ... ],
 *       "success": true,
 *       "meta": { "last_row_id": 1, "changes": 1, "duration": 0.3, ... }
 *     }
 *   ],
 *   "success": true
 * }
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public record D1Result(Map<String, Object> meta, List<Map<String, Object>> rows, boolean success) {

    public D1Result {
        meta = meta == null ? new LinkedHashMap<>() : meta;
        rows = rows == null ? new ArrayList<>() : rows;
    }

    /**
     * 解析单条 D1 结果元素（{@code {results, success, meta}}）。
     *
     * @param raw 单个 result 元素（Map 类型）
     * @return D1Result
     */
    @SuppressWarnings("unchecked")
    public static D1Result parse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new D1Result(Map.of(), List.of(), false);
        }
        Object metaRaw = map.get("meta");
        var meta = metaRaw instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<String, Object>();
        var success = Boolean.TRUE.equals(map.get("success"));
        var rows = parseResults(map.get("results"));
        return new D1Result(meta, rows, success);
    }

    /**
     * 解析批量响应（{@code result} 是数组，每元素一个结果）。
     *
     * @param raw batch result（List 类型）
     * @return 每条 SQL 对应的 D1Result
     */
    public static List<D1Result> parseBatch(Object raw) {
        var results = new ArrayList<D1Result>();
        if (raw instanceof List<?> list) {
            for (var item : list) {
                results.add(parse(item));
            }
        } else if (raw instanceof Map<?, ?>) {
            results.add(parse(raw));
        }
        return results;
    }

    /**
     * 把 D1 {@code results} 数组（每行一个对象）转换为行列表。
     *
     * @param raw results 节点（List&lt;Map&gt;）
     * @return 行列表
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseResults(Object raw) {
        var out = new ArrayList<Map<String, Object>>();
        if (raw instanceof List<?> list) {
            for (var row : list) {
                if (row instanceof Map<?, ?>) {
                    out.add((Map<String, Object>) row);
                }
            }
        }
        return out;
    }

    /**
     * 获取 last_row_id（如适用）。
     */
    public Object getLastRowId() {
        return meta.get("last_row_id");
    }

    /**
     * 获取受影响行数。
     */
    public Object getChanges() {
        return meta.get("changes");
    }
}