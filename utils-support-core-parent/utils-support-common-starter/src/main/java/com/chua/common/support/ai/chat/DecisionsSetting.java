package com.chua.common.support.ai.chat;

import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 决策客户端配置
 *
 * <p>封装与 AI 大模型通信决策端点所需的全部配置参数，包括认证信息、模型参数、
 * 网络代理等。通过 Builder 模式构建，支持部分字段可选。
 * 每个字段的角色均可由 {@link ChatClientSetting} 继承或类比。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class DecisionsSetting {

    /**
     * AI 服务商名称
     *
     * <p>用于 SPI 查找对应的 {@link DecisionsClient} 实现，
     * 如 "openai"、"systemone"、"typesafe" 等。
     */
    private String provider;

    /**
     * API 请求基础地址
     *
     * <p>服务端 API 的完整基础 URL，例如 "https://api.openai.com/v1"、
     * "https://api.typesafe.ai/v1"。若为空则使用实现类提供的默认地址。
     */
    private String baseUrl;

    /**
     * API 密钥
     *
     * <p>用于身份认证的 API Key。
     */
    private String appKey;

    /**
     * API 密钥（备用）
     *
     * <p>部分服务商需要额外密钥或签名密钥。
     */
    private String appSecret;

    /**
     * 决策协议
     *
     * <p>决定请求发往哪个端点：
     * <ul>
     *   <li>{@link DecisionsProtocol#OPENAI} — {@code POST /v1/decisions}</li>
     *   <li>{@link DecisionsProtocol#SYSTEMONE} — {@code POST /v1/systemone}</li>
     * </ul>
     * 默认 {@link DecisionsProtocol#OPENAI}。
     */
    private DecisionsProtocol protocol;

    /**
     * 默认模型名称
     *
     * <p>OpenAI 协议默认 {@code gpt-6-luna}，SystemOne 协议默认 {@code jev-latest}。
     * 未通过链式调用指定模型时使用的默认值。
     */
    private String model;

    /**
     * 默认温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     */
    private Double temperature;

    /**
     * 默认最大输出 Token 数
     */
    private Integer maxTokens;

    /**
     * 默认 Top-P 采样参数
     */
    private Double topP;

    /**
     * 默认系统提示词
     */
    private String system;

    /**
     * HTTP 代理地址
     */
    private String proxy;

    /**
     * 请求超时时间（毫秒）
     */
    private Long timeoutMillis;

    /**
     * 自定义 HTTP 请求头
     */
    private Map<String, String> extraHeaders;

    /**
     * 认证方案
     */
    private String authScheme;

    /**
     * 认证头名称覆盖
     */
    private String authHeader;

    /**
     * 认证值前缀覆盖
     */
    private String authPrefix;

    /**
     * 额外请求体参数
     *
     * <p>透传给协议要求额外的字段（如 OpenAI 协议的 prompt_format_version 等）。
     */
    private Map<String, Object> extraBody;
}
