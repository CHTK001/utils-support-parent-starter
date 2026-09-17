package com.chua.common.support.ai.audio;

import lombok.Builder;
import lombok.Data;

/**
* AI 文字转语音（TTS）客户端配置。
*
* <p>包含连接信息和合成参数的完整配置，与 {@link TextToAudioClient} 配合使用。
* 支持 builder 模式构建，也支持 SPI 自动注入。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class TextToAudioClientSetting {

    /**
    * AI 服务商名称
    *
    * <p>用于 SPI 查找对应的 {@link TextToAudioClient} 实现，
    * 如 "openai-tts"、"edge-tts"、"piper"、"coqui" 等。
    */
    private String provider;

    /**
    * API 密钥
    */
    private String appKey;

    /**
    * API 密钥（备用）
    */
    private String secretKey;

    /**
    * API 请求基础地址
    *
    * <p>若为空则使用实现类提供的默认地址。
    */
    private String baseUrl;

    /**
    * 默认模型名称
    *
    * <p>如 "tts-1"、"tts-1-hd"、"piper-zh_CN-huayan-medium"。
    */
    private String model;

    /**
    * 默认发音人
    */
    private String voice;

    /**
    * 默认语言
    *
    * <p>ISO 639-1 语言代码，如 "zh"、"en"；
    * 留空则由模型自动检测。
    */
    private String language;

    /**
    * 默认输出音频格式
    *
    * <p>如 "wav"、"mp3"、"opus"、"pcm"、"flac"。
    */
    private String format;

    /**
    * 默认语速倍率
    *
    * <p>1.0 表示原速。
    */
    private Double speed;

    /**
    * 默认采样率（Hz）
    */
    private Integer sampleRate;

    /**
    * 默认采样温度
    */
    private Double temperature;

    /**
    * 默认随机种子
    */
    private Long seed;

    /**
    * 默认要合成的文本
    */
    private String text;

    /**
    * 代理地址
    */
    private String proxy;
}
