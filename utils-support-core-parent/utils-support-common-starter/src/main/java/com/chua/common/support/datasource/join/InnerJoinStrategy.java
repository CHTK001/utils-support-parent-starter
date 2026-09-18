package com.chua.common.support.datasource.join;

import com.chua.common.support.spi.annotations.Spi;

import java.util.*;

/**
* 内连接策略。
*
* @author CH
* @since 4.0.0.42
 */
@Spi(value = "inner", order = 100)
public class InnerJoinStrategy implements JoinStrategy {

    @Override
    public String name() { return "inner"; }

    @Override
    public boolean enabled() { return true; }

    @Override
    public List<Map<String, Object>> execute(JoinContext ctx) {
        Map<String, List<Map<String, Object>>> rightGrouped = groupBy(ctx.rightRows(), ctx.rightKey());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> left : ctx.leftRows()) {
            String leftVal = String.valueOf(left.get(ctx.leftKey()));
            List<Map<String, Object>> matchingRight = rightGrouped.getOrDefault(leftVal, Collections.emptyList());
            for (Map<String, Object> right : matchingRight) {
                Map<String, Object> merged = new LinkedHashMap<>();
                merged.putAll(left);
                merged.putAll(right);
                if (ctx.projectColumns() != null && !ctx.projectColumns().isEmpty()) {
                    Map<String, Object> projected = new LinkedHashMap<>();
                    for (String col : ctx.projectColumns()) {
                        if (merged.containsKey(col)) {
                            projected.put(col, merged.get(col));
                        }
                    }
                    result.add(projected);
                } else {
                    result.add(merged);
                }
            }
        }
        return result;
    }

    private static Map<String, List<Map<String, Object>>> groupBy(
            List<Map<String, Object>> rows, String key) {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String val = String.valueOf(row.get(key));
            map.computeIfAbsent(val, k -> new ArrayList<>()).add(row);
        }
        return map;
    }
}
