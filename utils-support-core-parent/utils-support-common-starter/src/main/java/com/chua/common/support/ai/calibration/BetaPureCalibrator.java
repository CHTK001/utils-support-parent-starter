package com.chua.common.support.ai.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Beta 纯校准器
 * <p>
 * 【用途】 用Beta累积分布函数(CDF)将原始分数映射到0~100分。
 * 适合分数集中在0~1两端、中间稀疏的分布，能提供不对称的拉伸效果。
 * <p>
 * 【公式】 score' = 100 · I_x(α, β)，其中 I_x 为正则化不完全Beta函数
 * <p>
 * 【参数】
 * alpha – Beta分布形状参数α（>0，建议1~5）
 * beta  – Beta分布形状参数β（>0，建议1~5）
 * <p>
 * 【场景】
 * - 分数分布偏斜严重，两端密集中间稀疏
 * - 需要不对称的拉伸（如低分压得更低，高分拉得更高）
 * - 比Sigmoid更灵活，能拟合多种分布形态
 * <p>
 * 【示例】
 * PureCalibrator cal = BetaPureCalibrator.builder().alpha(2.0).beta(3.0).build();
 * double score = cal.calibrate(0.85); // 约92分
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
public class BetaPureCalibrator implements PureCalibrator {

    /**
     * Beta分布形状参数α，默认2.0
     */
    @Builder.Default
    /** 透明度 */
    private double alpha = 2.0;

    /**
     * Beta分布形状参数β，默认2.0
     */
    @Builder.Default
    /** Beta */
    private double beta = 2.0;

    @Override
    /** Calibrate */
    public double calibrate(double rawScore) {
        double prob = regularizedIncompleteBeta(rawScore, alpha, beta);
        return Math.round(prob * 10000.0) / 100.0;
    }

    /**
     * 正则化不完全Beta函数（使用连分数近似）
     *
     * @param x 自变量（0~1）
     * @param a 形状参数α
     * @param b 形状参数β
     * @return 累积概率值
     */
    private double regularizedIncompleteBeta(double x, double a, double b) {
        if (x < 0 || x > 1) {
            return x;
        }
        if (x == 0 || x == 1) {
            return x;
        }

        double bt = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) +
                a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) {
            return bt * continuedFraction(x, a, b) / a;
        } else {
            return 1 - bt * continuedFraction(1 - x, b, a) / b;
        }
    }

    /**
     * 连分数展开计算
     * @param x 方法入参 x
     * @param a 方法入参 a
     * @param b 方法入参 b
     * @return 结果数值
     */
    private double continuedFraction(double x, double a, double b) {
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < 1e-30) {
            d = 1e-30;
        }
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= 200; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            d = 1.0 / d;
            h *= d * c;

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-10) {
                break;
            }
        }
        return h;
    }

    /**
     * 对数Gamma函数（Lanczos近似）
     * @param x 方法入参 x
     * @return 结果数值
     */
    private double logGamma(double x) {
        double[] coef = {76.18009172947146, -86.50532032941677,
                24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            y += 1;
            ser += coef[j] / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    @Override
    /** 获取Name */
    public String getName() {
        return "Beta纯校准";
    }

    @Override
    /** 获取Description */
    public String getDescription() {
        return "基于Beta累积分布函数的分数校准。参数α和β控制分布形状，适合两端密集中间稀疏的分布。";
    }
}
