package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalEmbeddingClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 llama.cpp (GGUF) 的本地文本嵌入客户端。
 * <p>
 * 调度 llama 引擎下已注册的文本嵌入模型（如 embeddinggemma、otzaria、bitnet 等），
 * 统一以 {@link EmbeddingClient} 对外提供向量化能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("llama")
public class LlamaEmbeddingClient extends AbstractLocalEmbeddingClient {

    /**
     * 构造 llama 文本嵌入客户端。
     *
     * @param setting 客户端配置
     */
    public LlamaEmbeddingClient(EmbeddingClientSetting setting) {
        super("llama", setting);
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, float[].class);
    }
}
