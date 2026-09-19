package com.chua.ollama.support;

import io.github.ollama4j.Ollama;

/**
 * Ollama 服务支持工具。
 *
 * <p>封装 {@link Ollama} 实例的创建与主机地址归一化，
 * 供 {@link OllamaChatClient}、{@link OllamaEmbeddingClient}、
 * {@link OllamaFeatureClient}、{@link OllamaImageClient}、
 * {@link OllamaAudioClient} 与各 {@code @Spi("ollama")} 门面共用。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OllamaSupport {

    /**
     * OllamaSupport 实例
     */
    private OllamaSupport() {
    }

    /**
     * 创建指定主机地址的 Ollama 客户端。
     *
     * <p>host 为空时回退到 {@link OllamaConstants#DEFAULT_BASE_URL}，
     * 尾斜杠自动去除。</p>
     *
     * @param host Ollama 服务地址，可为空
     * @return Ollama 客户端实例
     */
    public static Ollama client(String host) {
        String resolved = normalize(host);
        return new Ollama(resolved);
    }

    /**
     * 归一化主机地址：空值回退默认地址，去除尾部斜杠。
     *
     * @param host 原始地址，可为空
     * @return 归一化后的地址
     */
    public static String normalize(String host) {
        if (host == null || host.isBlank()) {
            return OllamaConstants.DEFAULT_BASE_URL;
        }
        String result = host.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        // Ollama 原生端点（/api/...）挂在根路径；若调用方传入了 OpenAI 兼容的 /v1 后缀则去掉
        if (result.endsWith("/v1")) {
            result = result.substring(0, result.length() - 3);
        }
        return result;
    }

    /**
     * 将异常转换为运行时异常并携带原始信息。
     *
     * @param action 操作描述
     * @param e      底层异常
     * @return 包装后的运行时异常
     */
    public static RuntimeException wrap(String action, Exception e) {
        return new RuntimeException("Ollama " + action + " 失败: " + e.getMessage(), e);
    }
}
