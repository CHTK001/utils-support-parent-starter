package com.chua.common.support.lang.algorithm.nearest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.jspecify.annotations.NullMarked;

/**
 * KNN（K-Nearest Neighbors）临近算法实现，基于欧几里得距离查找最近的 K 个邻居。
 *
 * <p>这是最经典的临近算法实现，核心思想如下：</p>
 * <ol>
 *   <li>计算目标点与数据集中每个样本点的欧几里得距离</li>
 *   <li>使用最大堆（固定容量为 K）维护距离最小的 K 个样本</li>
 *   <li>按距离升序返回结果</li>
 * </ol>
 *
 * <p>时间复杂度：O(N * D)，其中 N 为数据集大小，D 为特征向量维度。
 * 使用最大堆优化后，空间复杂度为 O(K * D)。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * NearestNeighborAlgorithm knn = new KnnNearestNeighbor();
 *
 * List<double[]> dataset = List.of(
 *     new double[]{1.0, 2.0},
 *     new double[]{3.0, 4.0},
 *     new double[]{5.0, 6.0}
 * );
 *
 * List<NeighborResult> results = knn.search(new double[]{2.0, 3.0}, dataset, 2);
 * // results.get(0) 是距离最小的邻居
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullMarked
public class KnnNearestNeighbor implements NearestNeighborAlgorithm {

    /**
     * 查找距离目标点最近的 K 个邻居
     *
     * @param target  目标点特征向量
     * @param dataset 数据集
     * @param k       邻居数量
     * @return 按距离升序排列的 K 个邻居
     * @throws IllegalArgumentException 如果参数不合法
     */
    @Override
    public List<NeighborResult> search(double[] target, List<double[]> dataset, int k) {
        validate(target, dataset, k);

        if (dataset.isEmpty()) {
            return List.of();
        }

        int actualK = Math.min(k, dataset.size());

        // 最大堆：容量为 actualK，堆顶是距离最大的元素
        PriorityQueue<NeighborResult> maxHeap = new PriorityQueue<>(
                actualK,
                Comparator.comparingDouble(NeighborResult::getDistance).reversed()
        );

        for (int i = 0; i < dataset.size(); i++) {
            double[] sample = dataset.get(i);
            double distance = euclideanDistance(target, sample);

            NeighborResult result = new NeighborResult(i, distance, sample);

            if (maxHeap.size() < actualK) {
                maxHeap.offer(result);
            } else {
                NeighborResult peek = maxHeap.peek();
                if (peek != null && distance < peek.getDistance()) {
                    maxHeap.poll();
                    maxHeap.offer(result);
                }
            }
        }

        // 按距离升序排序
        List<NeighborResult> sorted = new ArrayList<>(maxHeap);
        sorted.sort(Comparator.comparingDouble(NeighborResult::getDistance));

        return sorted;
    }

    /**
     * 查找距离目标点最近的一个邻居
     *
     * @param target  目标点特征向量
     * @param dataset 数据集
     * @return 最近的邻居，若数据集为空返回空结果
     */
    @Override
    public NeighborResult searchNearest(double[] target, List<double[]> dataset) {
        validate(target, dataset, 1);

        NeighborResult nearest = NeighborResult.EMPTY;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < dataset.size(); i++) {
            double[] sample = dataset.get(i);
            double distance = euclideanDistance(target, sample);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = new NeighborResult(i, distance, sample);
            }
        }

        return nearest;
    }

    /**
     * 计算两个向量之间的欧几里得距离
     *
     * <pre>
     * distance = sqrt( Σ (a[i] - b[i])² )
     * </pre>
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 欧几里得距离
     */
    private double euclideanDistance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * 参数校验
     *
     * @param target  目标向量
     * @param dataset 数据集
     * @param k       邻居数量
     * @throws IllegalArgumentException 如果参数不合法
     */
    private void validate(double[] target, List<double[]> dataset, int k) {
        if (target.length == 0) {
            throw new IllegalArgumentException("目标向量不能为空");
        }

        if (k <= 0) {
            throw new IllegalArgumentException("邻居数量 K 必须大于 0，当前值: " + k);
        }

        if (!dataset.isEmpty()) {
            double[] first = dataset.get(0);
            if (first.length != target.length) {
                throw new IllegalArgumentException(String.format(
                        "目标向量维度 (%d) 与数据集特征维度 (%d) 不一致",
                        target.length, first.length));
            }
        }
    }
}
