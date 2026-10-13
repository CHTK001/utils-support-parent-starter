package com.chua.common.support.ai.translate;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * AI 翻译客户端配置
 *
 * <p>封装与 AI 翻译服务通信所需的全部配置参数，包括认证信息、
 * 翻译语言对、模型参数等。通过 Builder 模式构建，支持部分字段可选。
 *
 * <p>使用示例：
 * <pre>{@code
 * TranslateClientSetting setting = TranslateClientSetting.builder()
 *     .provider("bilibili")
 *     .appKey("sk-xxx")
 *     .targetLanguage("英语")
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 2026/10/12
 */
@Data
@Builder
public class TranslateClientSetting {

    /**
     * AI 服务商名称
     *
     * <p>用于 SPI 查找对应的 {@link TranslateClient} 实现，
     * 如 "bilibili"、"openai" 等。
     */
    private String provider;

    /**
     * API 请求基础地址
     *
     * <p>服务端 API 的完整基础 URL，例如 "https://index-translate.bilibili.com/v1"。
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
     * 默认翻译模型名称
     *
     * <p>未通过链式调用指定模型时使用的默认值，
     * 如 "Index-Translate-35B-A3B"。
     */
    private String model;

    /**
     * 默认源语言
     *
     * <p>待翻译文本的语言。未通过链式调用指定时使用此值，
     * 为空表示由模型自动检测。
     */
    private String sourceLanguage;

    /**
     * 默认目标语言
     *
     * <p>翻译结果的目标语言，如 "英语"、"日语"。
     * 未通过链式调用指定时使用此值。
     */
    private String targetLanguage;

    /**
     * 默认翻译指令
     *
     * <p>覆盖默认的翻译提示词模板。支持 {@code {text}} 占位符，
     * 会被替换为待翻译文本；不包含占位符时在指令后自动换行拼接文本。
     */
    private String prompt;

    /**
     * 默认温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     * 翻译场景建议较低值（如 0.3）以保证译文的确定性。
     */
    private Double temperature;

    /**
     * 默认最大输出 Token 数
     *
     * <p>限制每次请求生成的最大 Token 数量。
     */
    private Integer maxTokens;

    /**
     * 默认 Top-P 采样参数
     *
     * <p>核采样参数，控制生成文本的多样性。
     */
    private Double topP;

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

    /**
     * 请求超时时间（毫秒）
     *
     * <p>控制单次 HTTP 请求的连接与读取超时。
     * null 或 &lt;=0 表示使用实现类的默认超时。
     */
    private Long timeoutMillis;

    /**
     * 自定义 HTTP 请求头
     *
     * <p>每次请求都会携带这些额外的 HTTP 头，用于服务商要求的
     * 自定义认证头、路由头等场景。
     */
    private Map<String, String> extraHeaders;

    /**
     * 认证方案
     *
     * <p>取值：{@code api_key} / {@code bearer} / {@code oauth} / {@code none}。
     * {@code none} 表示不发送任何认证头。为空时按 {@code api_key} 处理。
     *
     * @see com.chua.common.support.ai.chat.AuthHeaders
     */
    private String authScheme;

    /**
     * 认证头名称覆盖
     *
     * <p>为空时使用标准 {@code Authorization}。
     */
    private String authHeader;

    /**
     * 认证值前缀覆盖
     *
     * <p>为空时使用标准 {@code "Bearer "}。需要「无前缀」时显式传空串。
     */
    private String authPrefix;
}
