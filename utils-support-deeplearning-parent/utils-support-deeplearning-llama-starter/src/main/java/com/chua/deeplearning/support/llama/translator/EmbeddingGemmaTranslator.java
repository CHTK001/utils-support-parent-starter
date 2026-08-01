package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * EmbeddingGemma-300M 文本向量化翻译器，基于 llama.cpp 绑定。
 * <p>
 * 输入：字符串；输出：浮点向量。模型首次调用时按需加载并缓存。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class EmbeddingGemmaTranslator implements ITranslator<String, float[]>, AutoCloseable {

    /**
     * 默认模型 id
     */
    private static final String DEFAULT_MODEL_ID = "embeddinggemma-300m";

    /**
     * llama.cpp 模型实例，首次 translate 时懒加载
     */
    private volatile LlamaModel model;

    /**
     * 模型是否已初始化
     */
    private volatile boolean initialized;

    /**
     * @return 模型名称 {@code embeddinggemma-300m}
     */
    @Override
    public String name() {
        return "embeddinggemma-300m";
    }

    /**
     * 将文本转为浮点向量。
     *
     * @param input 输入文本
     * @return 浮点向量
     */
    @Override
    public float[] translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[EmbeddingGemma] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[EmbeddingGemma] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[EmbeddingGemma] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            float[] embedding = model.embed(input);
            return embedding;
        } catch (Exception e) {
            log.warn("[EmbeddingGemma] inference failed: {}", e.getMessage());
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
                log.warn("[EmbeddingGemma] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}
