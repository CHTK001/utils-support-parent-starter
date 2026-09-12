package com.chua.common.support.ai.image;

import lombok.Builder;
import lombok.Data;

/**
* AI 图片生成客户端配置
*
* <p>包含连接信息和生成参数的完整配置，与 {@link ImageClient} 配合使用。
* 支持 builder 模式构建，也支持 SPI 自动注入。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class ImageClientSetting {

    /**
    * AI 服务商名称
    *
    * <p>用于 SPI 查找对应的 {@link ImageClient} 实现，
    * 如 "openai"、"midjourney"、"stable-diffusion" 等。
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
    * 如 "dall-e-3"、"sd-xl"、"mj-6"。
     */
    private String model;

    /**
    * 默认图片宽度
    *
    * <p>未通过链式调用指定尺寸时使用的默认宽度，单位为像素。
     */
    private Integer width;

    /**
    * 默认图片高度
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
    * <p>指定不希望出现在图片中的内容，未通过链式调用指定时使用此值。
     */
    private String negativePrompt;

    /**
    * 默认图片质量
    *
    * <p>未通过链式调用指定时使用的质量等级，如 "standard"、"hd"。
     */
    private String quality;

    /**
    * 默认图片风格
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
    * 默认推理步数
    *
    * <p>步数越高图片细节越丰富但耗时更长。
    * 未通过链式调用指定时使用此值。
     */
    private Integer steps;

    /**
    * 参考图字节数据
    *
    * <p>用于图生图（img2img）场景的参考图。
     */
    private byte[] referenceImage;

    /**
    * 参考图影响强度
    *
    * <p>控制参考图对生成结果的影响程度，取值范围 0.0 ~ 1.0。
     */
    private Double imageStrength;

    /**
    * ControlNet 类型
    *
    * <p>指定 ControlNet 预处理类型，如 canny、depth、pose 等。
     */
    private String controlType;
}
