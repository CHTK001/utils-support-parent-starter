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
        String minicpm5Url = "https://hf-mirror.com/GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-GGUF/resolve/main/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q8_0.gguf";
        reg("minicpm5", MiniCpm5Translator.class.getName(),
                String.class, String.class, Object.class,
                "../llama/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q8_0.gguf",
                minicpm5Url, java.util.List.of(minicpm5Url), false,
                "MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q8_0.gguf");
        // embeddinggemma-300m: embedding
        String embgemmaUrl = "https://hf-mirror.com/ggml-org/embeddinggemma-300m-qat-q8_0-GGUF/resolve/main/embeddinggemma-300m-qat-Q8_0.gguf";
        reg("embeddinggemma-300m", EmbeddingGemmaTranslator.class.getName(),
                String.class, float[].class, FeatureExtractor.class,
                "../llama/embeddinggemma-300m-qat-Q8_0.gguf",
                embgemmaUrl, java.util.List.of(embgemmaUrl), false,
                "embeddinggemma-300m-qat-Q8_0.gguf");
        // otzaria-embedding: embedding
        // 注：该 repo 为 gated(manual)，需 HF token 或手动下载后放置文件
        String otzariaUrl = "https://hf-mirror.com/EMD123/Otzaria-Embedding-V1-Flash-0.6B-GGUF/resolve/main/Otzaria-Embedding-V1-Flash-0.6B-Q8_0.gguf";
        reg("otzaria-embedding", OtzariaEmbeddingTranslator.class.getName(),
                String.class, float[].class, FeatureExtractor.class,
                "../llama/Otzaria-Embedding-V1-Flash-0.6B-Q8_0.gguf",
                otzariaUrl, java.util.List.of(otzariaUrl), false,
                "Otzaria-Embedding-V1-Flash-0.6B-Q8_0.gguf");
        // neutts-2e: text-to-speech
        // 注：该 repo 为 gated(auto)，需 HF token 或手动下载后放置文件
        String neuttsUrl = "https://hf-mirror.com/neuphonic/neutts-2e-q4-gguf/resolve/main/neutts-2e-Q4_0.gguf";
        reg("neutts-2e", NeuTts2eTranslator.class.getName(),
                String.class, byte[].class, Object.class,
                "../llama/neutts-2e-Q4_0.gguf",
                neuttsUrl, java.util.List.of(neuttsUrl), false,
                "neutts-2e-Q4_0.gguf");
        // gemma-4-e2b: multimodal text generation
        String gemma4Url = "https://hf-mirror.com/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/google_gemma-4-E2B-it-Q4_0.gguf";
        reg("gemma-4-e2b", Gemma4Translator.class.getName(),
                String.class, String.class, Object.class,
                "../llama/gemma-4-E2B_q4_0-it.gguf",
                gemma4Url, java.util.List.of(gemma4Url), false,
                "gemma-4-E2B_q4_0-it.gguf");
        // bitnet-embedding: embedding
        String bitnetUrl = "https://hf-mirror.com/microsoft/bitnet-embedding-0.6b/resolve/main/bitnet-embeddings-0.6b-bf16-i2_s.gguf";
        reg("bitnet-embedding", BitnetEmbeddingTranslator.class.getName(),
                String.class, float[].class, FeatureExtractor.class,
                "../llama/bitnet-embeddings-0.6b-bf16-i2_s.gguf",
                bitnetUrl, java.util.List.of(bitnetUrl), false,
                "bitnet-embeddings-0.6b-bf16-i2_s.gguf");
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