package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaIterator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class Qwen2ChatTranslator implements ITranslator<String, String>, AutoCloseable {

    private static final String DEFAULT_MODEL_ID = "qwen2-1.5b";

    /** 单次回答最大 token 数 */
    private static final int MAX_TOKENS = 512;
    /** 结束标记（Qwen chat 模板） */
    private static final String END_TOKEN = "<|im_end|>";

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
            InferenceParameters inferParams = new InferenceParameters(input)
                    .setTemperature(0.7f)
                    .setTopK(40)
                    .setNPredict(MAX_TOKENS);
            return generateWithLimit(inferParams);
        } catch (Exception e) {
            log.warn("[Qwen2] 推理失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 逐 token 生成，遇结束符或达到上限提前终止，避免无限输出。
     */
    private String generateWithLimit(InferenceParameters parameters) {
        StringBuilder sb = new StringBuilder();
        LlamaIterator it = model.generate(parameters).iterator();
        int tokens = 0;
        try {
            while (it.hasNext() && tokens < MAX_TOKENS) {
                LlamaOutput out = it.next();
                if (out == null || out.text == null) {
                    break;
                }
                String text = out.text;
                // 结束标记：去掉并终止
                int idx = text.indexOf(END_TOKEN);
                if (idx >= 0) {
                    sb.append(text, 0, idx);
                    break;
                }
                sb.append(text);
                tokens++;
            }
        } finally {
            it.cancel();
        }
        return sb.toString().trim();
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