package com.chua.common.support.lang.algorithm.boundary;

import org.jspecify.annotations.NullMarked;

/**
 * 基于切比雪夫距离（Chebyshev Distance）的边界距离算法实现。
 *
 * <p>切比雪夫距离又称棋盘距离（Chessboard Distance），表示在国际象棋棋盘上
 * 国王从一格走到另一格所需的最小步数，计算公式如下：</p>
 * <pre>
 * distance = max( |a[i] - b[i]| )  对所有维度 i 取最大值
 * </pre>
 *
 * <p>适用于离散网格、棋盘游戏、L∞ 度量、仓库物流中叉车可同时沿两轴移动等场景。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * BoundaryDistanceAlgorithm algorithm = new ChebyshevBoundaryDistance();
 * double maxDist = algorithm.boundary(points);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullMarked
public class ChebyshevBoundaryDistance extends AbstractBoundaryDistance {

    @Override
    public double distance(double[] a, double[] b) {
        double max = 0.0;
        for (int i = 0; i < a.length; i++) {
            double absDiff = Math.abs(a[i] - b[i]);
            if (absDiff > max) {
                max = absDiff;
            }
        }
        return max;
    }
}