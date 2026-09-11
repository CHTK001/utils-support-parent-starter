package com.chua.deeplearning.support.weka.result;

import lombok.Getter;

/**
 * 特征重要性。
 *
 * <p>基于随机森林的平均不纯度下降（Average Impurity Decrease）计算，
 * 并提供按最大值归一化后的相对重要性与排名。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class FeatureImportance {

    /** 特征名 */
    private final String feature;

    /** 特征重要性（平均不纯度下降原始值） */
    private final double importance;

    /** 归一化重要性（0.0 ~ 1.0，除以最大重要性） */
    private final double normalizedImportance;

    /** 排名（1 表示最重要） */
    private final int rank;

    /**
     * 构造未排名的特征重要性。
     *
     * @param feature              特征名
     * @param importance           原始重要性
     * @param normalizedImportance 归一化重要性
     */
    public FeatureImportance(String feature, double importance, double normalizedImportance) {
        this(feature, importance, normalizedImportance, 0);
    }

    /**
     * 构造完整特征重要性。
     *
     * @param feature              特征名
     * @param importance           原始重要性
     * @param normalizedImportance 归一化重要性
     * @param rank                 排名（1 表示最重要）
     */
    public FeatureImportance(String feature, double importance, double normalizedImportance, int rank) {
        this.feature = feature;
        this.importance = importance;
        this.normalizedImportance = normalizedImportance;
        this.rank = rank;
    }

    @Override
    public String toString() {
        return feature + ": " + String.format("%.6f", importance) + " (rank=" + rank + ")";
    }
}
