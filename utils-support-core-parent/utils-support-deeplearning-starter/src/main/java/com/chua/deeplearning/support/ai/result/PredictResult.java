package com.chua.deeplearning.support.ai.result;

import lombok.Getter;

/**
 * 通用预测结果。
 * <p>
 * 适用于文本分类、情感分析、性别/年龄识别等场景。
 * 支持 String 值和 float[] 向量两种结果形态。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public class PredictResult {

    /**
     * 字符串预测值
     */
    private String value;

    /**
     * 置信度
     */
    private double confidence;

    /**
     * 浮点向量值
     */
    private float[] floatValue;

    public PredictResult() {
    }

    /**
     * 获取字符串预测值。
     *
     * @return 字符串预测值
     */
    public String value() {
        return value;
    }

    /**
     * 获取字符串预测值。
     *
     * @return 字符串预测值
     */
    public String asString() {
        return value;
    }

    /**
     * 判断结果是否为空。
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return value == null || value.isEmpty();
    }

    /**
     * 获取置信度。
     *
     * @return 置信度
     */
    public double confidence() {
        return confidence;
    }

    /**
     * 设置浮点向量结果。
     *
     * @param value 浮点向量
     */
    public void setValue(float[] value) {
        this.floatValue = value;
    }

    /**
     * 获取浮点向量值。
     *
     * @return 浮点向量
     */
    public float[] getFloatValue() {
        return floatValue;
    }

    public static PredictResult empty() {
        return new PredictResult();
    }

    public static PredictResultBuilder builder() {
        return new PredictResultBuilder();
    }

    public static class PredictResultBuilder {

        private final PredictResult r = new PredictResult();

        public PredictResultBuilder value(String val) {
            r.value = val;
            return this;
        }

        public PredictResultBuilder confidence(double conf) {
            r.confidence = conf;
            return this;
        }

        public PredictResultBuilder floatValue(float[] arr) {
            r.floatValue = arr;
            return this;
        }

        public PredictResult build() {
            return r;
        }
    }
}