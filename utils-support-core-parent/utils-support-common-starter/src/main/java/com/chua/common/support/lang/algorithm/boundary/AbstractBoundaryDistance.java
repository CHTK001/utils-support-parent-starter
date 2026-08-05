package com.chua.common.support.lang.algorithm.boundary;

import java.util.List;

/**
 * 边界距离算法抽象基类，提供 {@link #boundary(List)}、{@link #furthestPair(List)} 和 {@link #extent(List)}
 * 三个方法的通用实现，子类只需实现具体的距离度量公式 {@link #distance(double[], double[])}。
 *
 * <p>公共逻辑：</p>
 * <ul>
 *   <li>{@code boundary} — 双重循环遍历所有点对，返回最大距离</li>
 *   <li>{@code furthestPair} — 双重循环遍历所有点对，返回距离最远的一对</li>
 *   <li>{@code extent} — 先计算几何中心（各维度均值），再返回距离中心最远的点的距离</li>
 * </ul>
 *
 * <p>子类需要实现：</p>
 * <ul>
 *   <li>{@link #distance(double[], double[])} — 具体的距离度量公式</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public abstract class AbstractBoundaryDistance implements BoundaryDistanceAlgorithm {

    /**
     * 数据点最小数量
     */
    private static final int MIN_POINTS_FOR_BOUNDARY = 2;

    /**
     * 数据点最小数量（用于扩展范围计算）
     */
    private static final int MIN_POINTS_FOR_EXTENT = 1;

    @Override
    public double boundary(List<double[]> points) {
        validatePoints(points, MIN_POINTS_FOR_BOUNDARY);

        double maxDistance = 0.0;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dist = distance(points.get(i), points.get(j));
                if (dist > maxDistance) {
                    maxDistance = dist;
                }
            }
        }
        return maxDistance;
    }

    @Override
    public FurthestPair furthestPair(List<double[]> points) {
        validatePoints(points, MIN_POINTS_FOR_BOUNDARY);

        double maxDistance = 0.0;
        int firstIndex = -1;
        int secondIndex = -1;

        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dist = distance(points.get(i), points.get(j));
                if (dist > maxDistance) {
                    maxDistance = dist;
                    firstIndex = i;
                    secondIndex = j;
                }
            }
        }

        if (firstIndex < 0 || secondIndex < 0) {
            return FurthestPair.EMPTY;
        }

        return new FurthestPair(firstIndex, secondIndex,
                points.get(firstIndex), points.get(secondIndex), maxDistance);
    }

    @Override
    public double extent(List<double[]> points) {
        validatePoints(points, MIN_POINTS_FOR_EXTENT);

        int dimension = points.get(0).length;
        double[] center = computeCenter(points, dimension);

        double maxRadius = 0.0;
        for (double[] point : points) {
            double dist = distance(center, point);
            if (dist > maxRadius) {
                maxRadius = dist;
            }
        }
        return maxRadius;
    }

    /**
     * 计算数据集的几何中心（各维度坐标的算术平均值）
     *
     * @param points    数据点列表
     * @param dimension 特征向量维度
     * @return 几何中心坐标向量
     */
    private double[] computeCenter(List<double[]> points, int dimension) {
        double[] center = new double[dimension];
        for (double[] point : points) {
            for (int d = 0; d < dimension; d++) {
                center[d] += point[d];
            }
        }
        for (int d = 0; d < dimension; d++) {
            center[d] /= points.size();
        }
        return center;
    }

    /**
     * 参数校验
     *
     * @param points   数据点列表
     * @param minSize  最小数量要求
     * @throws IllegalArgumentException 如果 points 为 null 或数量不足
     */
    private void validatePoints(List<double[]> points, int minSize) {
        if (points == null) {
            throw new IllegalArgumentException("数据点列表不能为 null");
        }
        if (points.size() < minSize) {
            throw new IllegalArgumentException(String.format(
                    "数据点数量不足，至少需要 %d 个点，当前: %d", minSize, points.size()));
        }
        int dimension = points.get(0).length;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).length != dimension) {
                throw new IllegalArgumentException(String.format(
                        "数据点维度不一致，第 0 个点维度: %d，第 %d 个点维度: %d",
                        dimension, i, points.get(i).length));
            }
        }
    }
}