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
     * 活跃起始时间(毫秒)
     */
    private long activeAt;

    /**
      * 告警名(从 标签.alertname 读取)
     *
     * @return 告警名
     */
    public String getAlertName() {
        return labels == null ? "" : labels.getOrDefault("alertname", "");
    }
}