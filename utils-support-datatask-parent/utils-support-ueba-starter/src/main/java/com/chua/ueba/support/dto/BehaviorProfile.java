package com.chua.ueba.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户操作行为画像 DTO。
 * <p>
 * LSTM/GRU + Attention 序列模型（或规则回退）推理后输出的行为分析结果，
 * 包含预测类别、置信度、各类别概率与最近访问路径等语义信息。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorProfile implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
      * 实体标识（IP 或 会话 标识）
     */
    private String entityId;

    /**
      * 预测类别下标，范围 [0, num类)
     */
    private int predictedClass;

    /**
     * 预测类别标签，如 normal / suspicious / attack
     */
    private String label;

    /**
     * 预测置信度，范围 [0, 1]
     */
    private double confidence;

    /**
     * 各类别概率，长度等于类别数，和为 1
     */
    private float[] classProbabilities;

    /**
     * 最近访问路径（按时间倒序）
     */
    @Builder.Default
    private List<String> recentPaths = new ArrayList<>(); // recent路径

    /**
     * 行为风险分数，范围 [0, 1]，越大越危险
     */
    private double riskScore;

    /**
     * 分析模式：ML（模型推理）或 RULE（规则回退）
     */
    private String analysisMode;
}
