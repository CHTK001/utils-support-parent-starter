package com.chua.common.support.utils;

import com.chua.common.support.utils.MathUtils.LinearRegression;
import com.chua.common.support.utils.MathUtils.SamplePoint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数学工具类单元测试，覆盖高斯分布、线性回归、移动平均三大算法。
 *
 * @author CH
 * @since 4.0.0.42
 */
class MathUtilsTest {

    /**
     * 数值断言允许的误差
     */
    private static final double DELTA = 1e-6;

    // ==================== 高斯 PDF ====================

    /**
     * 测试标准正态分布在均值处的概率密度：f(0) = 1 / √(2π)。
     */
    @Test
    void testGaussianPdfAtMean() {
        double pdf = MathUtils.gaussianPdf(0, 0, 1);
        assertEquals(1.0 / Math.sqrt(2 * Math.PI), pdf, DELTA);
    }

    /**
     * 测试标准差非法时返回 0。
     */
    @Test
    void testGaussianPdfInvalidStdDev() {
        assertEquals(0.0, MathUtils.gaussianPdf(0, 0, 0), DELTA);
        assertEquals(0.0, MathUtils.gaussianPdf(0, 0, -1), DELTA);
    }

    /**
     * 测试 PDF 关于均值对称。
     */
    @Test
    void testGaussianPdfSymmetric() {
        double left = MathUtils.gaussianPdf(2, 0, 3);
        double right = MathUtils.gaussianPdf(-2, 0, 3);
        assertEquals(left, right, DELTA);
    }

    // ==================== 高斯采样 ====================

    /**
     * 测试默认采样数量为 100，横坐标覆盖 ±4σ。
     */
    @Test
    void testGaussianSampleDefault() {
        List<SamplePoint> points = MathUtils.gaussianSample(0, 1);
        assertNotNull(points);
        assertEquals(100, points.size());
        assertEquals(-4.0, points.get(0).x(), DELTA);
        assertEquals(4.0, points.get(points.size() - 1).x(), DELTA);
    }

    /**
     * 测试采样点按 x 升序排列，峰值出现在均值附近。
     */
    @Test
    void testGaussianSamplePeakAtMean() {
        List<SamplePoint> points = MathUtils.gaussianSample(5, 2, 50, 4);
        double maxY = points.stream().mapToDouble(SamplePoint::y).max().orElse(0);
        int peakIndex = -1;
        for (int i = 0; i < points.size(); i++) {
            if (Math.abs(points.get(i).y() - maxY) < DELTA) {
                peakIndex = i;
                break;
            }
        }
        assertTrue(peakIndex >= 0);
        // 峰值 x 应接近均值 5
        assertEquals(5.0, points.get(peakIndex).x(), 0.5);
    }

    /**
     * 测试参数非法时返回空列表。
     */
    @Test
    void testGaussianSampleInvalidParams() {
        assertTrue(MathUtils.gaussianSample(0, 1, 1, 3).isEmpty());
        assertTrue(MathUtils.gaussianSample(0, 0, 50, 3).isEmpty());
        assertTrue(MathUtils.gaussianSample(0, 1, 50, 0).isEmpty());
    }

    // ==================== 高斯 CDF ====================

    /**
     * 测试 CDF 在均值处的值为 0.5。
     */
    @Test
    void testGaussianCdfAtMean() {
        assertEquals(0.5, MathUtils.gaussianCdf(0, 0, 1), DELTA);
    }

    /**
     * 测试 CDF 的已知参考值：Φ(1)≈0.8413，Φ(-1)≈0.1587。
     */
    @Test
    void testGaussianCdfKnownValues() {
        assertEquals(0.8413447, MathUtils.gaussianCdf(1, 0, 1), 1e-4);
        assertEquals(0.1586552, MathUtils.gaussianCdf(-1, 0, 1), 1e-4);
    }

    /**
     * 测试 CDF 对称性：Φ(-x) = 1 - Φ(x)。
     */
    @Test
    void testGaussianCdfSymmetry() {
        double positive = MathUtils.gaussianCdf(2, 0, 1);
        double negative = MathUtils.gaussianCdf(-2, 0, 1);
        assertEquals(1.0, positive + negative, DELTA);
    }

    // ==================== 线性回归 ====================

    /**
     * 测试正常数据的线性回归结果接近 y = 2x + 1。
     */
    @Test
    void testLinearRegressionNormal() {
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {3.01, 4.98, 7.02, 8.99, 11.0};
        LinearRegression lr = MathUtils.linearRegression(xs, ys);
        assertEquals(2.0, lr.slope(), 0.05);
        assertEquals(1.0, lr.intercept(), 0.05);
        assertEquals(1.0, lr.rSquared(), 0.01);
        assertEquals(1.0, lr.pearson(), 0.01);
    }

