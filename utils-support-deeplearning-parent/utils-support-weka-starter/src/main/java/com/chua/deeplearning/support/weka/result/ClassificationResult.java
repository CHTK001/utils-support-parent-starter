package com.chua.deeplearning.support.weka.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    /**
     * 规范构造器：概率分布映射做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。这里按 {@link LinkedHashMap} 做不可变
     * 包装而<b>不</b>用 {@link Map#copyOf(Map)}：概率分布是按训练标签取值顺序写入的，
     * {@code copyOf} 不保证迭代顺序，会让展示出来的类别顺序变成不确定。</p>
     *
     * @param probabilities 各类别概率分布
     */
    public ClassificationResult {
        probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(probabilities, "probabilities 不能为 null")));
    }

    @Override
    public String toString() {
        return label + "(" + String.format("%.4f", confidence) + ")";
    }
}
