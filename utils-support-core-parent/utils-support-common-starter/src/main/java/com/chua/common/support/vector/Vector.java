package com.chua.common.support.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 向量数据模型，包含向量 标识、浮点数组、元数据和原文内容。
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
     * 规范构造器：向量浮点数组 与 元数据映射 均做防御性拷贝。
     *
     * <p>value class 前置条件——底层表示必须深不可变，且对 空 值 敌对。</p>
     *
     * <p>浮点数组 与 元数据映射 刻意保留 空 语义：本类的 {@link #dimension()} 允许
     * {@code data} 为 空，{@code metadata} 也被 {@code writeShard} 等调用点显式判空，
     * 一旦改成拒绝 空 值就是把既有降级路径变成 500。</p>
     *
     * <p>元数据映射 采用 {@link LinkedHashMap} 复制而非 {@code Map.copyOf}：后者既不接受
     * null 值，也不保证迭代顺序，而 {@code writeShard} 会把 {@code metadata()} 的
     * {@code toString()} 落盘、再由 {@code parseMetadata} 反解，键序必须保持稳定。</p>
     */
    public Vector {
        id = Objects.requireNonNull(id, "id 不能为 null");
        data = data == null ? null : data.clone();
        metadata = metadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * 访问器覆写：返回内部浮点数组的副本。
     *
     * <p>value class 前置条件——数组组件必须深不可变。</p>
     *
     * @return 浮点数组副本；data 为 空 时返回 空
     */
    @Override
    public float[] data() {
        return data == null ? null : data.clone();
    }

    /**
     * 构造向量，不含元数据和原文。
     *
     * @param id   向量唯一标识
     * @param data 向量浮点数组
     * @return 向量的结果
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
     * @return 向量的结果
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
