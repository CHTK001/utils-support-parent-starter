package com.chua.common.support.lang.algorithm.boundary;


/**
* 基于欧几里得距离（Euclidean Distance）的边界距离算法实现。
*
* <p>欧几里得距离是最常用的距离度量，表示两点之间的直线距离，
* 计算公式如下：</p>
* <pre>
* distance = sqrt( Σ (a[i] - b[i])² )
* </pre>
*
* <p>适用于连续数值、物理空间、几何分析等场景。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* BoundaryDistanceAlgorithm algorithm = new EuclideanBoundaryDistance();
*
* List<double[]> points = List.of(
*     new double[]{0.0, 0.0},
*     new double[]{3.0, 4.0},
*     new double[]{6.0, 8.0}
* );
*
* double maxDist = algorithm.boundary(points);
* FurthestPair pair = algorithm.furthestPair(points);
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public class EuclideanBoundaryDistance extends AbstractBoundaryDistance {

    @Override
    /** Distance */
    public double distance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}