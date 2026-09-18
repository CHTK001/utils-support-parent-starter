package com.chua.deeplearning.support.dl4j.infer;

import lombok.Data;

/**
* 图片分类预测结果。
*
* <p>包含预测的类别名称和置信度概率。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class ClassPrediction {

    /**
    * 预测类别名称。
    */
    private final String className;

    /**
    * 预测置信度（0.0 ~ 1.0）。
    */
    private final float probability;

    /**
    * 构造分类预测结果。
    *
    * @param className   预测类别
    * @param probability 置信度
    */
    public ClassPrediction(String className, float probability) {
        this.className = className;
        this.probability = probability;
    }

    @Override
    public String toString() {
        return className + "(" + String.format("%.4f", probability) + ")";
    }
}
