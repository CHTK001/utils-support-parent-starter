package com.chua.deeplearning.support.weka.result;

import lombok.Getter;

/**
 * 回归预测结果。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class RegressionResult {

    /** 预测值 */
    private final double predictedValue;

    /**
     * 构造回归预测结果。
     *
     * @param predictedValue 预测值
     */
    public RegressionResult(double predictedValue) {
        this.predictedValue = predictedValue;
    }

    @Override
    public String toString() {
        return String.format("%.6f", predictedValue);
    }
}
