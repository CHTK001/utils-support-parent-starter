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
