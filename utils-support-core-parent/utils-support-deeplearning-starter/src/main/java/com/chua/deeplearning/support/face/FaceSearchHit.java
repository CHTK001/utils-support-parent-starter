package com.chua.deeplearning.support.face;

import java.util.Collections;
import java.util.Map;

/**
 * 人脸检索命中结果。
 *
 * @param id       库内向量 标识
 * @param score    相似度/距离分数
 * @param feature  命中特征向量
 * @param metadata 元数据
 * @param content  附加内容
 * @author CH
 * @since 4.0.0.42
 */
public record FaceSearchHit(
        String id,
        double score,
        float[] feature,
        Map<String, Object> metadata,
        String content) {

    /**
     * 简化构造。
     *
     * @param id    标识
     * @param score 分数
     * @return face搜索hit的结果
     */
    public FaceSearchHit(String id, double score) {
        this(id, score, null, Collections.emptyMap(), null);
    }
}
