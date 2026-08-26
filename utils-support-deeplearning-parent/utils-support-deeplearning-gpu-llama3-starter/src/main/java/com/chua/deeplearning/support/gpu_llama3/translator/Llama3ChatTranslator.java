package com.chua.deeplearning.support.gpu_llama3.translator;

import ai.djl.Model;
import ai.djl.inference.Publisher;
import ai.djl.modality.nlp.TextGenerator;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.llama.LlamaContext;
import com.chua.deeplearning.support.llama.translator.ChatTranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Llama 3 GPU 对话翻译器。
 * <p>
 * 利用 llama.cpp 的 GPU 加速能力进行 Llama 3 模型的文本生成推理。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Llama3ChatTranslator implements Translator<String, String> {

    /** llama 上下文 */
    private transient LlamaContext context;
    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 默认构造器。
     */
    public Llama3ChatTranslator() {
    }

    @Override
    /** Load model */
    public void loadModel(Model model, String basePath) throws IOException {
        try {
            log.info("加载 Llama3 GPU 模型: {}", basePath);
            // 初始化 llama.cpp GPU 上下文（假设路径指向 GGUF 模型文件）
            context = new LlamaContext(basePath, new LlamaContext.Options()
                    .setGpuDevice(0)  // 使用 GPU 设备 0
                    .setNgpuLayers(35) // 全部层放到 GPU
                    .setThreads(8));
            initialized.set(true);
            log.info("Llama3 GPU 模型加载完成");
        } catch (Exception e) {
            log.error("Llama3 GPU 模型加载失败", e);
            throw new IOException("Failed to load Llama3 GPU model", e);
        }
    }

    @Override
    /** Process input */
    public String processOutput(TranslatorContext ctx, TextGenerator generator) throws TranslateException {
        if (!initialized.get() || context == null) {
            throw new TranslateException("Model not initialized");
        }
        try {
            String prompt = (String) ctx.getInput().get(0);
            // 调用 GPU 加速的推理
            String result = context.generate(prompt, 512, 0.9f, 0.95f, 1.0f);
            return result;
        } catch (Exception e) {
            throw new TranslateException("Generation failed: " + e.getMessage(), e);
        }
    }

    @Override
    /** Close */
    public void close() {
        if (context != null) {
            try {
                context.close();
                log.info("Llama3 GPU 上下文已关闭");
            } catch (Exception e) {
                log.warn("关闭 GPU 上下文异常", e);
            }
        }
    }

    @Override
    /** Get batch size */
    public int getBatchSize() {
        return 1;
    }

    @Override
    /** Is pre processed */
    public boolean isBatchOverride() {
        return true;
    }
}
