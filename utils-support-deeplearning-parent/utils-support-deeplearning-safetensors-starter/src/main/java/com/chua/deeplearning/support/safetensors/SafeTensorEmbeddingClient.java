package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalEmbeddingClient;

import java.util.List;

/**
 * SafeTensor 本地文本嵌入客户端（HTTP 网关）。
 * <p>
 * 通过本地 SafeTensorService（localhost:8765）调度文本嵌入模型（qwen3-embedding、gte 等），
 * 统一以 {@link EmbeddingClient} 对外提供向量化能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("safetensors")
public class SafeTensorEmbeddingClient extends AbstractLocalEmbeddingClient {

    /**
     * 构造 SafeTensor 文本嵌入客户端。
     *
     * @param setting 客户端配置
     */
    public SafeTensorEmbeddingClient(EmbeddingClientSetting setting) {
        super("safetensors", setting);
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return SafeTensorModels.ofType("text_embedding");
    }
}
