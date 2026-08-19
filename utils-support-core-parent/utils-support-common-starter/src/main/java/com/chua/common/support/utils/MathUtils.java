package com.chua.common.support.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 数学工具类，提供常用的数学/统计算法。
 *
 * <p>包含以下功能：
 * <ul>
 *   <li>钟状图算法 — 高斯（正态）分布概率密度函数（PDF）曲线采样</li>
 *   <li>线性回归 — 一元线性回归最小二乘拟合，返回斜率、截距、相关系数</li>
 *   <li>移动平均 — 简单移动平均（SMA），常用于时间序列平滑</li>
 * </ul>
 *
 * <p>所有方法均无副作用，输入数组不会被修改；空数组或长度为 0 的输入将返回合理的默认值（详见各方法 Javadoc）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MathUtils {

    /**
     * 圆周率
     */
    private static final double PI = Math.PI;

    /**
     * 2π，常用于高斯分布归一化系数
     */
    private static final double TWO_PI = 2.0 * Math.PI;

    /**
     * 默认钟状图采样点数（横坐标点数）
     */
    private static final int DEFAULT_SAMPLE_SIZE = 100;

    /**
     * 默认钟状图横坐标范围相对标准差的倍数（左右各取 {@value} 倍 σ）
     */
    private static final double DEFAULT_RANGE_MULTIPLIER = 4.0;

    /**
     * 私有构造，禁止实例化
     */
    private MathUtils() {
    }

    // ==================== 钟状图算法：高斯/正态分布 PDF 采样 ====================

    /**
     * 钟状图采样点记录，记录横坐标 x 与对应的概率密度 y。
     *
     * @param x 横坐标（取值）
     * @param y 概率密度 f(x)
     * @author CH
     * @since 4.0.0.42
     */
    public record SamplePoint(double x, double y) {
    }

    /**
     * 高斯（正态）分布概率密度函数。
     *
     * <p>公式：f(x) = (1 / (σ · √(2π))) · exp(-((x - μ)²) / (2σ²))
     *
     * @param x      横坐标取值
     * @param mean   均值 μ
     * @param stdDev 标准差 σ（必须大于 0）
     * @return x 处的概率密度 f(x)
     */
    public static double gaussianPdf(double x, double mean, double stdDev) {
        // 标准差必须为正
        if (stdDev <= 0.0) {
            return 0.0;
        }
        // 计算 (x - μ)²
        double diff = x - mean;
        double diffSquared = diff * diff;
        // 计算 2σ²
        double twoSigmaSquared = 2.0 * stdDev * stdDev;
        // 指数部分 exp(-((x - μ)²) / (2σ²))
        double exponent = -diffSquared / twoSigmaSquared;
        // 归一化系数 1 / (σ · √(2π))
        double coefficient = 1.0 / (stdDev * Math.sqrt(TWO_PI));
        return coefficient * Math.exp(exponent);
    }

    /**
     * 对高斯分布曲线进行等距采样，输出钟状图坐标点序列。
     *
     * <p>横坐标范围默认取 [μ - 4σ, μ + 4σ]，共 {@value #DEFAULT_SAMPLE_SIZE} 个采样点。
     * 采样区间几乎覆盖了 99.99% 的概率质量，曲线两端已足够趋近于 0。
     *
     * @param mean   均值 μ
     * @param stdDev 标准差 σ（必须大于 0）
     * @return 采样点列表（按 x 升序排列）
     */
    public static List<SamplePoint> gaussianSample(double mean, double stdDev) {
        return gaussianSample(mean, stdDev, DEFAULT_SAMPLE_SIZE, DEFAULT_RANGE_MULTIPLIER);
    }

    /**
     * 对高斯分布曲线进行等距采样，输出钟状图坐标点序列。
     *
     * <p>横坐标范围取 [μ - rangeMultiplier·σ, μ + rangeMultiplier·σ]，共 sampleSize 个采样点。
     *
     * @param mean            均值 μ
     * @param stdDev          标准差 σ（必须大于 0）
     * @param sampleSize      采样点数（必须 ≥ 2）
     * @param rangeMultiplier 横坐标范围相对标准差的倍数（必须 &gt; 0，常用 3 ~ 6）
     * @return 采样点列表（按 x 升序排列）
     */
    public static List<SamplePoint> gaussianSample(
            double mean,
            double stdDev,
            int sampleSize,
            double rangeMultiplier) {
        // 参数校验
        if (stdDev <= 0.0) {
            return new ArrayList<>();
        }
        if (sampleSize < 2) {
            return new ArrayList<>();
        }
        if (rangeMultiplier <= 0.0) {
            return new ArrayList<>();
        }
        // 计算横坐标起止点
        double start = mean - rangeMultiplier * stdDev;
        double end = mean + rangeMultiplier * stdDev;
        // 相邻点间距
        double step = (end - start) / (sampleSize - 1);
        List<SamplePoint> points = new ArrayList<>(sampleSize);
        for (int i = 0; i < sampleSize; i++) {
            // 当前 x：end + i·step，等价于 start + i·step（末项恰为 end）
            double x = start + i * step;
            double y = gaussianPdf(x, mean, stdDev);
            points.add(new SamplePoint(x, y));
        }
        return points;
    }

    /**
     * 计算高斯分布的累积分布函数（CDF）在 x 处的取值，使用 Abramowitz & Stegun 近似公式。
     *
     * <p>公式：Φ(x) ≈ 1 - φ(x) · (a₁·k + a₂·k² + a₃·k³ + a₄·k⁴ + a₅·k⁵)
     * 其中 k = 1 / (1 + 0.2316419·x)，φ(x) 为标准正态 PDF。
     * 最大误差约 7.5e-8。
     *
     * @param x    横坐标取值
     * @param mean 均值 μ
     * @param stdDev 标准差 σ（必须大于 0）
     * @return x 处的累积概率 Φ(x)
     */
    public static double gaussianCdf(double x, double mean, double stdDev) {
        // 标准差必须为正
        if (stdDev <= 0.0) {
            return 0.0;
        }
        // 标准化为标准正态
        double z = (x - mean) / stdDev;
        // 标准化系数 1 / √(2π)
        double invSqrtTwoPi = 1.0 / Math.sqrt(TWO_PI);
        // Abramowitz & Stegun 26.2.17 多项式系数
        double k = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double poly = k * (0.319381530
                + k * (-0.356563782
                + k * (1.781477937
                + k * (-1.821255978
                + k * 1.330274429))));
        // 标准正态 PDF φ(z)
        double phi = Math.exp(-0.5 * z * z) * invSqrtTwoPi;
        // 正半轴累积概率 1 - φ(z)·poly
        double positiveTail = 1.0 - phi * poly;
        // 还原到原坐标轴：x < mean 时返回 1 - positiveTail；x >= mean 时返回 positiveTail
        if (x < mean) {
            return 1.0 - positiveTail;
        }
        return positiveTail;
    }

    // ==================== 线性回归：最小二乘法 ====================

    /**
     * 一元线性回归结果。
     *
     * @param slope            斜率 k（y = k·x + b 中的 k）
     * @param intercept        截距 b（y = k·x + b 中的 b）
     * @param rSquared         决定系数 R²，取值范围 [0, 1]，越接近 1 表示拟合越好
     * @param pearson          皮尔逊相关系数 r，取值范围 [-1, 1]
     * @author CH
     * @since 4.0.0.42
     */
    public record LinearRegression(
            double slope,
            double intercept,
            double rSquared,
            double pearson) {
    }

    /**
     * 一元线性回归最小二乘拟合。
     *
     * <p>求解 y = k·x + b 的最佳拟合直线，返回斜率 k、截距 b、决定系数 R² 与皮尔逊相关系数 r。
     *
     * <p>输入要求：
     * <ul>
     *   <li>x 与 y 长度必须一致且 ≥ 2</li>
     *   <li>x 不能全相等（否则斜率无意义）</li>
     * </ul>
     *
     * <p>非法输入返回 {@link LinearRegression} 全零值（slope=0, intercept=0, rSquared=0, pearson=0）。
     *
     * @param x 自变量序列（长度 ≥ 2）
     * @param y 因变量序列（长度与 x 一致）
     * @return 线性回归结果
     */
    public static LinearRegression linearRegression(double[] x, double[] y) {
        // 基本校验
        if (x == null || y == null || x.length != y.length || x.length < 2) {
            return new LinearRegression(0.0, 0.0, 0.0, 0.0);
        }
        int n = x.length;
        // 累加 Σx、Σy、Σxy、Σx²、Σy²
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumXX = 0.0;
        double sumYY = 0.0;
        for (int i = 0; i < n; i++) {
            double xi = x[i];
            double yi = y[i];
            sumX += xi;
            sumY += yi;
            sumXY += xi * yi;
            sumXX += xi * xi;
            sumYY += yi * yi;
        }
        // 均值
        double meanX = sumX / n;
        double meanY = sumY / n;
        // 方差与协方差（中心化形式）
        double sxx = sumXX - n * meanX * meanX;
        double syy = sumYY - n * meanY * meanY;
        double sxy = sumXY - n * meanX * meanY;
        // x 全相等时 sxx == 0，斜率无意义
        if (sxx <= 0.0) {
            return new LinearRegression(0.0, meanY, 0.0, 0.0);
        }
        // 最小二乘解
        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;
        // 决定系数 R² = (sxy)² / (sxx · syy)
        double rSquared = 0.0;
        if (syy > 0.0) {
            rSquared = (sxy * sxy) / (sxx * syy);
        }
        // 皮尔逊相关系数 r
        double pearson = sxy / Math.sqrt(sxx * Math.max(syy, 0.0));
        return new LinearRegression(slope, intercept, clamp(rSquared, 0.0, 1.0), clampAbs(pearson, 1.0));
    }

    /**
     * 一元线性回归并对给定 x 序列预测 y 值。
     *
     * <p>输入非法时（长度不一致或长度 &lt; 2）返回与输入 x 等长的全 0 数组。
     *
     * @param x 自变量序列
     * @param y 因变量序列
     * @param predictX 待预测的自变量序列
     * @return 预测 y 值序列（长度与 predictX 一致）
     */
    public static double[] linearPredict(double[] x, double[] y, double[] predictX) {
        // 参数校验
        if (predictX == null || predictX.length == 0) {
            return new double[0];
        }
        LinearRegression lr = linearRegression(x, y);
        // 输入非法时 linearRegression 返回全零值，整体预测即全 0
        if (lr.slope() == 0.0 && lr.intercept() == 0.0 && lr.rSquared() == 0.0 && lr.pearson() == 0.0) {
            // 进一步判断：确实是非法输入还是恰好经过原点
            if (x == null || y == null || x.length != y.length || x.length < 2) {
                double[] zeros = new double[predictX.length];
                Arrays.fill(zeros, 0.0);
                return zeros;
            }
        }
        double[] result = new double[predictX.length];
        for (int i = 0; i < predictX.length; i++) {
            result[i] = lr.slope() * predictX[i] + lr.intercept();
        }
        return result;
    }

    // ==================== 移动平均：简单移动平均 SMA ====================

    /**
     * 简单移动平均（SMA，Simple Moving Average）。
     *
     * <p>对输入序列按窗口大小 window 进行等权滑动平均，常用于时间序列平滑去噪。
     *
     * <p>输出规则：
     * <ul>
     *   <li>输出长度与输入长度一致</li>
     *   <li>前 (window - 1) 个位置因窗口不足，使用"已有数据的累积平均"作为填充（首项即为原值）</li>
     *   <li>从第 window 个位置开始，使用完整窗口的平均值</li>
     * </ul>
     *
     * <p>窗口大小 ≤ 0 或输入为 null/空数组时，原样返回输入的拷贝（避免共享引用）。
     *
     * @param values 输入序列
     * @param window 窗口大小（必须 ≥ 1）
     * @return 平滑后的序列（长度与输入一致）
     */
    public static double[] simpleMovingAverage(double[] values, int window) {
        // 空输入保护
        if (values == null || values.length == 0) {
            return new double[0];
        }
        // 窗口非法保护：原样拷贝返回
        if (window <= 0 || window > values.length) {
            return Arrays.copyOf(values, values.length);
        }
        int n = values.length;
        double[] result = new double[n];
        // 维护滑动窗口的累加和
        double windowSum = 0.0;
        for (int i = 0; i < n; i++) {
            // 加入当前元素
            windowSum += values[i];
            if (i < window) {
                // 窗口未填满：使用累积平均作为前段填充
                result[i] = windowSum / (i + 1);
            } else {
                // 完整窗口：滑出最旧元素、加入当前元素，取平均
                windowSum -= values[i - window];
                result[i] = windowSum / window;
            }
        }
        return result;
    }

    /**
     * 对列表形式的序列做简单移动平均，等价于 {@link #simpleMovingAverage(double[], int)} 的 List 重载。
     *
     * <p>返回新的 {@code double[]}，不修改输入。
     *
     * @param values 输入序列
     * @param window 窗口大小（必须 ≥ 1）
     * @return 平滑后的数组
     */
    public static double[] simpleMovingAverage(List<? extends Number> values, int window) {
        // 空输入保护
        if (values == null || values.isEmpty()) {
            return new double[0];
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            // 使用 Objects.requireNonNullElse 将 null 视作 0（避免 NPE）
            Number num = values.get(i);
            arr[i] = Objects.requireNonNullElse(num, 0).doubleValue();
        }
        return simpleMovingAverage(arr, window);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 value 限制在 [min, max] 区间内。
     *
     * @param value 输入值
     * @param min   下界
     * @param max   上界
     * @return 截断后的值
     */
    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * 将 |value| 限制在不超过 maxValue，符号保持不变。
     *
     * @param value    输入值
     * @param maxValue |value| 的最大绝对值
     * @return 限幅后的值
     */
    private static double clampAbs(double value, double maxValue) {
        if (value > maxValue) {
            return maxValue;
        }
        if (value < -maxValue) {
            return -maxValue;
        }
        return value;
    }
}
