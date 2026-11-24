package com.chua.jitllm.support.gguf;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.HardwareConfig;

import java.util.List;

/**
 * GGUF 通用模型集中注册器。
 *
 * <p>通过 SPI 被主框架加载；收录常用 / 最新 / 多类型的 GGUF 对话模型（Qwen3、
 * DeepSeek-R1 蒸馏、Phi-4、Gemma-3、GLM-4、Llama 3.x、Mistral 等），并逐条附带
 * {@link HardwareConfig}（设备 auto + 最低内存/显存需求 + 推荐标记），供
 * {@code ModelHardwareFilter} 按本机硬件自动过滤跑不动的模型，避免选中后 OOM/崩溃。</p>
 *
 * <p>Translator 选择口径：Llama / Mistral 等经典架构走进程内
 * {@code Llama3ChatTranslator}；Qwen3、DeepSeek-R1-Distill、Phi-4、Gemma-3、GLM-4
 * 等新架构由外部 llama-server（sidecar，llama.cpp ≥ v0.8.0/b11960）经
 * {@code LlamaServerChatTranslator} 提供 OpenAI 兼容服务（de.kherud b10126
 * 进程内无法加载新架构模板）。</p>
 *
 * <p>下载地址以 hf-mirror.com 为主地址（国内可达），huggingface.co 原址为备用镜像。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class GgufModelRegistrar implements ModelRegistrar {

    /**
     * 进程内加载的经典 Llama / Mistral 架构 Translator
     */
    private static final String T_LLAMA3 = "com.chua.jitllm.support.llama3.translator.Llama3ChatTranslator";

    /**
     * 外部 llama-server sidecar（新架构）Translator
     */
    private static final String T_LLAMA_SERVER = "com.chua.jitllm.support.llama3.translator.LlamaServerChatTranslator";

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
     * 注册全部内置 GGUF 模型元数据到 {@link ModelRegistry}，已注册则跳过。
     *
     * <p>minVramMb 为粗估的最低运行内存/显存需求（量化文件体积 + 运行开销余量）；
     * 小模型（1B~4B 级）标 recommended=true，其余 false；device 一律 auto，
     * 容量不足由 {@code ModelHardwareFilter} 按本机硬件剔除。</p>
     */
    private static void registerAll() {
        // ===== Llama 3.x（经典 Llama 架构，进程内加载） =====
        // llama-3.2-1b-it: 端侧轻量 1B，Q4_K_M 约 0.8GB
        reg("llama-3.2-1b-it", T_LLAMA3, "unsloth/Llama-3.2-1B-Instruct-GGUF",
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 1536, true,
                "Llama 3.2 1B Instruct（Q4_K_M，约 0.8GB，端侧轻量）");
        // llama-3.2-3b-it: 轻量 3B，Q4_K_M 约 2.0GB
        reg("llama-3.2-3b-it", T_LLAMA3, "unsloth/Llama-3.2-3B-Instruct-GGUF",
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 3072, true,
                "Llama 3.2 3B Instruct（Q4_K_M，约 2.0GB）");
        // llama-3.3-70b-it: 旗舰 70B，Q4_K_M 约 42.5GB，仅大显存/大内存机器可跑
        reg("llama-3.3-70b-it", T_LLAMA3, "unsloth/Llama-3.3-70B-Instruct-GGUF",
                "Llama-3.3-70B-Instruct-Q4_K_M.gguf", 46080, false,
                "Llama 3.3 70B Instruct（Q4_K_M，约 42.5GB，需大显存/大内存）");

        // ===== Mistral（Llama 系架构，进程内加载） =====
        reg("mistral-7b-instruct", T_LLAMA3, "bartowski/Mistral-7B-Instruct-v0.3-GGUF",
                "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf", 6144, false,
                "Mistral 7B Instruct v0.3（Q4_K_M，约 4.4GB）");

        // ===== Qwen3（新架构，llama-server sidecar） =====
        // qwen3-1.7b: 该仓库仅提供 Q8_0 量化，约 1.9GB
        reg("qwen3-1.7b", T_LLAMA_SERVER, "Qwen/Qwen3-1.7B-GGUF",
                "Qwen3-1.7B-Q8_0.gguf", 2560, true,
                "Qwen3 1.7B（Q8_0，约 1.9GB，思考/对话双模式）");
        reg("qwen3-4b", T_LLAMA_SERVER, "Qwen/Qwen3-4B-GGUF",
                "Qwen3-4B-Q4_K_M.gguf", 4096, true,
                "Qwen3 4B（Q4_K_M，约 2.5GB）");
        reg("qwen3-8b", T_LLAMA_SERVER, "Qwen/Qwen3-8B-GGUF",
                "Qwen3-8B-Q4_K_M.gguf", 6144, false,
                "Qwen3 8B（Q4_K_M，约 5.0GB）");
        reg("qwen3-14b", T_LLAMA_SERVER, "Qwen/Qwen3-14B-GGUF",
                "Qwen3-14B-Q4_K_M.gguf", 10240, false,
                "Qwen3 14B（Q4_K_M，约 9.0GB）");
        // qwen3-30b-a3b: MoE（激活 3B），Q4_K_M 约 18.6GB
        reg("qwen3-30b-a3b", T_LLAMA_SERVER, "Qwen/Qwen3-30B-A3B-GGUF",
                "Qwen3-30B-A3B-Q4_K_M.gguf", 21504, false,
                "Qwen3 30B-A3B（MoE，激活 3B；Q4_K_M，约 18.6GB）");

        // ===== DeepSeek-R1 蒸馏（Qwen 底座，新架构，sidecar） =====
        reg("dsr1-qwen-7b", T_LLAMA_SERVER, "unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF",
                "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf", 6144, false,
                "DeepSeek-R1 蒸馏 Qwen 7B（Q4_K_M，约 4.7GB，推理链输出）");
        reg("dsr1-qwen-32b", T_LLAMA_SERVER, "unsloth/DeepSeek-R1-Distill-Qwen-32B-GGUF",
                "DeepSeek-R1-Distill-Qwen-32B-Q4_K_M.gguf", 22528, false,
                "DeepSeek-R1 蒸馏 Qwen 32B（Q4_K_M，约 19.9GB，推理链输出）");

        // ===== Phi-4（微软 14B，新架构，sidecar；注意文件名 p 小写） =====
        reg("phi-4", T_LLAMA_SERVER, "lmstudio-community/Phi-4-GGUF",
                "phi-4-Q4_K_M.gguf", 10240, false,
                "Phi-4 14B（Q4_K_M，约 9.0GB）");

        // ===== Gemma 3（Google，新架构，sidecar） =====
        reg("gemma-3-4b", T_LLAMA_SERVER, "lmstudio-community/gemma-3-4b-it-GGUF",
                "gemma-3-4b-it-Q4_K_M.gguf", 4096, true,
                "Gemma 3 4B IT（Q4_K_M，约 2.5GB，多模态底座）");
        reg("gemma-3-12b", T_LLAMA_SERVER, "lmstudio-community/gemma-3-12b-it-GGUF",
                "gemma-3-12b-it-Q4_K_M.gguf", 8192, false,
                "Gemma 3 12B IT（Q4_K_M，约 7.3GB）");

        // ===== GLM-4（智谱 9B，新架构，sidecar；该仓库为点号命名 Q4_K） =====
        reg("glm-4-9b", T_LLAMA_SERVER, "legraphista/glm-4-9b-chat-GGUF",
                "glm-4-9b-chat.Q4_K.gguf", 7168, false,
                "GLM-4 9B Chat（Q4_K，约 5.5GB）");
    }

    /**
     * 注册单条 GGUF 模型元数据，已存在则跳过。
     *
     * <p>relativePath 固定落在 {@code ../llama3/}（与既有 GGUF 权重同目录）；
     * capability 声明为 {@link ChatClient}（目录归入 chat 能力组）；下载主地址取
     * hf-mirror.com，huggingface.co 原址作备用镜像；GGUF 为单文件、非压缩包。</p>
     *
     * @param modelId     模型标识
     * @param translator  Translator 全限定类名
     * @param repo        Hugging Face 仓库名（owner/repo）
     * @param fileName    仓内 GGUF 文件名（同时用作 downloadFileName）
     * @param minVramMb   最低运行内存/显存需求（MB，粗估）
     * @param recommended 是否推荐模型
     * @param description 展示描述
     */
    private static void reg(String modelId, String translator, String repo, String fileName,
                            long minVramMb, boolean recommended, String description) {
        if (ModelRegistry.get(modelId) != null) {
            return;
        }
        String mirrorUrl = "https://hf-mirror.com/" + repo + "/resolve/main/" + fileName;
        String sourceUrl = "https://huggingface.co/" + repo + "/resolve/main/" + fileName;
        HardwareConfig hardwareConfig = HardwareConfig.builder()
                .device("auto")
                .minVramMb(minVramMb)
                .recommended(recommended)
                .description(description)
                .build();
        ModelRegistry.register(modelId, translator,
                String.class, String.class, ChatClient.class,
                "../llama3/" + fileName, mirrorUrl, List.of(mirrorUrl, sourceUrl), null,
                false, fileName, hardwareConfig);
    }
}
