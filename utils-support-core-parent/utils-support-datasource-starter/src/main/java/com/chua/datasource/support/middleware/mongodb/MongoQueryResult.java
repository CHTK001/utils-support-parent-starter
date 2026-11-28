package com.chua.datasource.support.middleware.mongodb;

import java.util.List;
import java.util.Map;

/**
 * 一句只读 shell 查询的执行结果。
 *
 * @param rows      行数据，键 → 归一化后的值（ObjectId/日期/二进制已转文本）
 * @param limited   结果是否被行数上限截断
 * @param totalRows 截断前口径的总行数；拿不到时等于下发行数
 * @author CH
 * @since 4.0.0.51
 */
public record MongoQueryResult(List<Map<String, Object>> rows, boolean limited, Integer totalRows) {

    /**
     * 防御性拷贝。
     */
    public MongoQueryResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
