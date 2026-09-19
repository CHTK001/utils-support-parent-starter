package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prometheus 告警
 * <p>
 * 对应 {@code /api/v1/alerts} 返回的 alerts 元素。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrometheusAlert {

    /**
     * 告警标签
     */
    @Builder.Default
    private Map<String, String> labels = new LinkedHashMap<>(); // 标签

    /**
     * 注释
     */
    @Builder.Default
    private Map<String, String> annotations = new LinkedHashMap<>(); // 注解

    /**
     * 状态: firing / pending / inactive
     */
    private String state;

    /**
     * 告警表达式的取值(远端以字符串返回, 可能为 NaN / +Inf)
     */
    private String value;

    /**
     * 活跃起始时间(epoch 毫秒), 0 表示远端未返回
     */
    private long activeAt;

    /**
     * 告警名(从 labels.alertname 读取)
     *
     * @return 告警名
     */
    public String getAlertName() {
        return labels == null ? "" : labels.getOrDefault("alertname", "");
    }

    /**
     * 告警级别(从 labels.severity 读取)
     *
     * @return 级别, 不存在返回空串
     */
    public String getSeverity() {
        return labels == null ? "" : labels.getOrDefault("severity", "");
    }

    /**
     * 获取指定标签的值
     *
     * @param key 标签名
     * @return 标签值, 不存在返回 null
     */
    public String getLabel(String key) {
        return labels == null ? null : labels.get(key);
    }
}
