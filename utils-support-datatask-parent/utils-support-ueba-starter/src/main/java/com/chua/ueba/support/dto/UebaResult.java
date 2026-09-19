package com.chua.ueba.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * UEBA 综合分析结果 DTO。
 * <p>
 * {@code UebaEngine} 对单条流量事件分析的完整输出，聚合 IP 异常检测结果、
 * 行为分析画像、加权风险分数、风险等级与（可选）minimind 语义解释。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UebaResult implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
     * 风险等级枚举。
     * @author CH
     * @since 4.0.0
     */
    public enum RiskLevel {
        /**
         * 正常，无需关注
         */
        NORMAL,

        /**
         * 低风险，观察
         */
        LOW,

        /**
         * 中风险，关注
         */
        MEDIUM,

        /**
         * 高风险，告警
         */
        HIGH,

        /**
         * 严重风险，立即处置
         */
        CRITICAL
    }

    /**
     * 实体标识（IP 或 会话 标识）
     */
    private String entityId;

    /**
     * IP 异常检测结果
     */
    private IpAnomalyResult ipAnomaly;

    /**
     * 用户操作行为画像
     */
    private BehaviorProfile behavior;

    /**
     * 综合风险分数，范围 [0, 1]
     */
    private double riskScore;

    /**
     * 综合风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 语义解释（minimind 生成或模板回退），可能为空
     */
    private String explanation;

    /**
     * 分析完成时间戳（毫秒）
     */
    private long analyzedAt;
}
