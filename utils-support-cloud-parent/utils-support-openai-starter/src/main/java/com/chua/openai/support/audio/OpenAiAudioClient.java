package com.chua.openai.support.audio;

import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 语音识别（ASR / STT）客户端。
 *
 * <p>基于 OpenAI 音频标准接口 {@code POST /v1/audio/transcriptions} 的 {@link VirtualClient}
 * 实现，支持 OpenAI 兼容接口的所有服务商（如 OpenAI、SiliconFlow、SenseTime、b.ai 等）。
 *
 * <p>通过 SPI 机制注册以下别名：
 * <ul>
 *   <li>openai — OpenAI 官方（whisper-1 / gpt-4o-transcribe）</li>
 *   <li>openai-asr — 语义化别名</li>
 *   <li>siliconflow / sensetime / github / gitee — OpenAI 兼容服务商</li>
 * </ul>
 *
 * <p>调用示例：
 * <pre>{@code
 *   String text = VirtualClient.create("openai", "sk-xxx")
 *       .model("whisper-1")
 *       .language("zh")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"openai", "openai-asr", "siliconflow", "sensetime", "github", "gitee"})
public class OpenAiAudioClient implements VirtualClient {

    /**
     * OpenAI 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * 默认转录模型
     */
    private static final String DEFAULT_MODEL = "whisper-1";

    /**
     * 客户端配置
     */
    private final AudioClientSetting setting;

    /**
     * 当前模型
     */
    private String model;

    /**
     * 当前语言
     */
    private String language;

    /**
     * 当前提示词
     */
    private String prompt;

    /**
     * 音频字节
     */
    private byte[] audio;

    /**
     * 音频输入流
     */
    private InputStream audioInput;

    /**
     * 音频文件路径
     */
    private Path audioPath;

    /**
     * 构造 OpenAI 语音识别客户端。
     *
     * @param setting 客户端配置
     */
    public OpenAiAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.language = setting.getLanguage();
        this.prompt = setting.getPrompt();
        this.audio = setting.getAudio();
        this.audioInput = setting.getAudioInput();
        this.audioPath = setting.getAudioPath();
    }

    @Override
    public VirtualClient provider(String provider) {
        setting.setProvider(provider);
        return this;
    }

    @Override
    public VirtualClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public VirtualClient language(String language) {
        this.language = language;
        return this;
    }

    @Override
    public VirtualClient sampleRate(Integer sampleRate) {
        return this;
    }

    @Override
    public VirtualClient format(String format) {
        return this;
    }

    @Override
    public VirtualClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    public VirtualClient temperature(Double temperature) {
        return this;
    }

    @Override
    public VirtualClient seed(Long seed) {
        return this;
    }

    @Override
    public VirtualClient audio(byte[] audio) {
        this.audio = audio;
        return this;
    }

    @Override
    public VirtualClient audio(InputStream input) {
        this.audioInput = input;
        return this;
    }

    @Override
    public VirtualClient audio(Path path) {
        this.audioPath = path;
        return this;
    }

    @Override
    public VirtualClient speakers(Integer speakers) {
        return this;
    }

    @Override
    public String transcribe(Path path) {
        try {
            byte[] fileBytes = resolveAudioBytes(path);
            String filename = resolveFilename(path);
            String contentType = resolveContentType(filename);

            HttpClientBuilder builder = newBuilder("/audio/transcriptions")
                    .formData("model", model != null ? model : DEFAULT_MODEL)
                    .formData("file", fileBytes, contentType, filename);
            if (language != null && !language.isBlank()) {
                builder.formData("language", language);
            }
            if (prompt != null && !prompt.isBlank()) {
                builder.formData("prompt", prompt);
            }
            ClientResponse resp = builder.post();
            if (!resp.isSuccess()) {
                throw new RuntimeException("语音识别请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
            }
            return parseTranscript(resp.getBodyString());
        } catch (IOException e) {
            throw new RuntimeException("读取音频文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String createTask(Path path) {
        transcribe(path);
        return "openai-asr-" + UUID.randomUUID();
    }

    @Override
    public AudioResponse queryTask(String taskId) {
        try {
            String transcript = transcribe(this.audioPath);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(transcript)
                    .detectedLanguage(language)
                    .build();
        } catch (Exception e) {
            log.error("查询 ASR 任务失败: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> models = new ArrayList<>(3);
        models.add(ModelDefinition.builder()
                .id("whisper-1")
                .name("whisper-1")
                .provider("openai")
                .description("OpenAI Whisper 通用语音识别模型")
                .capabilities(List.of("audio-to-text"))
                .build());
        models.add(ModelDefinition.builder()
                .id("gpt-4o-transcribe")
                .name("gpt-4o-transcribe")
                .provider("openai")
                .description("OpenAI GPT-4o 语音识别模型")
                .capabilities(List.of("audio-to-text"))
                .build());
        models.add(ModelDefinition.builder()
                .id("gpt-4o-mini-transcribe")
                .name("gpt-4o-mini-transcribe")
                .provider("openai")
                .description("OpenAI GPT-4o-mini 低延迟语音识别模型")
                .capabilities(List.of("audio-to-text"))
                .build());
        return models;
    }

    /**
     * 解析音频字节：优先使用已设置的 audio 字节，其次音频路径。
     *
     * @param path 调用方法时传入的路径（可为 null）
     * @return 音频字节
     * @throws IOException 读取失败时抛出
     */
    private byte[] resolveAudioBytes(Path path) throws IOException {
        if (audio != null && audio.length > 0) {
            return audio;
        }
        if (audioInput != null) {
            return audioInput.readAllBytes();
        }
        Path effective = path != null ? path : audioPath;
        if (effective == null) {
            throw new IllegalArgumentException("未指定音频文件路径");
        }
        return Files.readAllBytes(effective);
    }

    /**
     * 解析上传文件名。
     *
     * @param path 音频路径
     * @return 文件名（带扩展名）
     */
    private String resolveFilename(Path path) {
        Path effective = path != null ? path : audioPath;
        String name = effective != null ? effective.getFileName().toString() : "audio";
        return name.contains(".") ? name : name + ".wav";
    }

    /**
     * 解析音频 Content-Type。
     *
     * @param filename 文件名
     * @return MIME 类型
     */
    private String resolveContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        if (lower.endsWith(".webm")) {
            return "audio/webm";
        }
        return "application/octet-stream";
    }

    /**
     * 解析转录响应 JSON 中的文本。
     *
     * @param json 响应 JSON 字符串
     * @return 转录文本
     */
    @SuppressWarnings("unchecked")
    private String parseTranscript(String json) {
        Map<String, Object> root = Json.fromJson(json, Map.class);
        Object text = root.get("text");
        return text != null ? text.toString() : "";
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
                .connectTimeout(120000)
                .readTimeout(120000);
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
