package com.chua.deeplearning.support.weka.result;

import lombok.Getter;

import java.util.Map;

/**
 * 分类预测结果。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class ClassificationResult {

    /** 预测标签 */
    private final String label;

    /** 预测类别索引（与训练数据标签取值顺序一致） */
    private final int classIndex;

    /** 预测类别置信度（0.0 ~ 1.0） */
    private final double confidence;

    /** 各类别概率分布 */
    private final Map<String, Double> probabilities;

    /**
     * 构造分类预测结果。
     *
     * @param label         预测标签
     * @param classIndex    类别索引
     * @param confidence    置信度
     * @param probabilities 概率分布
     */
    public ClassificationResult(String label, int classIndex, double confidence, Map<String, Double> probabilities) {
        this.label = label;
        this.classIndex = classIndex;
        this.confidence = confidence;
        this.probabilities = probabilities;
    }

    @Override
    public String toString() {
        return label + "(" + String.format("%.4f", confidence) + ")";
    }
}
