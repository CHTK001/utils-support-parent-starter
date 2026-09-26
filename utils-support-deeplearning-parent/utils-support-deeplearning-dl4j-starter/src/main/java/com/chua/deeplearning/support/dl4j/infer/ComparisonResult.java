package com.chua.deeplearning.support.dl4j.infer;

import java.io.Serializable;
import java.util.Objects;

/**
 * 图片 1:1 比对结果。
 *
 * @param similarity 相似度分数（0.0 ~ 1.0，1.0
 *                   表示完全相同）。
 * @param feature1 第一张图片的特征向量。
 * @param feature2 第二张图片的特征向量。
 * @param match 是否匹配（相似度 &gt; 0.5）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public record ComparisonResult(
        float similarity,
        float[] feature1,
        float[] feature2,
        boolean match
) implements Serializable {
    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
     * 规范构造器：两张图片的特征向量做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。
     * 唯一构造点 {@code ResNet50InferenceModel#compare} 传入的是
     * {@code extractFeature} 刚产出的数组、构造后不再被改动，因此拷贝不改变行为。</p>
     *
     * @param feature1 第一张图片的特征向量
     * @param feature2 第二张图片的特征向量
     */
    public ComparisonResult {
        feature1 = Objects.requireNonNull(feature1, "feature1 不能为 null").clone();
        feature2 = Objects.requireNonNull(feature2, "feature2 不能为 null").clone();
    }

    /**
     * 访问器覆写：返回内部特征向量的副本。
     *
     * <p>value class 前置条件——数组组件必须深不可变。</p>
     *
     * @return 第一张图片的特征向量副本
     */
    @Override
    public float[] feature1() {
        return feature1.clone();
    }

    /**
     * 访问器覆写：返回内部特征向量的副本。
     *
     * <p>value class 前置条件——数组组件必须深不可变。</p>
     *
     * @return 第二张图片的特征向量副本
     */
    @Override
    public float[] feature2() {
        return feature2.clone();
    }
}
