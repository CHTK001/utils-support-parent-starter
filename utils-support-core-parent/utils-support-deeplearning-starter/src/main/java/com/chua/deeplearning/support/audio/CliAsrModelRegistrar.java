package com.chua.deeplearning.support.audio;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;

import java.util.List;

/**
 * CLI 运行时 ASR 模型元数据注册器。
 * <p>注册由外部 CLI（nemo-speech）推理、但模型文件由 {@link ModelRegistry} 托管下载的条目：
 * CLI 自带的下载依赖系统 {@code curl}，在无法完成证书吊销校验（如 schannel 环境）时会失败，
 * 故模型权重统一走 Java 侧下载（支持 hf-mirror 镜像回落），再以本地路径传给 CLI。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CliAsrModelRegistrar implements ModelRegistrar {

    /** Parakeet GGUF 在 HuggingFace 上的文件名 */
    private static final String PARAKEET_FILE = "parakeet-tdt-0.6b-v3.q8_0.gguf";

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
     * 注册全部 CLI ASR 模型元数据，已注册则跳过。
     */
    private static void registerAll() {
        // 语音识别(Parakeet-TDT-0.6B-v3)：NVIDIA 多语言 ASR（25 语言，自带标点），GGUF q8_0 约 681MB
        // 无 translator：由 CliAsrAudioClient 经外部 CLI 推理，本条目仅提供模型文件下载与能力清单展示
        String url = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/main/" + PARAKEET_FILE;
        reg("parakeet-v3", url, List.of("https://hf-mirror.com/nvidia/parakeet-tdt-0.6b-v3/resolve/main/" + PARAKEET_FILE));
    }

    /**
     * 注册单条模型元数据，已存在则跳过。
     *
     * @param modelId 模型标识
     * @param url     主下载地址
     * @param mirrors 备用下载地址
     */
    private static void reg(String modelId, String url, List<String> mirrors) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, null, byte[].class, String.class, VirtualClient.class,
                    null, url, mirrors, false, PARAKEET_FILE, null);
        }
    }
}
