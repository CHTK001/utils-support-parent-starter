package com.chua.ollama.support;

import java.util.List;

/**
 * Ollama 常量与默认模型清单。
 *
 * <p>提供 Ollama 本地推理服务的默认地址、API 路径与已知模型定义，
 * 供 {@link OllamaChatClient} 使用。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OllamaConstants {

    /**
     * Ollama 常量 实例
     */
    private OllamaConstants() {
    }

    /**
     * Ollama 默认服务地址（本机 11434 端口）
     */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /**
     * OpenAI 兼容 聊天 补全 路径
     */
    public static final String PATH_CHAT_COMPLETIONS = "/v1/chat/completions";

    /**
     * OpenAI 兼容 模型列表 路径
     */
    public static final String PATH_MODELS = "/v1/models";

    /**
     * Ollama 原生 模型标签 路径（返回 已 安装 模型列表）
     */
    public static final String PATH_TAGS = "/api/tags";

    /**
     * 连接 超时 毫秒数
     */
    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /**
     * 读取 超时 毫秒数（本地 推理 可能 较慢，放宽 到 300 秒）
     */
    public static final int READ_TIMEOUT_MILLIS = 300_000;

    /**
     * Ollama 常见 本地 模型 清单（节选）。
     * <p>实际 可用 模型 以 服务 端 {@code /api/tags} 返回 为准，此 清单 仅 用于
     * 离线 兜底 与 文档 参考。</p>
     */
    public static final List<String> COMMON_MODELS = List.of(
            "llama3.2:3b",
            "llama3.2:1b",
            "qwen2.5:7b",
            "qwen2.5:1.5b",
            "gemma3:4b",
            "deepseek-r1:7b",
            "mistral:7b",
            "phi3:mini",
            "minicpm5-2b",
            "minicpm5-1b"
    );
}
