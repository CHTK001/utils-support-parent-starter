package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prometheus 告警规则
 * <p>
 * 对应 {@code /api/v1/rules} 返回的 rules 元素(alerting 与 recording 两类)。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrometheusRule {

    /**
     * 规则名
     */
    private String name;

    /**
     * promql 表达式
     */
    private String query;

    /**
     * 规则类型: alerting / recording
     */
    private String type;

    /**
     * 状态: firing / pending / inactive(仅 alerting 规则返回)
     */
    private String state;

    /**
     * 所属规则组名
     */
    private String ruleGroup;

    /**
     * 规则文件
     */
    private String ruleFile;

    /**
     * 健康状态: ok / err / unknown
     */
    private String health;

    /**
     * 上次评估错误
     */
    private String lastError;

    /**
     * 告警持续时间阈值(for, 秒)
     */
    private double duration;

    /**
     * 上次评估耗时(秒)
     */
    private double evaluationTime;

    /**
     * 上次评估时间(epoch 毫秒), 0 表示远端未返回
     */
    private long lastEvaluation;

    /**
     * 规则附加标签
     */
    @Builder.Default
    private Map<String, String> labels = new LinkedHashMap<>(); // 标签

    /**
     * 规则注解
     */
    @Builder.Default
    private Map<String, String> annotations = new LinkedHashMap<>(); // 注解

    /**
     * 是否为告警型规则
     *
     * @return true 表示 type=alerting
     */
    public boolean isAlerting() {
        return "alerting".equalsIgnoreCase(type);
    }
}
