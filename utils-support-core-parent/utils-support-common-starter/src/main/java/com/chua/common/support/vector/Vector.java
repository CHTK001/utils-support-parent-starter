package com.chua.common.support.vector;

import java.util.Map;

/**
 * 向量数据模型，包含向量 ID、浮点数组、元数据和原文内容。
 *
 * @param id       向量唯一标识
 * @param data     向量浮点数组
 * @param metadata 元数据映射
 * @param content  原文内容（用于 RAG 检索后直接使用）
 * @author CH
 * @since 2024/12/12
 */
public record Vector(
        String id,
        float[] data,
        Map<String, Object> metadata,
        String content) {

    /**
     * 构造向量，不含元数据和原文。
     *
     * @param id   向量唯一标识
     * @param data 向量浮点数组
     */
    public Vector(String id, float[] data) {
        this(id, data, Map.of(), null);
    }

    /**
     * 构造向量，不含原文。
     *
     * @param id       向量唯一标识
     * @param data     向量浮点数组
     * @param metadata 元数据映射
     */
    public Vector(String id, float[] data, Map<String, Object> metadata) {
        this(id, data, metadata, null);
    }

    /**
     * 获取向量维度。
     *
     * @return 向量数组长度
     */
    public int dimension() {
        if (data == null) {
            return 0;
        }
        return data.length;
    }
}