package com.chua.deeplearning.support.weka.result;

/**
 * 特征重要性。
 *
 * <p>基于随机森林的平均不纯度下降（Average Impurity Decrease）计算，
 * 并提供按最大值归一化后的相对重要性与排名。</p>
 *
 * @param feature              特征名
 * @param importance           原始重要性（平均不纯度下降）
 * @param normalizedImportance 归一化重要性（0.0 ~ 1.0，除以最大重要性）
 * @param rank                 排名（1 表示最重要）
 * @author CH
 * @since 4.0.0.42
 */
public record FeatureImportance(String feature, double importance, double normalizedImportance, int rank) {

    @Override
    public String toString() {
        return feature + ": " + String.format("%.6f", importance) + " (rank=" + rank + ")";
    }
}
