package com.chua.deeplearning.support.gpu_llama3;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;

/**
* GPU Llama3 模型集中注册器。
* <p>通过 SPI 被主框架加载；注册 Llama 3 模型元数据供 ModelRegistry 使用。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class GpuLlama3ModelRegistrar implements ModelRegistrar {

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
    * 注册全部内置 GPU Llama3 模型元数据到 {@link ModelRegistry}，已注册则跳过。
     */
    private static void registerAll() {
        // llama-3-8b-it: text generation (GPU)
        String llama3_8b_url = "https://hf-mirror.com/meta-llama/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct.Q4_K_M.gguf";
        reg("llama-3-8b-it", "com.chua.deeplearning.support.gpu_llama3.translator.Llama3ChatTranslator",
                String.class, String.class, Object.class,
                "../llama3/Meta-Llama-3-8B-Instruct.Q4_K_M.gguf",
                llama3_8b_url, java.util.List.of(llama3_8b_url), false,
                "Meta-Llama-3-8B-Instruct.Q4_K_M.gguf");
        // llama-3-70b-it: text generation (GPU, 需要大显存)
        String llama3_70b_url = "https://hf-mirror.com/meta-llama/Meta-Llama-3-70B-Instruct-GGUF/resolve/main/Meta-Llama-3-70B-Instruct.Q4_K_M.gguf";
        reg("llama-3-70b-it", "com.chua.deeplearning.support.gpu_llama3.translator.Llama3ChatTranslator",
                String.class, String.class, Object.class,
                "../llama3/Meta-Llama-3-70B-Instruct.Q4_K_M.gguf",
                llama3_70b_url, java.util.List.of(llama3_70b_url), false,
                "Meta-Llama-3-70B-Instruct.Q4_K_M.gguf");
        // llama-3-2b-it: text generation (轻量 GPU)
        String llama3_2b_url = "https://hf-mirror.com/meta-llama/Meta-Llama-3-2B-Instruct-GGUF/resolve/main/Meta-Llama-3-2B-Instruct.Q4_K_M.gguf";
        reg("llama-3-2b-it", "com.chua.deeplearning.support.gpu_llama3.translator.Llama3ChatTranslator",
                String.class, String.class, Object.class,
                "../llama3/Meta-Llama-3-2B-Instruct.Q4_K_M.gguf",
                llama3_2b_url, java.util.List.of(llama3_2b_url), false,
                "Meta-Llama-3-2B-Instruct.Q4_K_M.gguf");
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
