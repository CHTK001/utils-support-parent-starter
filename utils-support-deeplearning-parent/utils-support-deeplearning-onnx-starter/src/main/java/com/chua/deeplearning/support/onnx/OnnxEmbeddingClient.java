package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalEmbeddingClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 ONNX Runtime 的本地文本嵌入客户端。
 * <p>
 * 调度 onnx 引擎下已注册的文本嵌入模型（如 bge、minilm、clip-text 等），
 * 统一以 {@link EmbeddingClient} 对外提供向量化能力。
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 *   float[] vector = EmbeddingClient.create("onnx", "")
 *       .model("bge-small-zh")
 *       .embedding("要向量化的文本");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("onnx")
public class OnnxEmbeddingClient extends AbstractLocalEmbeddingClient {

    /**
     * 构造 ONNX 文本嵌入客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxEmbeddingClient(EmbeddingClientSetting setting) {
        super("onnx", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, float[].class);
    }
}
