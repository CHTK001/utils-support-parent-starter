package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * Gemma-4-E2B 多模态文本生成翻译器，基于 llama.cpp 绑定。
 * <p>
 * 输入：字符串；输出：模型补全结果字符串。模型首次调用时按需加载并缓存。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class Gemma4Translator implements ITranslator<String, String>, AutoCloseable {

    /**
     * 默认模型 id
     */
    private static final String DEFAULT_MODEL_ID = "gemma-4-e2b";

    /**
     * llama.cpp 模型实例，首次 translate 时懒加载
     */
    private volatile LlamaModel model;

    /**
     * 模型是否已初始化
     */
    private volatile boolean initialized;

    /**
     * @return 模型名称 {@code gemma-4-e2b}
     */
    @Override
    public String name() {
        return "gemma-4-e2b";
    }

    /**
     * 用模型对输入进行补全生成。
     *
     * @param input 输入文本
     * @return 模型输出文本
     */
    @Override
    public String translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[Gemma4] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[Gemma4] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[Gemma4] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            return model.complete(new InferenceParameters(input));
        } catch (Exception e) {
            log.warn("[Gemma4] inference failed: {}", e.getMessage());
            return "";
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
                log.warn("[Gemma4] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}