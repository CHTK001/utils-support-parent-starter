package com.chua.cloudflare.support.d1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D1 查询结果包装。
 *
 * <p>封装 Cloudflare D1 响应：
 * <ul>
 *   <li>{@code meta} — {@code last_row_id, rows_read, rows_written, duration}</li>
 *   <li>{@code rows} — SELECT 结果的二维表（列名→值）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class D1Result {

    /**
     * 元数据
     */
    private final Map<String, Object> meta;

    /**
     * 结果行（每行一个 Map：列名→值）
     */
    private final List<Map<String, Object>> rows;

    public D1Result(Map<String, Object> meta, List<Map<String, Object>> rows) {
        this.meta = meta == null ? new LinkedHashMap<>() : meta;
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    /**
     * 解析 D1 API 的 {@code result} 字段（单条查询响应）。
     *
     * <p>D1 单条响应格式：{@code {"success":true,"result":{"meta":{...},"rows":[...]}}}</p>
     * 本方法接受的是已经从 CloudflareClient.parseResult 提取的 result 子树。
     *
     * @param raw D1 result（Map 类型）
     * @return D1Result
     */
    @SuppressWarnings("unchecked")
    public static D1Result parse(Object raw) {
        if (!(raw instanceof Map)) {
            return new D1Result(new LinkedHashMap<>(), new ArrayList<>());
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Map<String, Object> meta = (Map<String, Object>) map.getOrDefault("meta", new LinkedHashMap<>());
        List<Map<String, Object>> rows = parseRows(map.get("rows"));
        return new D1Result(meta, rows);
    }

    /**
     * 解析批量响应（{@code result} 是数组）。
     *
     * @param raw batch result（List 类型）
     * @return 每条 SQL 对应的 D1Result
     */
    @SuppressWarnings("unchecked")
    public static List<D1Result> parseBatch(Object raw) {
        List<D1Result> results = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                results.add(parse(item));
            }
        }
        return results;
    }

    /**
     * 把 D1 rows（含列名数组 + 二维数组）转换为列名 Map 列表。
     *
     * <p>D1 格式：{@code "rows":[{"columns":["id","name"],"values":[[1,"Alice"],...]}]}</p>
     *
     * @param raw rows 节点
     * @return 行列表
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseRows(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (Object rowEntry : (List<Object>) raw) {
            if (!(rowEntry instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) rowEntry;
            List<String> columns = (List<String>) row.getOrDefault("columns", new ArrayList<>());
            List<Object> values = (List<Object>) row.getOrDefault("values", new ArrayList<>());
            for (Object valueRow : values) {
                if (!(valueRow instanceof List)) {
                    continue;
                }
                List<Object> valueList = (List<Object>) valueRow;
                Map<String, Object> entry = new LinkedHashMap<>();
                for (int i = 0; i < columns.size() && i < valueList.size(); i++) {
                    entry.put(columns.get(i), valueList.get(i));
                }
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * 获取元数据（含 last_row_id、rows_written 等）。
     */
    public Map<String, Object> getMeta() {
        return meta;
    }

    /**
     * 获取结果行。
     */
    public List<Map<String, Object>> getRows() {
        return rows;
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