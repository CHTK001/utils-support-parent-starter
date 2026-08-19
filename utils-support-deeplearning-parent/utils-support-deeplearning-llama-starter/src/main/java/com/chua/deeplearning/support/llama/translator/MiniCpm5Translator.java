package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * MiniCPM5 翻译器。
 *
 * <p>通过 llama.cpp 加载 GGUF 模型进行文本生成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniCpm5Translator implements ITranslator<String, String>, AutoCloseable {

    /** 默认模型标识 */
    private static final String DEFAULT_MODEL_ID = "minicpm5";

    /** 模型 */
    private volatile LlamaModel model;
    /** 是否已初始化 */
    private volatile boolean initialized;

    @Override
    public String name() {
        return "minicpm5";
    }

    @Override
    public String translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[MiniCPM5] 开始加载 GGUF 模型: {}", modelPath);
                        ModelParameters parameters = new ModelParameters()
                                .setModel(modelPath.toString())
                                .setCtxSize(2048)
                                .setThreads(Runtime.getRuntime().availableProcessors());
                        model = new LlamaModel(parameters);
                        initialized = true;
                        log.info("[MiniCPM5] 模型加载成功: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[MiniCPM5] 模型加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            de.kherud.llama.InferenceParameters parameters = new de.kherud.llama.InferenceParameters(input);
            return model.complete(parameters);
        } catch (Exception e) {
            log.warn("[MiniCPM5] 推理失败: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                log.warn("[MiniCPM5] 关闭模型失败: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}