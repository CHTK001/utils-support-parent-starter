package com.chua.deeplearning.support.image;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片检索命中结果。
 *
 * @param id       库内向量 标识
 * @param score    相似度/距离分数（含义依赖 向量storage 算法）
 * @param feature  命中向量（可能为 空）
 * @param metadata 元数据
 * @param content  附加内容（可选）
 * @author CH
 * @since 4.0.0.42
 */
public record ImageSearchHit(
        String id,
        double score,
        float[] feature,
        Map<String, Object> metadata,
        String content) {

    /**
     * 规范构造器：特征向量 与 元数据映射 做防御性拷贝。
     *
     * <p>value class 前置条件——数组与集合组件必须深不可变。</p>
     *
     * <p>两个组件都刻意保留 {@code null} 语义：简化构造传入 {@code null} 特征，
     * 且元数据可空。元数据映射采用 {@link LinkedHashMap} 复制而非 {@code Map.copyOf}：
     * 后者不接受 null 值，也不保证迭代顺序，而元数据来自向量库（键序需保持稳定）。</p>
     *
     * @param feature  命中向量
     * @param metadata 元数据映射
     */
    public ImageSearchHit {
        feature = feature == null ? null : feature.clone();
        metadata = metadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * 访问器覆写：返回内部特征向量的副本。
     *
     * @return 特征向量副本；无则返回 {@code null}
     */
    @Override
    public float[] feature() {
        return feature == null ? null : feature.clone();
    }

    /**
     * 简化构造。
     *
     * @param id    向量 标识
     * @param score 分数
     * @return 镜像搜索hit的结果
     */
    public ImageSearchHit(String id, double score) {
        this(id, score, null, Collections.emptyMap(), null);
    }

    /**
     * 带特征向量构造。
     *
     * @param id      向量 标识
     * @param score   分数
     * @param feature 特征
     * @return 镜像搜索hit的结果
     */
    public ImageSearchHit(String id, double score, float[] feature) {
        this(id, score, feature, Collections.emptyMap(), null);
    }
}