    /**
     * 测试 y 为常量（无波动）时 pearson 与 rSquared 均为 0 而非 NaN。
     */
    @Test
    void testLinearRegressionConstantY() {
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {10, 10, 10, 10, 10};
        LinearRegression lr = MathUtils.linearRegression(xs, ys);
        assertEquals(0.0, lr.slope(), DELTA);
        assertEquals(10.0, lr.intercept(), DELTA);
        assertEquals(0.0, lr.rSquared(), DELTA);
        assertEquals(0.0, lr.pearson(), DELTA);
        assertFalse(Double.isNaN(lr.pearson()));
    }

    /**
     * 测试 x 为常量时斜率无意义，返回截距为均值。
     */
    @Test
    void testLinearRegressionConstantX() {
        double[] xs = {5, 5, 5, 5, 5};
        double[] ys = {1, 2, 3, 4, 5};
        LinearRegression lr = MathUtils.linearRegression(xs, ys);
        assertEquals(0.0, lr.slope(), DELTA);
        assertEquals(3.0, lr.intercept(), DELTA);
    }

    /**
     * 测试完全负相关数据 pearson 为 -1。
     */
    @Test
    void testLinearRegressionNegativeCorrelation() {
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {5, 4, 3, 2, 1};
        LinearRegression lr = MathUtils.linearRegression(xs, ys);
        assertEquals(-1.0, lr.slope(), DELTA);
        assertEquals(-1.0, lr.pearson(), DELTA);
        assertEquals(1.0, lr.rSquared(), DELTA);
    }

    /**
     * 测试非法输入返回全零结果。
     */
    @Test
    void testLinearRegressionInvalidInput() {
        LinearRegression lr = MathUtils.linearRegression(null, null);
        assertEquals(0.0, lr.slope(), DELTA);
        assertEquals(0.0, lr.intercept(), DELTA);
        assertEquals(0.0, lr.rSquared(), DELTA);
        assertEquals(0.0, lr.pearson(), DELTA);

        // 长度不一致
        LinearRegression mismatch = MathUtils.linearRegression(new double[]{1, 2}, new double[]{1});
        assertEquals(0.0, mismatch.slope(), DELTA);
    }

    // ==================== 线性回归预测 ====================

    /**
     * 测试基于回归结果的预测值。
     */
    @Test
    void testLinearPredict() {
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {3.01, 4.98, 7.02, 8.99, 11.0};
        double[] predicted = MathUtils.linearPredict(xs, ys, new double[]{6.0, 10.0});
        assertEquals(13.0, predicted[0], 0.05);
        assertEquals(21.0, predicted[1], 0.05);
    }

    /**
     * 测试非法输入返回与 predictX 等长的全 0 数组。
     */
    @Test
    void testLinearPredictInvalidInput() {
        double[] predicted = MathUtils.linearPredict(null, null, new double[]{1, 2, 3});
        assertEquals(3, predicted.length);
        for (double value : predicted) {
            assertEquals(0.0, value, DELTA);
        }
        // 空 predictX 返回空数组
        assertEquals(0, MathUtils.linearPredict(new double[]{1, 2}, new double[]{1, 2}, new double[0]).length);
    }

    // ==================== 简单移动平均 ====================

    /**
     * 测试常量序列的移动平均仍为常量。
     */
    @Test
    void testSimpleMovingAverageConstant() {
        double[] values = {5, 5, 5, 5, 5, 5, 5};
        double[] result = MathUtils.simpleMovingAverage(values, 3);
        for (double value : result) {
            assertEquals(5.0, value, DELTA);
        }
    }

    /**
     * 测试高频震荡序列的窗口平滑效果。
     */
    @Test
    void testSimpleMovingAverageDenoise() {
        double[] values = {1, 9, 1, 9, 1, 9};
        double[] result = MathUtils.simpleMovingAverage(values, 2);
        assertEquals(1.0, result[0], DELTA);
        assertEquals(5.0, result[1], DELTA);
        // 窗口满后稳定为 5
        for (int i = 2; i < result.length; i++) {
            assertEquals(5.0, result[i], DELTA);
        }
    }

    /**
     * 测试窗口大小为 1 时结果等于输入。
     */
    @Test
    void testSimpleMovingAverageWindowOne() {
        double[] values = {1, 2, 3, 4};
        double[] result = MathUtils.simpleMovingAverage(values, 1);
        assertTrue(Arrays.equals(values, result));
    }

    /**
     * 测试空输入与非法窗口返回合理结果且不修改原数组。
     */
    @Test
    void testSimpleMovingAverageEdge() {
        assertEquals(0, MathUtils.simpleMovingAverage(new double[0], 3).length);
        assertEquals(0, MathUtils.simpleMovingAverage((double[]) null, 3).length);
        double[] values = {1, 2, 3};
        double[] tooLarge = MathUtils.simpleMovingAverage(values, 10);
        assertTrue(Arrays.equals(values, tooLarge));
        // List 重载
        double[] listResult = MathUtils.simpleMovingAverage(List.of(1, 2, 3, 4, 5), 2);
        assertEquals(5, listResult.length);
    }
}
