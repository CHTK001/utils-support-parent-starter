package com.chua.common.support.ai.embedding;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;

import java.util.List;
import java.util.Objects;

/**
 * AI 嵌入向量响应。
 * 表示一次嵌入向量调用的响应结果，包含向量列表和用量信息。
 *
 * @param embeddings 嵌入向量列表，顺序与本次输入的文本一一对应：单文本调用时长度为 1，
 *                   批量调用时长度等于输入文本数，传入空文本数组时为 0（空列表）。
 *                   恒非 null 且元素非 null，紧凑构造器以 {@link List#copyOf} 强制
 * @param usage     本次调用的用量信息，含模型名、提供方、请求起始时刻、耗时（单位：毫秒）
 *                   与 Token 计数（单位：token）。可为 null：{@link EmbeddingClient} 的默认实现
 *                   以及本地推理客户端只回报向量不回报用量，仅云端客户端会填充
 * @author CH
 * @since 4.0.0.42
 */
@Builder
public record EmbeddingResponse(
        /**
         * 嵌入向量列表。
         * 每个 {@link Embedding} 对象包含向量数据和元信息。
         * 单文本输入时列表长度为 1，批量输入时长度与输入文本数一致。
         */
        List<Embedding> embeddings,
        /**
         * 用量信息，包含本次调用的 Token 用量信息。
         */
        AiUsage usage
) {

    /**
     * 规范构造器：对嵌入向量列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 全部构造点（各 EmbeddingClient 实现）传入的列表均非空且元素非空，
     * 因此使用 {@link List#copyOf} 拒绝 null 列表与 null 元素。</p>
     *
     * @param embeddings 嵌入向量列表
     * @param usage     用量信息，可为 null
     */
    public EmbeddingResponse {
        embeddings = List.copyOf(Objects.requireNonNull(embeddings, "embeddings 不能为 null"));
    }

    /**
     * 单个文本的嵌入向量。
     *
     * @param vector     向量数据，浮点分量数组，长度即嵌入模型的输出维度
     *                   （如 text-embedding-3-small 默认为 1536，部分模型支持压缩到 256/512）。
     *                   可为 null：各客户端实现均以 {@code v != null ? v.length : 0} 显式判空后
     *                   才取维度，说明向量缺失是合法状态。紧凑构造器与 {@link #vector()} 覆写
     *                   都会克隆数组，外部无法改动内部引用
     * @param index      该向量在本次批量输入中的位置，从 0 开始；单文本调用固定为 0。
     *                   可为 null：builder 未显式设置时即为 null
     * @param dimensions 向量维度（单位：维），等于 {@code vector.length}；各实现统一传
     *                   {@code v != null ? v.length : 0}，故 {@code vector} 为 null 时为 0。
     *                   可为 null：builder 未显式设置时即为 null
     * @author CH
     * @since 4.0.0.42
     */
    @Builder
    public record Embedding(
            /**
             * 向量数据。
             * 浮点数数组，长度由模型决定（如 text-embedding-3-small 默认为 1536）。
             */
            float[] vector,
            /**
             * 向量索引，批量输入时表示该向量在输入列表中的位置。
             */
            Integer index,
            /**
             * 向量维度，向量数组的长度。
             */
            Integer dimensions
    ) {

        /**
         * 规范构造器：向量数组做防御性拷贝。
         *
         * <p>value class 前置条件——数组组件必须深不可变。
         * {@link EmbeddingClient} 各实现显式判空 {@code vector != null}，
         * 说明 vector 允许为 null，故此处保留 null 语义。</p>
         *
         * @param vector     向量数据，可为 null
         * @param index      向量索引
         * @param dimensions 向量维度
         */
        public Embedding {
            vector = vector == null ? null : vector.clone();
        }

        /**
         * 访问器覆写：返回内部向量数组的副本。
         *
         * <p>value class 前置条件——外部不得持有内部数组引用。</p>
         *
         * @return 向量数组副本，vector 为 null 时返回 null
         */
        @Override
        public float[] vector() {
            return vector == null ? null : vector.clone();
        }
    }
}
