package com.chua.deeplearning.support.gpu_llama3.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaIterator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * Llama 3 GPU 对话翻译器。
 * <p>
 * 利用 llama.cpp 的 GPU 加速能力进行 Llama 3 模型的文本生成推理。
 * 通过 ModelParameters 配置 GPU 层数和上下文大小。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Llama3ChatTranslator implements ITranslator<String, String>, AutoCloseable {

    /** 单次回答最大 token 数 */
    private static final int MAX_TOKENS = 512;
    /** Llama 3 chat 结束标记 */
    private static final String END_TOKEN = "<|end_of_text|>";
    /** GPU 加速：分配给 GPU 的层数，-1 表示自动 */
    private static final int GPULAYERS = -1;
    /** 上下文大小 */
    private static final int CTX_SIZE = 4096;

    private final String modelId;
    private volatile LlamaModel model;
    private volatile boolean initialized;

    /**
     * 默认构造器，使用 llama-3-8b-it 模型。
     */
    public Llama3ChatTranslator() {
        this("llama-3-8b-it");
    }

    /**
     * 构造器。
     *
     * @param modelId 模型 ID
     */
    public Llama3ChatTranslator(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public String name() {
        return modelId;
    }

    @Override
    public String translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(modelId);
                        log.info("[Llama3-GPU:{}] 开始加载 GGUF 模型（GPU 加速）: {}", modelId, modelPath);
                        
                        // 配置 GPU 参数
                        ModelParameters parameters = new ModelParameters()
                                .setModel(modelPath.toString())
                                .setCtxSize(CTX_SIZE)
                                .setThreads(Runtime.getRuntime().availableProcessors())
                                .setGpuLayers(GPULAYERS); // GPU 加速
                        model = new LlamaModel(parameters);
                        initialized = true;
                        log.info("[Llama3-GPU:{}] 模型加载成功", modelId);
                    } catch (Exception e) {
                        throw new RuntimeException("[Llama3-GPU:" + modelId + "] 模型加载失败: " + e.getMessage(), e);
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
            log.warn("[Llama3-GPU:{}] 推理失败: {}", modelId, e.getMessage());
            return "";
        }
    }

    /**
     * 逐 token 生成，遇结束符或达到上限提前终止。
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
                log.info("[Llama3-GPU:{}] 模型已关闭", modelId);
            } catch (Exception e) {
                log.warn("[Llama3-GPU:{}] 关闭模型失败: {}", modelId, e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}
