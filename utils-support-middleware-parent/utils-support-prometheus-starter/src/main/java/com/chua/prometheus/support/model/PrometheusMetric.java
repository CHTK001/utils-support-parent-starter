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
 * 描述一条 promql 查询返回的序列, 包含标签维度与采样值。
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
    private Map<String, String> metric = new LinkedHashMap<>(); // 指标

    /**
     * 即时值(vector)
     */
    private Double value;

    /**
     * 即时值采样时间(epoch 秒), 0 表示远端未返回
     */
    private long timestamp;

    /**
     * 序列值(matrix, 有序时间戳+值)
     */
    @Builder.Default
    private List<Sample> values = new ArrayList<>(); // 值

    /**
     * 获取指标名(__name__)
     *
     * @return 指标名, 无则空串
     */
    public String getName() {
        return metric == null ? "" : metric.getOrDefault("__name__", "");
    }

    /**
     * 获取指定标签的值
     *
     * @param key 标签名
     * @return 标签值, 不存在返回 null
     */
    public String getLabel(String key) {
        return metric == null ? null : metric.get(key);
    }

    /**
     * 采样点
     *
     * @param timestamp 时间戳(epoch 秒, 远端浮点秒向下取整)
     * @param value     值(可能是 NaN / 无穷大)
     * @author CH
     * @since 4.0.0.42
     */
    public record Sample(long timestamp, double value) {
    }
}
