package com.chua.jitllm.support.embedding;

import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalEmbeddingClient;
import com.chua.jitllm.support.JitllmConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * jitLLM 本地文本嵌入客户端（SPI provider="jitllm"）。
 *
 * <p>基于 llama.cpp 进程内 GGUF 推理（{@link JitllmEmbeddingTranslator}）实现本地文本向量化，
 * 模型清单来自 {@code ModelRegistry} 中以 {@code jitllm} 提供者注册的嵌入模型
 * （bge-m3、bge-small-zh-v1.5、nomic-embed-text-v1.5 等）。本地推理无需 API Key。</p>
 *
 * <p>调用示例：
 * <pre>{@code
 *   float[] vector = EmbeddingClient.create("jitllm", "")
 *       .model("bge-m3")
 *       .embedding("要向量化的文本");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi(JitllmConstants.PROVIDER)
public class JitllmEmbeddingClient extends AbstractLocalEmbeddingClient {

    /**
     * 创建 jitLLM 嵌入客户端。
     *
     * <p>构造阶段只记录模型名与维度偏好，不加载权重；模型在首次嵌入时才装载进
     * GPU/内存，避免无推理场景的启动开销。本地推理无需 API Key。</p>
     *
     * @param setting 客户端配置（provider 应为 "jitllm"，model 为注册的嵌入模型标识）
     */
    public JitllmEmbeddingClient(EmbeddingClientSetting setting) {
        super(JitllmConstants.PROVIDER, setting);
    }
}
