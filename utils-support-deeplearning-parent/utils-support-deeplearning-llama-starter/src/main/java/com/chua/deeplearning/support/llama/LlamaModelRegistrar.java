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
        // minicpm5: text generation
        reg("minicpm5", MiniCpm5Translator.class.getName(),
                String.class, String.class, Object.class,
                "../llama/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q8_0.gguf");
        // embeddinggemma-300m: embedding
        reg("embeddinggemma-300m", EmbeddingGemmaTranslator.class.getName(),
                String.class, float[].class, FeatureExtractor.class,
                "../llama/embeddinggemma-300m-qat-Q8_0.gguf");
        // otzaria-embedding: embedding
        reg("otzaria-embedding", OtzariaEmbeddingTranslator.class.getName(),
                String.class, float[].class, FeatureExtractor.class,
                "../llama/Otzaria-Embedding-V1-Flash-0.6B-Q8_0.gguf");
        // neutts-2e: text-to-speech
        reg("neutts-2e", NeuTts2eTranslator.class.getName(),
                String.class, byte[].class, Object.class,
                "../llama/neutts-2e-Q4_0.gguf");
        // gemma-4-e2b: multimodal text generation
        reg("gemma-4-e2b", Gemma4Translator.class.getName(),
                String.class, String.class, Object.class,
                "../llama/gemma-4-E2B_q4_0-it.gguf");
        // bitnet-embedding: embedding
        reg("bitnet-embedding", BitnetEmbeddingTranslator.class.getName(),
                String.class, float[].class, FeatureExtractor.class,
                "../llama/bitnet-embeddings-0.6b-bf16-i2_s.gguf");
        // qwen2-1.5b: text generation
        reg("qwen2-1.5b", Qwen2ChatTranslator.class.getName(),
                String.class, String.class, Object.class,
                "models/llama/qwen2.5-1.5b-instruct-q4_k_m.gguf");
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
}