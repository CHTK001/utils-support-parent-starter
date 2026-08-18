package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 指标点
 * <p>
 * 描述一条 PromQL 查询返回的序列, 包含标签维度与采样值。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrometheusMetric {

    /**
     * 标签维度(包含 __name__)
     */
    @Builder.Default
    private Map<String, String> metric = new LinkedHashMap<>();

    /**
     * 即时值(vector)
     */
    private Double value;

    /**
     * 序列值(matrix, 有序时间戳+值)
     */
    @Builder.Default
    /** Values */
    private List<Sample> values = new ArrayList<>();

    /**
     * 获取指标名(__name__)
     *
     * @return 指标名, 无则空串
     */
    public String getName() {
        return metric == null ? "" : metric.getOrDefault("__name__", "");
    }

    /**
     * 采样点
     *
     * @param timestamp 时间戳(秒)
     * @param value     值
     * @author CH
     * @since 4.0.0.42
     */
    public record Sample(long timestamp, double value) {
    }
}