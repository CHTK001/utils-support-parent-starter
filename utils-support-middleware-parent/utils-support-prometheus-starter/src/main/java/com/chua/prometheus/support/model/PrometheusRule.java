package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prometheus 告警规则
 * <p>
 * 对应 {@code /api/v1/rules} 返回的 rules 元素(alert 类型)。
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
     * PromQL 表达式
     */
    private String query;

    /**
     * 告警级别: alert / record
     */
    private String type;

    /**
     * 状态: firing / pending / inactive
     */
    private String state;

    /**
     * 规则文件
     */
    private String ruleFile;

    /**
     * 健康状态
     */
    private String health;

    /**
     * 上次评估错误
     */
    private String lastError;

    /**
     * 持续时间(秒)
     */
    private double duration;
}