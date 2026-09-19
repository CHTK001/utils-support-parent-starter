package com.chua.common.support.ai.feature;

import lombok.Builder;
import lombok.Data;

/**
 * AI 特征提取客户端配置。
 * <p>
 * 封装与特征提取模型通信所需的全部配置参数，包括认证信息、模型参数等。
 * 通过 Builder 模式构建，支持部分字段可选。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class FeatureClientSetting {

    /**
     * AI 服务商名称。
     * <p>
     * 用于 SPI 查找对应的 {@link FeatureClient} 实现，
     * 如 "onnx"、"pytorch"、"openai" 等。
     * </p>
     */
    private String provider;

    /**
     * API 请求基础地址。
     * <p>
     * 服务端 API 的完整基础 URL，例如 "https://api.openai.com/v1"。
     * 若为空则使用实现类提供的默认地址。
     * </p>
     */
    private String baseUrl;

    /**
     * API 密钥，用于身份认证的 API Key。
     */
    private String appKey;

    /**
     * API 密钥（备用），部分服务商需要额外密钥或签名密钥。
     */
    private String appSecret;

    /**
     * 默认模型名称。
     * <p>
     * 未通过链式调用指定模型时使用的默认值，
     * 如 "bge-small-zh"、"clip-image-feature" 等。
     * </p>
     */
    private String model;

    /**
     * 输出向量维度。
     * <p>
     * 部分模型支持通过 dimensions 参数指定输出向量的维度数。
     * 为 null 时使用模型默认维度。
     * </p>
     */
    private Integer dimensions;

    /**
     * HTTP 代理地址。
     * <p>
     * 格式示例：
     * </p>
     * <ul>
     *   <li>HTTP 代理：http://127.0.0.1:7890</li>
     *   <li>SOCKS5 代理：socks5://127.0.0.1:1080</li>
     * </ul>
     */
    private String proxy;

    /**
     * 输入最大序列长度（token 数），用于本地特征模型。
     * <p>
     * 部分本地模型需要截断输入到指定最大序列长度；为 null 时由实现类决定。
     * </p>
     */
    private Integer maxLen;
}
