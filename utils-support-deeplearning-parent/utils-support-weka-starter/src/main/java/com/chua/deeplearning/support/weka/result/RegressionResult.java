package com.chua.deeplearning.support.weka.result;

/**
 * 回归预测结果。
 *
 * @param predictedValue 预测值
 * @author CH
 * @since 4.0.0.42
 */
public record RegressionResult(double predictedValue) {

    @Override
    public String toString() {
        return String.format("%.6f", predictedValue);
    }
}
