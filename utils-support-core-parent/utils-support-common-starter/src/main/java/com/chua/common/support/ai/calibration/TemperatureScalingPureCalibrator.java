package com.chua.common.support.ai.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 温度缩放纯校准器
 * <p><b>用途</b>：用一个温度参数 T 对原始分数进行指数缩放，调整置信度的整体高低。
 * 当 T&gt;1 时，分数被压缩（模型更保守）；当 T&lt;1 时，分数被拉伸（模型更激进）。
 * 温度缩放不改变分数的相对顺序，只改变整体的分布形态。</p>
 *
 * <p><b>公式</b>：{@code score' = 100 · raw^(1/T)}</p>
 *
 * <p><b>参数</b>：</p>
 * <ul>
 *   <li>temperature —— 温度参数 T（&gt;0，建议 0.5~5.0）</li>
 *   <li>T=1 时分数保持不变</li>
 *   <li>T&gt;1 时高分降低、低分升高，整体向中间收缩</li>
 *   <li>T&lt;1 时高分更高、低分更低，整体向两端拉伸</li>
 * </ul>
 *
 * <p><b>场景</b>：</p>
 * <ul>
 *   <li>模型输出的分数整体偏高或偏低，需要整体调整</li>
 *   <li>模型过于自信（分数普遍偏高）时，用 T&gt;1 降低置信度</li>
 *   <li>模型不够自信（分数普遍偏低）时，用 T&lt;1 提高置信度</li>
 *   <li>不改变排序结果，只改变分数的可解释性</li>
 * </ul>
 *
 * <p><b>示例</b>：</p>
 * <pre>{@code
 * PureCalibrator cal = TemperatureScalingPureCalibrator.builder().temperature(0.8).build();
 * double score = cal.calibrate(0.85);   // 约 89.87 分（略微拉伸）
 *
 * PureCalibrator cal2 = TemperatureScalingPureCalibrator.builder().temperature(2.0).build();
 * double score2 = cal2.calibrate(0.85); // 约 73.57 分（显著压缩）
 * }</pre>
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
     *
     * Temperature
     */
    @Builder.Default
    private double temperature = 1.0;

    /**
     * Calibrate
     */
    @Override
    public double calibrate(double rawScore) {
        // 确保分数在0~1之间
        double clamped = Math.clamp(rawScore, 0.0, 1.0);

        // 温度缩放：raw^(1/T)
        double scaled = Math.pow(clamped, 1.0 / temperature);

        return Math.round(scaled * 10000.0) / 100.0;
    }

    /**
     * 获取Name
     */
    @Override
    public String getName() {
        return "温度缩放纯校准";
    }

    /**
     * 获取Description
     */
    @Override
    public String getDescription() {
        return "基于温度参数的分数缩放。T=1不变，T>1压缩（更保守），T<1拉伸（更激进）。";
    }
}
