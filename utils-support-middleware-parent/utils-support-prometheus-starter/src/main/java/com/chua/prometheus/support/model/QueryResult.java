package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Prometheus 查询结果
 * <p>
 * 对应 {@code /api/v1/query} 与 {@code /api/v1/query_range} 的 data 结构,
 * 具体语义由 {@link #resultType} 决定。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    /**
     * 结果类型: vector / matrix / scalar
     */
    private String resultType;

    /**
     * 结果列表(vector/matrix 为序列, scalar 为单条值)
     */
    @Builder.Default
    private List<PrometheusMetric> result = new ArrayList<>(); // 结果

    /**
     * 是否存在数据
     *
     * @return 是否
     */
    public boolean hasData() {
        return result != null && !result.isEmpty();
    }
}
