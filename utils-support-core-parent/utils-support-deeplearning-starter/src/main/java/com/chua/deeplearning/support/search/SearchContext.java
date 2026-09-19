package com.chua.deeplearning.support.search;

import com.chua.common.support.vector.Vector;

import java.util.List;

/**
 * 通用特征检索上下文。
 *
 * <p>承载一次检索的状态：原始图像、提取特征、命中向量列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SearchContext {

    /**
     * 原始图像数据。
     */
    private final byte[] imageData;

    /**
     * 查询特征。
     */
    private float[] feature;

    /**
     * 检索 Top-K。
     */
    private final int topK;

    /**
     * 命中向量列表。
     */
    private List<Vector> vectors = List.of();

    /**
     * 构造。
     *
     * @param imageData 原始图像
     * @param topK      检索条数
     */
    public SearchContext(byte[] imageData, int topK) {
        this.imageData = imageData;
        this.topK = Math.max(1, topK);
    }

    /**
     * 原始图像。
     *
     * @return 图像字节
     */
    public byte[] imageData() {
        return imageData;
    }

    /**
     * 查询特征。
     *
     * @return 特征
     */
    public float[] feature() {
        return feature;
    }

    /**
     * 设置查询特征。
     *
     * @param feature 特征
     */
    public void feature(float[] feature) {
        this.feature = feature;
    }

    /**
     * 检索条数。
     *
     * @return Top-K
     */
    public int topK() {
        return topK;
    }

    /**
     * 命中向量。
     *
     * @return 向量列表
     */
    public List<Vector> vectors() {
        return vectors;
    }

    /**
     * 设置命中向量。
     *
     * @param vectors 向量列表
     */
    public void vectors(List<Vector> vectors) {
        this.vectors = vectors == null ? List.of() : vectors;
    }
}
