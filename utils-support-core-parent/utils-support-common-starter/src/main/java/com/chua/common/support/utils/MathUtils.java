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
 *   <li>激活函数 — Sigmoid 函数及其导数，适用于概率映射与神经网络反向传播</li>
 *   <li>归一化与相似度 — L2 归一化、Min-Max 归一化、余弦相似度（float/double）、L2 余弦相似度与余弦距离</li>
 * </ul>
 *
 * <p>所有方法均无副作用，输入数组不会被修改；空数组或长度为 0 的输入将返回合理的默认值（详见各方法 Javadoc）。
 *
 * @author CH
 * @since 4.0.0.42
*/
public class MathUtils {

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
    * @return 样本point的结果
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
 // 当前 x：启动 + i·step（末项恰为 结束）
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
 // 决定系数 R² 与皮尔逊相关系数 R（y 无波动时均取 0，避免除零产生 nan）
        double rSquared = 0.0;
        double pearson = 0.0;
        if (syy > 0.0) {
            double denominator = sxx * syy;
            rSquared = (sxy * sxy) / denominator;
            pearson = sxy / Math.sqrt(denominator);
        }
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
    * @return 预测 y 值序列（长度与 predictx 一致）
    */
    public static double[] linearPredict(double[] x, double[] y, double[] predictX) {
        // 参数校验
        if (predictX == null || predictX.length == 0) {
            return new double[0];
        }
 // 输入序列非法时返回与 predictx 等长的全 0 数组
        if (x == null || y == null || x.length != y.length || x.length < 2) {
            double[] zeros = new double[predictX.length];
            Arrays.fill(zeros, 0.0);
            return zeros;
        }
        LinearRegression lr = linearRegression(x, y);
        double[] result = new double[predictX.length];
        for (int i = 0; i < predictX.length; i++) {
            result[i] = lr.slope() * predictX[i] + lr.intercept();
        }
        return result;
    }

    // ==================== 移动平均：简单移动平均 SMA ====================

