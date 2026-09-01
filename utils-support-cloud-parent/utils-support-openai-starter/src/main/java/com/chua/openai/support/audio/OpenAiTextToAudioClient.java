package com.chua.openai.support.audio;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.audio.TextToAudioResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI 文字转语音（TTS）客户端。
 *
 * <p>基于 OpenAI 音频标准接口 {@code POST /v1/audio/speech} 的 {@link TextToAudioClient}
 * 实现，支持 OpenAI 兼容接口的所有服务商（如 OpenAI、SiliconFlow、SenseTime、b.ai 等）。
 *
 * <p>通过 SPI 机制注册以下别名：
 * <ul>
 *   <li>openai — OpenAI 官方（tts-1 / tts-1-hd / gpt-4o-mini-tts）</li>
 *   <li>openai-tts — 语义化别名</li>
 *   <li>siliconflow / sensetime / github / gitee — OpenAI 兼容服务商</li>
 * </ul>
 *
 * <p>调用示例：
 * <pre>{@code
 *   byte[] mp3 = TextToAudioClient.create("openai", "sk-xxx")
 *       .model("tts-1")
 *       .voice("alloy")
 *       .format("mp3")
 *       .synthesize("你好世界");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"openai", "openai-tts", "siliconflow", "sensetime", "github", "gitee"})
public class OpenAiTextToAudioClient implements TextToAudioClient {

    /**
     * OpenAI 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * 默认 TTS 模型
     */
    private static final String DEFAULT_MODEL = "tts-1";

    /**
     * 默认发音人
     */
    private static final String DEFAULT_VOICE = "alloy";

    /**
     * 默认输出格式
     */
    private static final String DEFAULT_FORMAT = "mp3";

    /**
     * 客户端配置
     */
    private final TextToAudioClientSetting setting;

    /**
     * 当前模型
     */
    private String model;

    /**
     * 当前发音人
     */
    private String voice;

    /**
     * 当前输出格式
     */
    private String format;

    /**
     * 当前语速
     */
    private Double speed;

    /**
     * 当前文本
     */
    private String text;

    /**
     * 构造 OpenAI TTS 客户端。
     *
     * @param setting 客户端配置
     */
    public OpenAiTextToAudioClient(TextToAudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.voice = setting.getVoice();
        this.format = setting.getFormat();
        this.speed = setting.getSpeed();
        this.text = setting.getText();
    }

    @Override
    public TextToAudioClient provider(String provider) {
        setting.setProvider(provider);
        return this;
    }

    @Override
    public TextToAudioClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public TextToAudioClient voice(String voice) {
        this.voice = voice;
        return this;
    }

    @Override
    public TextToAudioClient language(String language) {
        return this;
    }

    @Override
    public TextToAudioClient format(String format) {
        this.format = format;
        return this;
    }

    @Override
    public TextToAudioClient speed(Double speed) {
        this.speed = speed;
        return this;
    }

    @Override
    public TextToAudioClient sampleRate(Integer sampleRate) {
        return this;
    }

    @Override
    public TextToAudioClient temperature(Double temperature) {
        return this;
    }

    @Override
    public TextToAudioClient seed(Long seed) {
        return this;
    }

    @Override
    public TextToAudioClient text(String text) {
        this.text = text;
        return this;
    }

    @Override
    public byte[] synthesize(String text) {
        String target = text != null ? text : this.text;
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("待合成文本不能为空");
        }

        String requestBody = JsonObject.create()
                .fluentPut("model", model != null ? model : DEFAULT_MODEL)
                .fluentPut("input", target)
                .fluentPut("voice", voice != null ? voice : DEFAULT_VOICE)
                .fluentPut("response_format", format != null ? format : DEFAULT_FORMAT)
                .fluentPut(speed != null, "speed", speed)
                .toJSONString();

        ClientResponse resp = newBuilder("/audio/speech")
                .json()
                .body(requestBody)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("语音合成请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        return resp.getBody();
    }

    @Override
    public String createTask(String text) {
        synthesize(text);
        return "openai-tts-" + UUID.randomUUID();
    }

    @Override
    public TextToAudioResponse queryTask(String taskId) {
        try {
            byte[] audio = synthesize(this.text);
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.SUCCESS)
                    .audioBytes(audio)
                    .format(format != null ? format : DEFAULT_FORMAT)
                    .voice(voice != null ? voice : DEFAULT_VOICE)
                    .build();
        } catch (Exception e) {
            log.error("查询 TTS 任务失败: {}", e.getMessage(), e);
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> models = new ArrayList<>(3);
        models.add(ModelDefinition.builder()
                .id("tts-1")
                .name("tts-1")
                .provider("openai")
                .description("OpenAI 标准语音合成模型")
                .capabilities(List.of("text-to-audio"))
                .build());
        models.add(ModelDefinition.builder()
                .id("tts-1-hd")
                .name("tts-1-hd")
                .provider("openai")
                .description("OpenAI 高质量语音合成模型")
                .capabilities(List.of("text-to-audio"))
                .build());
        models.add(ModelDefinition.builder()
                .id("gpt-4o-mini-tts")
                .name("gpt-4o-mini-tts")
                .provider("openai")
                .description("OpenAI 低延迟语音合成模型")
                .capabilities(List.of("text-to-audio"))
                .build());
        return models;
    }

    /**
     * 创建带公共配置的 HTTP 请求构建器。
     *
     * @param path API 路径（含前导斜杠）
     * @return 请求构建器
     */
    private HttpClientBuilder newBuilder(String path) {
        HttpClientBuilder builder = HttpClientFactory.of(normalizeBaseUrl())
                .path(path)
                .header("Authorization", "Bearer " + setting.getAppKey())
                .connectTimeout(120000);
        String proxyStr = setting.getProxy();
        if (proxyStr != null && !proxyStr.isBlank()) {
            String[] parts = proxyStr.replace("http://", "").replace("https://", "").split(":");
            if (parts.length == 2) {
                builder.proxy(parts[0], Integer.parseInt(parts[1]));
            }
        }
        return builder;
    }

    /**
     * 规范化 API 基础地址。
     *
     * @return 规范化后的 URL
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    @Override
    public void close() {
        // 无独立连接资源需要释放
    }
}
