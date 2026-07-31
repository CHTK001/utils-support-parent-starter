package com.chua.common.support.ai.chat;

import lombok.Builder;
import lombok.Data;

/**
 * AI 对话客户端配置
 *
 * <p>封装与 AI 大模型通信所需的全部配置参数，包括认证信息、模型参数、
 * 网络代理等。通过 Builder 模式构建，支持部分字段可选。
 *
 * @author CH
 * @since 2026/07/15
 */
@Data
@Builder
public class ChatClientSetting {

    /**
     * AI 服务商名称
     *
     * <p>用于 SPI 查找对应的 {@link ChatClient} 实现，
     * 如 "openai"、"deepseek"、"zhipu" 等。
     */
    private String provider;

    /**
     * API 请求基础地址
     *
     * <p>服务端 API 的完整基础 URL，例如 "https://api.openai.com/v1"。
     * 若为空则使用实现类提供的默认地址。
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
     * 默认模型名称
     *
     * <p>未通过链式调用指定模型时使用的默认值，
     * 如 "gpt-3.5-turbo"、"deepseek-chat"。
     */
    private String model;

    /**
     * 默认温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     * 未通过链式调用指定时使用此值。
     */
    private Double temperature;

    /**
     * 默认最大输出 Token 数
     *
     * <p>限制每次请求生成的最大 Token 数量。
     * 未通过链式调用指定时使用此值。
     */
    private Integer maxTokens;

    /**
     * 默认 Top-P 采样参数
     *
     * <p>核采样参数，控制生成文本的多样性。
     * 值越小生成内容越确定。
     */
    private Double topP;

    /**
     * 默认系统提示词
     *
     * <p>未通过链式调用指定时使用的系统提示词。
     */
    private String system;

    /**
     * HTTP 代理地址
     *
     * <p>格式示例：
     * <ul>
     *   <li>HTTP 代理：http://127.0.0.1:7890</li>
     *   <li>SOCKS5 代理：socks5://127.0.0.1:1080</li>
     * </ul>
     */
    private String proxy;
}
