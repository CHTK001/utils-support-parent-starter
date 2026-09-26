package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单个人脸识别结果（检测框 + 活体 + 检索命中）。
 *
 * @param box       人脸框
 * @param feature   人脸特征（活体失败可为 空）
 * @param hits      向量库检索结果
 * @param live      是否通过活体（未配置活体时为 true）
 * @param liveScore 活体分数
 * @author CH
 * @since 4.0.0.42
 */
public record FaceIdentifyHit(
        PredictRectangle box,
        float[] feature,
        List<FaceSearchHit> hits,
        boolean live,
        float liveScore) {

    /**
     * 规范构造器：特征向量 与 命中列表 做防御性拷贝。
     *
     * <p>value class 前置条件——数组与集合组件必须深不可变。</p>
     *
     * <p>两个组件都刻意保留 {@code null} 语义：{@code FaceIdentify} 在活体不通过时
     * 显式传入 {@code null} 特征与空命中列表，而 {@link #bestId()}、{@link #bestScore()}、
     * {@link #bestMetadata()} 也按可空设计（命中列表可能为 {@code null}）。</p>
     *
     * @param feature 人脸特征
     * @param hits    命中列表
     */
    public FaceIdentifyHit {
        feature = feature == null ? null : feature.clone();
        hits = hits == null ? null : List.copyOf(hits);
    }

    /**
     * 访问器覆写：返回内部特征向量的副本。
     *
     * @return 特征向量副本；活体失败未取特征时返回 {@code null}
     */
    @Override
    public float[] feature() {
        return feature == null ? null : feature.clone();
    }

    /**
     * 兼容旧构造（默认活体通过）。
     *
     * @param box     框
     * @param feature 特征
     * @param hits    命中
     * @return FaceIdentifyHit的结果
     */
    public FaceIdentifyHit(PredictRectangle box, float[] feature, List<FaceSearchHit> hits) {
        this(box, feature, hits, true, 1.0f);
    }

    /**
     * 取第一命中 标识。
     *
     * @return id 或 空
     */
    public String bestId() {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        return hits.getFirst().id();
    }

    /**
     * 取第一命中分数。
     *
     * @return score
     */
    public double bestScore() {
        if (hits == null || hits.isEmpty()) {
            return 0d;
        }
        return hits.getFirst().score();
    }

    /**
     * 第一命中元数据。
     *
     * @return metadata
     */
    public Map<String, Object> bestMetadata() {
        if (hits == null || hits.isEmpty() || hits.getFirst().metadata() == null) {
            return Collections.emptyMap();
        }
        return hits.getFirst().metadata();
    }
}
