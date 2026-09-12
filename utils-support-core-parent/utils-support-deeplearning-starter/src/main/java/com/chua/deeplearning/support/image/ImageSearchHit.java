package com.chua.deeplearning.support.image;

import java.util.Collections;
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
