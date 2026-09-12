package com.chua.common.support.lang.algorithm.boundary;


/**
* 基于曼哈顿距离（Manhattan Distance）的边界距离算法实现。
*
* <p>曼哈顿距离又称城市街区距离（City Block Distance），表示在网格状路网中
* 两点间的路径长度，计算公式如下：</p>
* <pre>
* distance = Σ |a[i] - b[i]|
* </pre>
*
* <p>适用于网格状布局、离散空间、出租车路线规划、L1 正则化等场景。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* BoundaryDistanceAlgorithm algorithm = new ManhattanBoundaryDistance();
* double maxDist = algorithm.boundary(points);
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public class ManhattanBoundaryDistance extends AbstractBoundaryDistance {

    @Override
    /** Distance */
    public double distance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum;
    }
}