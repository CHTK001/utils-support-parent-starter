package com.chua.common.support.ai.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 温度缩放纯校准器
 * <p>
 * 【用途】 用一个温度参数T对原始分数进行指数缩放，调整置信度的整体高低。
 * 当T>1时，分数被压缩（模型更保守）；当T<1时，分数被拉伸（模型更激进）。
 * 温度缩放不改变分数的相对顺序，只改变整体的分布形态。
 * <p>
 * 【公式】 score' = 100 · raw^(1/T)
 * <p>
 * 【参数】
 * temperature – 温度参数T（>0，建议0.5~5.0）
 * T=1时，分数保持不变
 * T>1时，高分降低、低分升高，整体向中间收缩
 * T<1时，高分更高、低分更低，整体向两端拉伸
 * <p>
 * 【场景】
 * - 模型输出的分数整体偏高或偏低，需要整体调整
 * - 模型过于自信（分数普遍偏高）时，用T>1降低置信度
 * - 模型不够自信（分数普遍偏低）时，用T<1提高置信度
 * - 不改变排序结果，只改变分数的可解释性
 * <p>
 * 【示例】
 * PureCalibrator cal = TemperatureScalingPureCalibrator.builder().temperature(0.8).build();
 * double score = cal.calibrate(0.85); // 约89.87分（略微拉伸）
 * <p>
 * PureCalibrator cal2 = TemperatureScalingPureCalibrator.builder().temperature(2.0).build();
 * double score2 = cal2.calibrate(0.85); // 约73.57分（显著压缩）
 * <p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemperatureScalingPureCalibrator implements PureCalibrator {

    /**
     * 温度参数T，默认1.0（保持原始分数不变）
     */
    @Builder.Default
    private double temperature = 1.0;

    @Override
    public double calibrate(double rawScore) {
        // 确保分数在0~1之间
        double clamped = Math.clamp(rawScore, 0.0, 1.0);

        // 温度缩放：raw^(1/T)
        double scaled = Math.pow(clamped, 1.0 / temperature);

        return Math.round(scaled * 10000.0) / 100.0;
    }

    @Override
    public String getName() {
        return "温度缩放纯校准";
    }

    @Override
    public String getDescription() {
        return "基于温度参数的分数缩放。T=1不变，T>1压缩（更保守），T<1拉伸（更激进）。";
    }
}
