package com.chua.jitllm.support.gguf;

import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.HardwareConfig;
import com.chua.jitllm.support.embedding.JitllmEmbeddingTranslator;

import java.util.List;

/**
 * GGUF 文本嵌入模型集中注册器。
 *
 * <p>通过 SPI 被主框架加载；收录常用 / 中文友好的 GGUF 嵌入模型（bge-m3、
 * bge-small-zh-v1.5、nomic-embed-text-v1.5），能力声明为 {@link EmbeddingClient}
 * （目录归入 text-embedding 能力组），供 {@code JitllmEmbeddingClient} 与
 * RAG 检索链路按 {@code jitllm} 提供者枚举。</p>
 *
 * <p>下载地址以 hf-mirror.com 为主地址（国内可达），huggingface.co 原址为备用镜像；
 * 全部下载地址均经 HEAD 校验可达（2026-11-24）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class GgufEmbeddingModelRegistrar implements ModelRegistrar {

    /**
     * 进程内 llama.cpp 嵌入 Translator
     */
    private static final String T_EMBEDDING = JitllmEmbeddingTranslator.class.getName();

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
     * 注册全部内置 GGUF 嵌入模型元数据到 {@link ModelRegistry}，已注册则跳过。
     *
     * <p>minVramMb 为粗估的最低运行内存/显存需求（量化文件体积 + 运行开销余量）；
     * 嵌入模型体量小（0.1GB~0.7GB），全部标 recommended=true；device 一律 auto，
     * 容量不足由 {@code ModelHardwareFilter} 按本机硬件剔除。</p>
     */
    private static void registerAll() {
        // bge-m3: 多语（100+ 语言）通用嵌入，8192 上下文，1024 维，Q4_K_M 约 0.6GB
        reg("bge-m3", "gpustack/bge-m3-GGUF",
                "bge-m3-Q4_K_M.gguf", 1024, true,
                "BGE-M3 多语嵌入（Q4_K_M，约 0.6GB，1024 维，8192 上下文）");
        // bge-small-zh-v1.5: 中文轻量嵌入，512 维，Q4_K_M 约 0.1GB
        reg("bge-small-zh-v1.5", "CompendiumLabs/bge-small-zh-v1.5-gguf",
                "bge-small-zh-v1.5-q4_k_m.gguf", 512, true,
                "BGE small 中文嵌入 v1.5（Q4_K_M，约 0.1GB，512 维，端侧轻量）");
        // nomic-embed-text-v1.5: 英文为主嵌入，768 维，8192 上下文，Q4_K_M 约 0.1GB（仓内文件为点号命名）
        reg("nomic-embed-text-v1.5", "nomic-ai/nomic-embed-text-v1.5-GGUF",
                "nomic-embed-text-v1.5.Q4_K_M.gguf", 768, true,
                "Nomic Embed Text v1.5（Q4_K_M，约 0.1GB，768 维，8192 上下文）");
    }

    /**
     * 注册单条 GGUF 嵌入模型元数据，已存在则跳过。
     *
     * <p>relativePath 固定落在 {@code ../llama3/}（与既有 GGUF 权重同目录）；
     * capability 声明为 {@link EmbeddingClient}（目录归入 text-embedding 能力组）；
     * 下载主地址取 hf-mirror.com，huggingface.co 原址作备用镜像；GGUF 为单文件、非压缩包。</p>
     *
     * @param modelId     模型标识
     * @param repo        Hugging Face 仓库名（owner/repo）
     * @param fileName    仓内 GGUF 文件名（同时用作 downloadFileName）
     * @param dimensions  输出向量维度
     * @param recommended 是否推荐模型
     * @param description 展示描述
     */
    private static void reg(String modelId, String repo, String fileName,
                            int dimensions, boolean recommended, String description) {
        if (ModelRegistry.get(modelId) != null) {
            return;
        }
        String mirrorUrl = "https://hf-mirror.com/" + repo + "/resolve/main/" + fileName;
        String sourceUrl = "https://huggingface.co/" + repo + "/resolve/main/" + fileName;
        HardwareConfig hardwareConfig = HardwareConfig.builder()
                .device("auto")
                .minVramMb(dimensions >= 1024 ? 1024 : 512)
                .recommended(recommended)
                .description(description)
                .build();
        ModelRegistry.register(modelId, T_EMBEDDING,
                String.class, float[].class, EmbeddingClient.class,
                "../llama3/" + fileName, mirrorUrl, List.of(mirrorUrl, sourceUrl), null,
                false, fileName, hardwareConfig);
    }
}
