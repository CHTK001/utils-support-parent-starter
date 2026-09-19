package com.chua.common.support.lang.algorithm.boundary;

import java.util.List;

/**
 * 边界距离算法顶级接口，定义多维空间边界距离计算的统一抽象。
 *
 * <p>边界距离（Boundary Distance）算法用于计算多点之间的最大边界距离，
 * 即找出一组数据点中两两之间距离的最大值，常用于：
 * <ul>
 *   <li>聚类评估：衡量簇的直径（最大散度）</li>
 *   <li>异常检测：判断数据点分布的极端范围</li>
 *   <li>空间分析：计算地理数据的最大跨度</li>
 *   <li>优化算法：评估搜索空间的覆盖边界</li>
 * </ul>
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #boundary(List)} — 计算数据集中任意两点间距离的最大值</li>
 *   <li>{@link #furthestPair(List)} — 查找距离最远的两个样本点</li>
 *   <li>{@link #extent(List)} — 计算数据集的扩展范围（最小外接超球直径）</li>
 * </ul>
 *
 * <h2>支持的距离度量</h2>
 * <ul>
 *   <li>欧几里得距离（Euclidean） — 直线距离，最常用</li>
 *   <li>曼哈顿距离（Manhattan） — 城市街区距离，坐标轴绝对值之和</li>
 *   <li>切比雪夫距离（Chebyshev） — 各维度差值绝对值的最大值</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 使用欧几里得边界距离
 * BoundaryDistanceAlgorithm euclidean = new EuclideanBoundaryDistance();
 *
 * List<double[]> points = List.of(
 *     new double[]{0.0, 0.0},
 *     new double[]{3.0, 4.0},
 *     new double[]{1.0, 1.0}
 * );
 *
 * double maxDistance = euclidean.boundary(points);
 * FurthestPair pair = euclidean.furthestPair(points);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
public interface BoundaryDistanceAlgorithm {

    /**
     * 计算数据集中任意两点之间距离的最大值
     *
     * <p>穷举所有点对组合，返回其中的最大距离值。
     * 时间复杂度为 O(N²)，适用于中小规模数据集。</p>
     *
     * @param points 数据点列表，每个数据点为 N 维特征向量
     * @return 两点间的最大距离值
     * @throws IllegalArgumentException 如果 points 为 null 或包含少于 2 个点
     */
    double boundary(List<double[]> points);

    /**
     * 查找数据集中距离最远的两个样本点
     *
     * <p>遍历所有点对，返回距离最大的一对点及其距离值。</p>
     *
     * @param points 数据点列表
     * @return 最远点对信息，包含两个点的索引、向量和距离值
     * @throws IllegalArgumentException 如果 points 为 null 或包含少于 2 个点
     */
    FurthestPair furthestPair(List<double[]> points);

    /**
     * 计算数据集的扩展范围（最小外接超球直径）
     *
     * <p>先计算数据集的几何中心（各维度均值），再找出距离中心最远的点，
     * 返回该点到中心的距离，即数据集的最小外接圆/超球半径。
     * 数据集的扩展范围即为此半径。</p>
     *
     * @param points 数据点列表
     * @return 数据集到几何中心的扩展范围（半径），取最近点距离中心的最大值
     * @throws IllegalArgumentException 如果 points 为 null 或为空列表
     */
    double extent(List<double[]> points);

    /**
     * 计算两个点之间的 Elo 距离
     *
     * @param a 点 a 的特征向量
     * @param b 点 b 的特征向量
     * @return 两点间的距离
     */
    double distance(double[] a, double[] b);
}
