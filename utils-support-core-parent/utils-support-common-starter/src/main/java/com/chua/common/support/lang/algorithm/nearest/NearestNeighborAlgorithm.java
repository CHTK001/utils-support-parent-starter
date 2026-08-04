package com.chua.common.support.lang.algorithm.nearest;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 临近算法顶级接口，定义最近邻搜索的统一抽象。
 *
 * <p>临近算法（Nearest Neighbor）用于在多维空间中查找距离目标点最接近的 K 个样本点，
 * 广泛应用于分类、聚类、推荐系统、异常检测等场景。
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #search(double[], List, int)} — 查找距离目标点最近的 K 个邻居</li>
 *   <li>{@link #searchNearest(double[], List)} — 查找距离目标点最近的一个邻居</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建 KNN 算法实例
 * NearestNeighborAlgorithm knn = new KnnNearestNeighbor();
 *
 * // 准备数据集（N 维特征向量列表）
 * List<double[]> dataset = List.of(
 *     new double[]{1.0, 2.0},
 *     new double[]{3.0, 4.0},
 *     new double[]{5.0, 6.0}
 * );
 *
 * // 查找最近的 2 个邻居
 * List<NeighborResult> results = knn.search(new double[]{2.0, 3.0}, dataset, 2);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public interface NearestNeighborAlgorithm {

    /**
     * 查找距离目标点最近的 K 个邻居
     *
     * @param target  目标点特征向量
     * @param dataset 数据集，包含 N 维特征向量的列表
     * @param k       返回的邻居数量，必须大于 0 且不大于数据集大小
     * @return 按距离升序排列的 K 个邻居结果列表
     * @throws IllegalArgumentException 如果 k 超出范围或 dataset 为空
     */
    List<NeighborResult> search(double[] target, List<double[]> dataset, int k);

    /**
     * 查找距离目标点最近的一个邻居
     *
     * @param target  目标点特征向量
     * @param dataset 数据集，包含 N 维特征向量的列表
     * @return 最近的邻居结果，若 dataset 为空则返回 {@link NeighborResult#EMPTY}
     */
    NeighborResult searchNearest(double[] target, List<double[]> dataset);
}