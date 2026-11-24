package com.chua.jitllm.support.llama3;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;

/**
 * llama-server 独立进程（sidecar）模型集中注册器。
 * <p>通过 SPI 被主框架加载；注册经外部 {@code llama-server}（llama.cpp ≥ v0.8.0/b11960）
 * 提供服务的模型元数据供 {@link ModelRegistry} 使用。此类模型不经 de.kherud 进程内加载，
 * 由 {@code LlamaServerChatTranslator} 转发 OpenAI 兼容请求。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class LlamaServerModelRegistrar implements ModelRegistrar {

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
     * 注册全部 sidecar 模型元数据到 {@link ModelRegistry}，已注册则跳过。
     */
    private static void registerAll() {
        // lfm3-1.7b: LiquidAI LFM3-1.7B（tri-hybrid 架构，llama.cpp v0.8.0 起支持）。
        // 进程内 de.kherud 绑定为 b10126，无法加载，故走 llama-server sidecar + OpenAI 兼容接口。
        // 推荐采样参数（GGUF 元数据）：temperature 0.1 / top_k 50 / repetition_penalty 1.1
        String lfm3MirrorUrl = "https://hf-mirror.com/LiquidAI/LFM3-1.7B-GGUF/resolve/main/LFM3-1.7B-Q4_K_M.gguf";
        String lfm3SourceUrl = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/main/LFM3-1.7B-Q4_K_M.gguf";
        reg("lfm3-1.7b", "com.chua.jitllm.support.llama3.translator.LlamaServerChatTranslator",
                String.class, String.class, Object.class,
                "../llama3/LFM3-1.7B-Q4_K_M.gguf",
                lfm3MirrorUrl, java.util.List.of(lfm3MirrorUrl, lfm3SourceUrl), false,
                "LFM3-1.7B-Q4_K_M.gguf");
    }

    /**
     * 注册单条模型元数据，已存在则跳过。
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
