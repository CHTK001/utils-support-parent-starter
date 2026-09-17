package com.chua.common.support.ai.embedding;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;

import java.util.List;

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
    }
}
