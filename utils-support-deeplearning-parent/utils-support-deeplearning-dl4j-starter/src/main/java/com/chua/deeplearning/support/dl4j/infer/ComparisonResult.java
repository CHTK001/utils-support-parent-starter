package com.chua.deeplearning.support.dl4j.infer;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

/**
 * 图片 1:1 比对结果。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@AllArgsConstructor
public class ComparisonResult implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
     * 相似度分数（0.0 ~ 1.0，1.0 表示完全相同）。
     */
    private final float similarity;

    /**
     * 第一张图片的特征向量。
     */
    private final float[] feature1;

    /**
     * 第二张图片的特征向量。
     */
    private final float[] feature2;

    /**
     * 是否匹配（相似度 &gt; 0.5）。
     */
    private final boolean match;
}
