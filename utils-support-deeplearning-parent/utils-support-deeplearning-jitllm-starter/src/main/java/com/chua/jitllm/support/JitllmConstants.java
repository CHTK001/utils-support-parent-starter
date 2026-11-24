package com.chua.jitllm.support;

/**
 * jitLLM 集成常量。
 *
 * <p>集中管理 SPI provider 名称、默认采样参数与模型目录等配置项，
 * 供 {@link JitllmChatClient} 与 {@link JitllmSupport} 共用。
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class JitllmConstants {

    /**
     * SPI provider 名称
     */
    public static final String PROVIDER = "jitllm";

    /**
     * 默认模型目录（{@code model} 为相对路径且未配置 {@code baseUrl} 时使用）
     */
    public static final String DEFAULT_MODEL_DIR = "./models";

    /**
     * 默认最大生成 Token 数
     */
    public static final int DEFAULT_MAX_NEW_TOKENS = 512;

    /**
     * JitllmConstants 实例
     */
    private JitllmConstants() {
    }
}
