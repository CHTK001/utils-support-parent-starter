package com.chua.deeplearning.support.llama;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.llama.translator.BitnetEmbeddingTranslator;
import com.chua.deeplearning.support.llama.translator.EmbeddingGemmaTranslator;
import com.chua.deeplearning.support.llama.translator.Gemma4Translator;
import com.chua.deeplearning.support.llama.translator.MiniCpm5Translator;
import com.chua.deeplearning.support.llama.translator.NeuTts2eTranslator;
import com.chua.deeplearning.support.llama.translator.OtzariaEmbeddingTranslator;
import com.chua.deeplearning.support.llama.translator.Qwen2ChatTranslator;

/**
 * Llama 模型集中注册器。
 * <p>通过 SPI 被主框架加载；注册模型元数据（路径/输入输出类型）供 ModelRegistry 路径解析使用。</p>
 *
 * @author CH
 * @since 4.0.0
 */
public class LlamaModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(ModelRegistry registry) {
        registerAll();
    }

    /**
     * 注册全部内置 Llama 模型元数据到 {@link ModelRegistry}，已注册则跳过。
     */
    private static void registerAll() {
        // qwen2-1.5b: text generation
        String qwenUrl = "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf";
        reg("qwen2-1.5b", Qwen2ChatTranslator.class.getName(),
                String.class, String.class, Object.class,
                "../llama/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                qwenUrl, java.util.List.of(qwenUrl), false, "qwen2.5-1.5b-instruct-q4_k_m.gguf");
        // qwen2-0.5b: text generation
        String qwen05Url = "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf";
        reg("qwen2-0.5b", Qwen2ChatTranslator.class.getName(),
                String.class, String.class, Object.class,
                "../llama/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                qwen05Url, java.util.List.of(qwen05Url), false, "qwen2.5-0.5b-instruct-q4_k_m.gguf");
    }

    /**
     * 注册单条模型元数据，已存在则跳过。
     *
     * @param modelId            模型 id
     * @param translatorClassName 翻译器类名
     * @param inputType          输入类型
     * @param outputType         输出类型
     * @param capability         能力类型（ITranslator / FeatureExtractor / Object）
     * @param relativePath       GGUF 模型相对路径
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, relativePath);
        }
    }

    /**
     * 注册单条模型元数据（含下载 URL），已存在则跳过。
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath,
                            String downloadUrl, java.util.List<String> mirrors,
                            boolean compress, String downloadFileName) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability,
                    relativePath, downloadUrl, mirrors, compress, downloadFileName);
        }
    }
}