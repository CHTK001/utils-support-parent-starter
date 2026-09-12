package com.chua.deeplearning.support.weka.result;

import java.util.Map;

/**
* 分类预测结果。
*
* @param label         预测标签
* @param classIndex    预测类别索引（与训练数据标签取值顺序一致）
* @param confidence    预测类别置信度（0.0 ~ 1.0）
* @param probabilities 各类别概率分布（无分布信息时为空 映射）
* @author CH
* @since 4.0.0.42
* @return classification结果的结果
 */
public record ClassificationResult(String label, int classIndex, double confidence, Map<String, Double> probabilities) {

    @Override
    public String toString() {
        return label + "(" + String.format("%.4f", confidence) + ")";
    }
}
