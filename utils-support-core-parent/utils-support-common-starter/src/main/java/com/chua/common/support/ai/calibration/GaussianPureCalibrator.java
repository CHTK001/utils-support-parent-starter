package com.chua.common.support.ai.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 双高斯纯校准器
 * <p>
 * 【用途】 假设正样本和负样本的分数各自服从一个高斯分布，
 * 用贝叶斯公式计算给定分数属于正类的概率，输出0~100分。
 * 比Sigmoid更能适应实际数据的双峰分布，拉开效果更好。
 * <p>
 * 【公式】
 * P(pos|raw) = P(raw|pos)·P(pos) / [P(raw|pos)·P(pos) + P(raw|neg)·P(neg)]
 * 其中 P(raw|pos) ~ N(muPos, stdPos²)，P(raw|neg) ~ N(muNeg, stdNeg²)
 * <p>
 * 【参数】
 * muPos    – 正样本分数均值（建议0.80~0.90）
 * stdPos   – 正样本分数标准差（建议0.03~0.08）
 * muNeg    – 负样本分数均值（建议0.40~0.60）
 * stdNeg   – 负样本分数标准差（建议0.10~0.20）
 * priorPos – 正样本先验概率（建议0.3~0.7，默认0.5）
 * <p>
 * 【场景】
 * - 正负样本分数呈现明显双峰分布
 * - 需要高精度校准，尤其要压制“相似但不同”的分数
 * - 人脸识别、图像检索等
 * <p>
 * 【示例】
 * PureCalibrator cal = GaussianPureCalibrator.builder()
 * .muPos(0.85).stdPos(0.05)
 * .muNeg(0.50).stdNeg(0.15)
 * .priorPos(0.5).build();
 * double score = cal.calibrate(0.81); // 约35分（靠近负类）
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GaussianPureCalibrator implements PureCalibrator {

    /**
     * 正样本分数均值，默认0.85
     */
    @Builder.Default
    /** MUPOS */
    private double muPos = 0.85;

    /**
     * 正样本分数标准差，默认0.05
     */
    @Builder.Default
    /** STDPOS */
    private double stdPos = 0.05;

    /**
     * 负样本分数均值，默认0.50
     */
    @Builder.Default
    /** MUNEG */
    private double muNeg = 0.50;

    /**
     * 负样本分数标准差，默认0.15
     */
    @Builder.Default
    /** STDNEG */
    private double stdNeg = 0.15;

    /**
     * 正样本先验概率，默认0.5
     */
    @Builder.Default
    /** PriorPOS */
    private double priorPos = 0.5;

    @Override
    /** Calibrate */
    public double calibrate(double rawScore) {
        double pPos = gaussianPdf(rawScore, muPos, stdPos) * priorPos;
        double pNeg = gaussianPdf(rawScore, muNeg, stdNeg) * (1 - priorPos);

        if (pPos + pNeg == 0) {
            return 50.0;
        }

        double prob = pPos / (pPos + pNeg);
        return Math.round(prob * 10000.0) / 100.0;
    }

    /**
     * 高斯概率密度函数
     *
     * @param x     自变量
     * @param mu    均值
     * @param sigma 标准差
     * @return 概率密度值
     */
    private double gaussianPdf(double x, double mu, double sigma) {
        return (1.0 / (sigma * Math.sqrt(2 * Math.PI))) *
                Math.exp(-0.5 * Math.pow((x - mu) / sigma, 2));
    }

    @Override
    /** 获取Name */
    public String getName() {
        return "双高斯纯校准";
    }

    @Override
    /** 获取Description */
    public String getDescription() {
        return "基于双高斯分布的贝叶斯概率校准。参数：正类均值/标准差、负类均值/标准差、先验概率。";
    }
}
