package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * BitNet-Embedding-0.6B 文本向量化翻译器，基于 llama.cpp 绑定。
 * <p>
 * 输入：字符串；输出：文本的浮点向量。模型首次调用时按需加载并缓存。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class BitnetEmbeddingTranslator implements ITranslator<String, float[]>, AutoCloseable {

    /**
     * 默认模型 id
     */
    private static final String DEFAULT_MODEL_ID = "bitnet-embedding";

    /**
     * llama.cpp 模型实例，首次 translate 时懒加载
     */
    private volatile LlamaModel model;

    /**
     * 模型是否已初始化
     */
    private volatile boolean initialized;

    /**
     * @return 模型名称 {@code bitnet-embedding}
     */
    @Override
    public String name() {
        return "bitnet-embedding";
    }

    /**
     * 将文本转为浮点向量；首次调用懒加载模型。
     *
     * @param input 输入文本
     * @return 浮点向量；失败时返回空数组
     */
    @Override
    public float[] translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[BitnetEmbedding] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[BitnetEmbedding] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[BitnetEmbedding] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            float[] embedding = model.embed(input);
            return embedding;
        } catch (Exception e) {
            log.warn("[BitnetEmbedding] inference failed: {}", e.getMessage());
            return new float[0];
        }
    }

    /**
     * 关闭模型并重置初始化标志。
     */
    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                log.warn("[BitnetEmbedding] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}