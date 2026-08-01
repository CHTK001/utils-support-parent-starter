package com.chua.deeplearning.support.llama.translator;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * NeuTts-2E 文本转语音翻译器，基于 llama.cpp 绑定。
 * <p>
 * 输入：字符串；输出：文本表示的字节（占位实现，待原生 TTS 解码接入）。模型首次调用时按需加载并缓存。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class NeuTts2eTranslator implements ITranslator<String, byte[]>, AutoCloseable {

    /**
     * 默认模型 id
     */
    private static final String DEFAULT_MODEL_ID = "neutts-2e";

    /**
     * llama.cpp 模型实例，首次 translate 时懒加载
     */
    private volatile LlamaModel model;

    /**
     * 模型是否已初始化
     */
    private volatile boolean initialized;

    /**
     * @return 模型名称 {@code neutts-2e}
     */
    @Override
    public String name() {
        return "neutts-2e";
    }

    /**
     * TTS 生成文本的 UTF-8 字节（占位实现，待原生 TTS 解码接入）。
     *
     * @param input 输入文本
     * @return 字节表示
     */
    @Override
    public byte[] translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(DEFAULT_MODEL_ID);
                        log.info("[NeuTts2e] loading: {}", modelPath);
                        ModelParameters modelParams = new ModelParameters().setModel(modelPath.toString());
                        model = new LlamaModel(modelParams);
                        initialized = true;
                        log.info("[NeuTts2e] loaded: {}", modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("[NeuTts2e] load failed: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            // TTS model outputs text tokens, convert to bytes (placeholder)
            String audioText = model.complete(new InferenceParameters(input));
            return audioText.getBytes();
        } catch (Exception e) {
            log.warn("[NeuTts2e] inference failed: {}", e.getMessage());
            return new byte[0];
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
                log.warn("[NeuTts2e] close failed: {}", e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}