    /**
    * 简单移动平均（SMA，简单 Move.com Average）。
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
    * 对列表形式的序列做简单移动平均，等价于 {@link #simpleMovingAverage(double[], int)} 的 列表 重载。
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
 // 使用 对象.requirenon空else 将 空 视作 0（避免 NPE）
            Number num = values.get(i);
            arr[i] = Objects.requireNonNullElse(num, 0).doubleValue();
        }
        return simpleMovingAverage(arr, window);
    }

    // ==================== Sigmoid 激活函数 ====================

    /**
    * Sigmoid 激活函数。
    *
    * <p>公式：σ(x) = 1 / (1 + e^(-x))
    *
    * <p>特性：
    * <ul>
    *   <li>输出范围 (0, 1)，可解释为概率</li>
    *   <li>σ(0) = 0.5，关于 (0, 0.5) 中心对称</li>
    *   <li>导数 σ'(x) = σ(x) · (1 - σ(x))，可直接复用计算结果</li>
    * </ul>
    *
    * <p>对于极端值（x &lt; -709 或 x &gt; 709），直接返回边界值以避免浮点溢出。
    *
    * @param x 输入值
    * @return σ(x) ∈ (0, 1)
    * @author CH
    * @since 4.0.0.42
    */
    public static double sigmoid(double x) {
        // 极端值保护：避免 Math.exp 溢出
        if (x < -709.0) {
            return 0.0;
        }
        if (x > 709.0) {
            return 1.0;
        }
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /**
    * 对数组逐元素计算 Sigmoid。
    *
    * <p>返回新数组，不修改输入。
    *
    * @param values 输入数组
    * @return 逐元素 sigmoid 结果
    * @author CH
    * @since 4.0.0.42
    */
    public static double[] sigmoid(double[] values) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = sigmoid(values[i]);
        }
        return result;
    }

    /**
    * Sigmoid 的导数 σ'(x) = σ(x) · (1 - σ(x))。
    *
    * <p>常用于反向传播，已知 σ(x) 时可直接传入避免重复计算。
    *
    * @param x 输入值
    * @return σ'(x) ∈ [0, 0.25]
    * @author CH
    * @since 4.0.0.42
    */
    public static double sigmoidDerivative(double x) {
        double s = sigmoid(x);
        return s * (1.0 - s);
    }

    /**
    * Sigmoid 的导数，直接传入已计算的 σ(x) 值。
    *
    * <p>公式：σ'(x) = s · (1 - s)，其中 s = σ(x)。
    * 适用于反向传播中已持有 σ(x) 结果的场景，避免重复计算。
    *
    * @param s 已计算的 σ(x) 值
    * @return σ'(x) ∈ [0, 0.25]
    * @author CH
    * @since 4.0.0.42
    */
    public static double sigmoidDerivativeFromOutput(double s) {
        return s * (1.0 - s);
    }

    // ==================== 归一化与余弦相似度 ====================

    /**
    * 向量 L2 归一化（单位化）。
    *
    * <p>公式：x̂ = x / ‖x‖₂，其中 ‖x‖₂ = √(Σxᵢ²)
    *
    * <p>归一化后向量的模长为 1（零向量返回原数组拷贝）。
    *
    * @param vector 输入向量
    * @return L2 归一化后的向量（新数组，不修改输入）
    * @author CH
    * @since 4.0.0.42
    */
    public static double[] normalize(double[] vector) {
        if (vector == null || vector.length == 0) {
            return new double[0];
        }
        double norm = l2Norm(vector);
        if (norm == 0.0) {
            return Arrays.copyOf(vector, vector.length);
        }
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] / norm;
        }
        return result;
    }

    /**
    * 最小-最大 归一化，将值映射到 [0, 1] 区间。
    *
    * <p>公式：x̂ = (x - min) / (max - min)
    *
    * <p>当 max == min 时返回 0.0（避免除零）。
    *
    * @param value 输入值
    * @param min   区间最小值
    * @param max   区间最大值
    * @return 归一化后的值 ∈ [0, 1]
    * @author CH
    * @since 4.0.0.42
    */
    public static double normalizeMinMax(double value, double min, double max) {
        double range = max - min;
        if (range == 0.0) {
            return 0.0;
        }
        return (value - min) / range;
    }

    /**
    * 计算向量的 L2 范数（欧几里得长度）。
    *
    * <p>公式：‖x‖₂ = √(Σxᵢ²)
    *
    * @param vector 输入向量
    * @return L2 范数（≥ 0）
    * @author CH
    * @since 4.0.0.42
    */
    public static double l2Norm(double[] vector) {
        if (vector == null || vector.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    /**
    * 计算两个向量的余弦相似度（double 版本）。
    *
    * <p>公式：cos(θ) = (A · B) / (‖A‖₂ · ‖B‖₂)
    *
    * <p>返回值范围 [-1, 1]：
    * <ul>
    *   <li>1 — 完全相同方向</li>
    *   <li>0 — 正交（无相关性）</li>
    *   <li>-1 — 完全相反方向</li>
    * </ul>
    *
    * <p>任一向量为空或为零向量时返回 0.0。
    *
    * @param a 向量 A
    * @param b 向量 B（长度必须与 A 一致）
    * @return 余弦相似度 ∈ [-1, 1]
    * @author CH
    * @since 4.0.0.42
    */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0;
        }
        return dot / denominator;
    }

    /**
    * 计算两个向量的余弦相似度（float 版本，适用于深度学习特征向量）。
    *
    * <p>公式：cos(θ) = (A · B) / (‖A‖₂ · ‖B‖₂)
    *
    * <p>返回值范围 [-1, 1]。维度不一致时抛出 {@link IllegalArgumentException}。
    * 任一向量为空或为零向量时返回 0.0f。
    *
    * @param a 向量 A
    * @param b 向量 B
    * @return 余弦相似度 ∈ [-1, 1]
    * @throws IllegalArgumentException 维度不匹配时抛出
    * @author CH
    * @since 4.0.0.42
    */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0) {
            return 0.0f;
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配: " + a.length + " vs " + b.length);
        }
        float dot = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        if (denominator == 0.0f) {
            return 0.0f;
        }
        return dot / denominator;
    }

    /**
    * L2 归一化余弦相似度（float 版本）。
    *
    * <p>公式：cos(θ) = (A̅ · B̅)，其中 A̅、B̅ 分别为 A、B 的 L2 归一化向量。
    * 适用于向量已归一化（模长 = 1）的场景，此时余弦相似度等于点积。
    *
    * <p>返回值范围 [-1, 1]。维度不一致时抛出 {@link IllegalArgumentException}。
    * 零向量返回 0.0f。
    *
    * @param a 向量 A
    * @param b 向量 B
    * @return L2 归一化余弦相似度 ∈ [-1, 1]
    * @throws IllegalArgumentException 维度不匹配时抛出
    * @author CH
    * @since 4.0.0.42
    */
    public static float l2CosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0) {
            return 0.0f;
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配: " + a.length + " vs " + b.length);
        }
        // 先对两个向量做 L2 归一化
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        normA = (float) Math.sqrt(normA);
        normB = (float) Math.sqrt(normB);
        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }
        float dot = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dot += (a[i] / normA) * (b[i] / normB);
        }
        return dot;
    }

    /**
    * L2 归一化余弦相似度（double 版本）。
    *
    * <p>等价于 {@code cosineSimilarity(normalize(a), normalize(b))}，
    * 适用于向量已归一化或需要精确归一化的场景。
    *
    * @param a 向量 A
    * @param b 向量 B
    * @return L2 归一化余弦相似度 ∈ [-1, 1]
    * @author CH
    * @since 4.0.0.42
    */
    public static double l2CosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (a[i] / normA) * (b[i] / normB);
        }
        return dot;
    }

    /**
    * 计算两个向量的余弦距离（double 版本）。
    *
    * <p>公式：d = 1 - cos(θ)
    *
    * <p>返回值范围 [0, 2]：
    * <ul>
    *   <li>0 — 完全相同方向</li>
    *   <li>1 — 正交</li>
    *   <li>2 — 完全相反方向</li>
    * </ul>
    *
    * @param a 向量 A
    * @param b 向量 B（长度必须与 A 一致）
    * @return 余弦距离 ∈ [0, 2]
    * @author CH
    * @since 4.0.0.42
    */
    public static double cosineDistance(double[] a, double[] b) {
        return 1.0 - cosineSimilarity(a, b);
    }

    /**
    * 计算两个向量的余弦距离（float 版本）。
    *
    * @param a 向量 A
    * @param b 向量 B
    * @return 余弦距离 ∈ [0, 2]
    * @author CH
    * @since 4.0.0.42
    */
    public static float cosineDistance(float[] a, float[] b) {
        return 1.0f - cosineSimilarity(a, b);
    }

    // ==================== 内部工具方法 ====================

    /**
    * 将 值 限制在 [最小, 最大] 区间内，nan 返回 0。
    *
    * @param value 输入值
    * @param min   下界
    * @param max   上界
    * @return 截断后的值
    */
    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
    * 将 |值| 限制在不超过 最大值，符号保持不变，nan 返回 0。
    *
    * @param value    输入值
    * @param maxValue |值| 的最大绝对值
    * @return 限幅后的值
    */
    private static double clampAbs(double value, double maxValue) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value > maxValue) {
            return maxValue;
        }
        if (value < -maxValue) {
            return -maxValue;
        }
        return value;
    }
}
