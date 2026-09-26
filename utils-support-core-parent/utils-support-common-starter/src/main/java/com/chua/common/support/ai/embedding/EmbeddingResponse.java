package com.chua.common.support.ai.embedding;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;

import java.util.List;
import java.util.Objects;

/**
 * AI 嵌入向量响应。
 * <p>
 * 表示一次嵌入向量调用的响应结果，包含向量列表和用量信息。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Builder
public record EmbeddingResponse(
        /**
         * 嵌入向量列表。
         * <p>
         * 每个 {@link Embedding} 对象包含向量数据和元信息。
         * 单文本输入时列表长度为 1，批量输入时长度与输入文本数一致。
         * </p>
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
     * @author CH
     * @since 4.0.0.42
     */
    @Builder
    public record Embedding(
            /**
             * 向量数据。
             * <p>
             * 浮点数数组，长度由模型决定（如 text-embedding-3-small 默认为 1536）。
             * </p>
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
