package com.chua.common.support.ai.video;

import lombok.Builder;
import lombok.Data;

/**
 * AI 视频生成客户端配置
 *
 * <p>包含连接信息和生成参数的完整配置，与 {@code VideoClient} 配合使用。
 * 支持 builder 模式构建，也支持 SPI 自动注入。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class VideoClientSetting {

    /**
     * AI 服务商名称
     *
     * <p>用于 SPI 查找对应的 {@code VideoClient} 实现，
     * 如 "openai"、"runway" 等。
     */
    private String provider;

    /**
     * API 密钥
     *
     * <p>用于身份认证的 API Key。
     */
    private String appKey;

    /**
     * API 密钥（备用）
     *
     * <p>部分服务商需要额外的 Secret Key 或签名密钥。
     */
    private String secretKey;

    /**
     * API 请求基础地址
     *
     * <p>服务端 API 的完整基础 URL。
     * 若为空则使用实现类提供的默认地址。
     */
    private String baseUrl;

    /**
     * 默认模型名称
     *
     * <p>未通过链式调用指定模型时使用的默认值，
     * 如 "sora"、"gen-2"。
     */
    private String model;

    /**
     * 默认视频宽度
     *
     * <p>未通过链式调用指定尺寸时使用的默认宽度，单位为像素。
     */
    private Integer width;

    /**
     * 默认视频高度
     *
     * <p>未通过链式调用指定尺寸时使用的默认高度，单位为像素。
     */
    private Integer height;

    /**
     * 默认正向提示词
     *
     * <p>未通过链式调用指定时使用的默认提示词。
     */
    private String prompt;

    /**
     * 默认反向提示词
     *
     * <p>指定不希望出现在视频中的内容，未通过链式调用指定时使用此值。
     */
    private String negativePrompt;

    /**
     * 默认视频时长
     *
     * <p>未通过链式调用指定时使用的视频时长，单位为秒。
     */
    private Integer duration;

    /**
     * 默认视频质量
     *
     * <p>未通过链式调用指定时使用的质量等级。
     */
    private String quality;

    /**
     * 默认视频风格
     *
     * <p>未通过链式调用指定时使用的风格描述。
     */
    private String style;

    /**
     * 默认随机种子
     *
     * <p>固定种子可保证多次生成结果可复现。
     * 未通过链式调用指定时使用此值。
     */
    private Long seed;

    /**
     * 参考图字节数据
     *
     * <p>用于图生视频（img2vid）场景的参考图。
     */
    private byte[] referenceImage;

    /**
     * 参考图影响强度
     *
     * <p>控制参考图对生成结果的影响程度，取值范围 0.0 ~ 1.0。
     */
    private Double imageStrength;
}
