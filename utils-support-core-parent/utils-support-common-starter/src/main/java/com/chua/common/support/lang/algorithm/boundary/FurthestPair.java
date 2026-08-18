package com.chua.common.support.lang.algorithm.boundary;


/**
 * 最远点对结果，封装数据集中距离最远的两个样本点信息。
 *
 * <p>当数据集空或不足两个点时，返回 {@link #EMPTY} 常量，
 * 其距离值为 {@link Double#NaN}。</p>
 *
 * @author CH
 * @since 1.0.0
 */
public class FurthestPair {

    /** 空结果常量 */
    /** 是否为空 */
    public static final FurthestPair EMPTY = new FurthestPair(-1, -1, new double[0], new double[0], Double.NaN);

    /** 第一个点在数据集中的索引 */
    /** 首个索引 */
    private final int firstIndex;

    /** 第二个点在数据集中的索引 */
    /** Second索引 */
    private final int secondIndex;

    /** 第一个点的特征向量 */
    /** 首个vector */
    private final double[] firstVector;

    /** 第二个点的特征向量 */
    /** Secondvector */
    private final double[] secondVector;

    /** 两点间的距离 */
    /** Distance */
    private final double distance;

    /**
     * 创建最远点对结果
     *
     * @param firstIndex  第一个点索引
     * @param secondIndex 第二个点索引
     * @param firstVector 第一个点向量
     * @param secondVector 第二个点向量
     * @param distance    距离值
     */
    public FurthestPair(int firstIndex, int secondIndex, double[] firstVector, double[] secondVector, double distance) {
        this.firstIndex = firstIndex;
        this.secondIndex = secondIndex;
        this.firstVector = firstVector;
        this.secondVector = secondVector;
        this.distance = distance;
    }

    /**
     * 获取第一个点的索引
     *
     * @return 第一个点在数据集中的索引
     */
    public int getFirstIndex() {
        return firstIndex;
    }

    /**
     * 获取第二个点的索引
     *
     * @return 第二个点在数据集中的索引
     */
    public int getSecondIndex() {
        return secondIndex;
    }

    /**
     * 获取第一个点的特征向量
     *
     * @return 第一个点的 N 维向量
     */
    public double[] getFirstVector() {
        return firstVector;
    }

    /**
     * 获取第二个点的特征向量
     *
     * @return 第二个点的 N 维向量
     */
    public double[] getSecondVector() {
        return secondVector;
    }

    /**
     * 获取两点间的距离
     *
     * @return 距离值，无效结果时为 NaN
     */
    public double getDistance() {
        return distance;
    }

    /**
     * 是否为有效的最远点对
     *
     * @return 如果两个索引均 >= 0 则为有效结果
     */
    public boolean isValid() {
        return firstIndex >= 0 && secondIndex >= 0;
    }

    @Override
    public String toString() {
        return String.format("FurthestPair{first=%d, second=%d, distance=%.6f}", firstIndex, secondIndex, distance);
    }
}