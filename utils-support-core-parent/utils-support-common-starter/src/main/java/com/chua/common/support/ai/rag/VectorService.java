package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.embedding.EmbeddingClient;

/**
* 向量服务接口，提供文本向量化能力。
* <p>
* 可通过 {@link #from(EmbeddingClient)} 从 {@link EmbeddingClient} 适配，
* 也可以直接实现自定义的向量化逻辑。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface VectorService {

    /**
    * 从 EmbeddingClient 创建 VectorService。
    *
    * @param embeddingClient 嵌入向量客户端
    * @return VectorService 实例
    */
    static VectorService from(EmbeddingClient embeddingClient) {
        return new VectorService() {
            @Override
            /** Embed */
            public float[] embed(String text) {
                return embeddingClient.embedding(text);
            }

            @Override
            /** EmbedBatch */
            public float[][] embedBatch(String[] texts) {
                return embeddingClient.embeddingBatch(texts);
            }
        };
    }

    /**
            * 单文本向量化。
            *
            * @param text 待向量化的文本
            * @return 浮点数向量
            */
    float[] embed(String text);

    /**
    * 批量文本向量化。
    *
    * @param texts 待向量化的文本数组
    * @return 浮点数向量数组，顺序与输入一致
    */
    float[][] embedBatch(String[] texts);
}
