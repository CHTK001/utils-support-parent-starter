package com.chua.common.support.ai.audio;

import lombok.Builder;
import lombok.Data;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * AI 语音识别（ASR）客户端配置
 *
 * <p>包含连接信息和识别参数的完整配置，与 {@link VirtualClient} 配合使用。
 * 支持 builder 模式构建，也支持 SPI 自动注入。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class AudioClientSetting {

    /**
     * AI 服务商名称
     *
     * <p>用于 SPI 查找对应的 {@link VirtualClient} 实现，
     * 如 "whisper"、"openai"、"alibaba-asr"、"local" 等。
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
     * <p>如 "whisper-1"、"whisper-tiny"、"paraformer-v2"。
     */
    private String model;

    /**
     * 默认音频语言
     *
     * <p>ISO 639-1 语言代码，如 "zh"、"en"；
     * 留空则由模型自动检测。
     */
    private String language;

    /**
     * 默认采样率（Hz）
     *
     * <p>常见值：16000、24000、44100、48000。
     */
    private Integer sampleRate;

    /**
     * 默认音频格式
     *
     * <p>如 "wav"、"mp3"、"m4a"、"flac"。
     */
    private String format;

    /**
     * 默认提示词
     *
     * <p>用于引导模型识别特定术语或专有名词。
     */
    private String prompt;

    /**
     * 默认采样温度
     *
     * <p>0 表示最确定（贪心），越高结果越发散。
     */
    private Double temperature;

    /**
     * 默认随机种子
     */
    private Long seed;

    /**
     * 默认说话人数
     *
     * <p>0 表示由模型自动推断。
     */
    private Integer speakers;

    /**
     * 音频字节数据
     *
     * <p>部分服务支持直接上传二进制。
     */
    private byte[] audio;

    /**
     * 音频输入流
     */
    private transient InputStream audioInput;

    /**
     * 音频文件路径
     */
    private transient Path audioPath;

    /**
     * 代理地址
     *
     * <p>HTTP / SOCKS5 代理，如 {@code http://127.0.0.1:7890} 或 {@code socks5://127.0.0.1:1080}。
     */
    private String proxy;
}

