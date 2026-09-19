package com.chua.common.support.lang.algorithm.nearest;


/**
 * 临近算法搜索结果，封装邻居样本的索引、距离和数据向量。
 *
 * <p>每个 {@code NeighborResult} 表示一个被查询到的邻居样本，
 * 包含其在原数据集中的索引位置、与目标点的距离值以及完整的特征向量。
 *
 * <h2>特殊值</h2>
 * <ul>
 *   <li>{@link #EMPTY} — 空结果常量，当数据集为空时返回，距离为 {@link Double#MAX_VALUE}</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public class NeighborResult {

    /** 空结果常量，表示查询无结果 */
    public static final NeighborResult EMPTY = new NeighborResult(-1, Double.MAX_VALUE, new double[0]);

    /** 样本在原数据集中的索引位置 */
    private final int index;

    /** 与目标点的距离值 */
    private final double distance;

    /** 样本的完整特征向量 */
    private final double[] vector;

    /**
     * 创建邻居结果
     *
     * @param index    样本索引
     * @param distance 距离值
     * @param vector   特征向量
     */
    public NeighborResult(int index, double distance, double[] vector) {
        this.index = index;
        this.distance = distance;
        this.vector = vector;
    }

    /**
     * 获取样本索引
     *
     * @return 样本在原数据集中的索引
     */
    public int getIndex() {
        return index;
    }

    /**
     * 获取距离值
     *
     * @return 与目标点的距离
     */
    public double getDistance() {
        return distance;
    }

    /**
     * 获取特征向量
     *
     * @return 样本的 N 维特征向量
     */
    public double[] getVector() {
        return vector;
    }

    /**
     * 是否为有效结果
     *
     * @return 如果索引 >= 0 则为有效结果
     */
    public boolean isValid() {
        return index >= 0;
    }

    @Override
    /** ToString */
    public String toString() {
        return String.format("NeighborResult{index=%d, distance=%.6f}", index, distance);
    }
}
