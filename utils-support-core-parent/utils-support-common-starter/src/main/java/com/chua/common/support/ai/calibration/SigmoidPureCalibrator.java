package com.chua.common.support.ai.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Sigmoid 纯校准器
 * <p>
 * 【用途】 将原始分数通过Sigmoid函数映射到0~100分，在阈值附近产生陡峭过渡，
 * 有效拉开相似和不相似的分数。
 * <p>
 * 【公式】 score' = 100 / (1 + e^{-k * (raw - t)})
 * <p>
 * 【参数】
 * k – 陡度，越大过渡越陡（建议10~30）
 * t – 阈值，决定分界线位置（建议0.7~0.85）
 * <p>
 * 【场景】
 * - 快速原型验证
 * - 正负样本分布接近逻辑分布时
 * - 只需简单可调的S形曲线
 * <p>
 * 【示例】
 * PureCalibrator cal = SigmoidPureCalibrator.builder().k(20).t(0.78).build();
 * double score = cal.calibrate(0.85); // 约90.59
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SigmoidPureCalibrator implements PureCalibrator {

    /**
     * 陡度参数，默认15
     */
    @Builder.Default
    /**
     * K
    */
    private double k = 15.0;

    /**
     * 阈值参数，默认0.75
     */
    @Builder.Default
    /**
     * T
    */
    private double t = 0.75;

    @Override
    /**
     * Calibrate
    */
    public double calibrate(double rawScore) {
        double expVal = Math.exp(-k * (rawScore - t));
        double prob = 1.0 / (1.0 + expVal);
        return Math.round(prob * 10000.0) / 100.0;
    }

    @Override
    /**
     * 获取Name
    */
    public String getName() {
        return "Sigmoid纯校准";
    }

    @Override
    /**
     * 获取Description
    */
    public String getDescription() {
        return "基于Sigmoid函数的分数校准。参数k控制陡度，t控制阈值。";
    }
}
