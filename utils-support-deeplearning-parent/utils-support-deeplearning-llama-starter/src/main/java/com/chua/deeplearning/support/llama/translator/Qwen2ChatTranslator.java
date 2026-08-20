package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class Qwen2ChatTranslator implements ITranslator<String, String>, AutoCloseable {

    private static final String DEFAULT_MODEL_ID = "qwen2-1.5b";

    private volatile LlamaModel model;
    private volatile boolean initialized;

    @Override
    public String name() {
        return "qwen2-1.5b";
    }

    @Override
    public String translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[Qwen2] 开始加载 GGUF 模型: {}", modelPath);
                        ModelParameters parameters = new ModelParameters()
                                .setModel(modelPath.toString())
                                .setCtxSize(4096)
                                .setThreads(Runtime.getRuntime().availableProcessors());
                        model = new LlamaModel(parameters);
                        initialized = true;
                        log.info("[Qwen2] 模型加载成功: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[Qwen2] 模型加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            InferenceParameters inferParams = new InferenceParameters(input);
            return model.complete(inferParams);
        } catch (Exception e) {
            log.warn("[Qwen2] 推理失败: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                log.warn("[Qwen2] 关闭模型失败: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}