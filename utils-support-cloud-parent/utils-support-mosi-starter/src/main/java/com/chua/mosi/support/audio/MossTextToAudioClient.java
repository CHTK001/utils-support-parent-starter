// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.mosi.support.audio;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.audio.TextToAudioResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moss（{@code https://platform.mosi.cn}）文字转语音（TTS）客户端。
 *
 * <p>基于 Moss 语音合成接口 {@code POST /v1/audio/speech} 的 {@link TextToAudioClient}
 * 实现。与 OpenAI TTS 的差异：
 * <ul>
 *   <li>音色字段是 {@code voice_id}（不是 {@code voice}），且只接受 voice id，
 *       不支持 {@code voice_url} / {@code voice_data} 这类行内音频；</li>
 *   <li>{@code input} 内可用停顿标记 {@code [pause 0.8s]}（仅
 *       {@code moss-tts-1.5-flash} 支持）；</li>
 *   <li>{@code language} 只认语言全名（{@code Chinese} / {@code English}），
 *       不认 {@code zh} / {@code en} 这类语言代码；</li>
 *   <li>{@code delivery_method=audio} 直接回二进制音频，
 *       {@code delivery_method=url} 回 JSON {@code {status,url,response_format,content_type}}。</li>
 * </ul>
 *
 * <p>通过 SPI 机制注册以下别名：{@code moss}、{@code mosi}。
 *
 * <p><b>注意不要占用 {@code moss-tts} 这个名字。</b>该名字属于
 * {@code utils-support-deeplearning-onnx-starter} 里的本地 MOSS-TTS-Nano 模型
 * （{@code com.chua.deeplearning.support.onnx.audio.moss.MossTextToAudioClient}），
 * 两者是不同实现：本地那个跑 ONNX 权重、无需密钥，本类走云端 HTTP API。
 * SPI 注册表对同名定义是「累加」而非「覆盖」，重复注册会让
 * {@link com.chua.common.support.spi.ServiceProvider#getNewExtension} 取到一个
 * 不可预期的实现，因此云端这份刻意只用 {@code moss} / {@code mosi}。
 *
 * <p>使用示例：
 * <pre>{@code
 *   byte[] mp3 = TextToAudioClient.create("moss", "sk-moss-xxx", "https://api.mosi.cn/v1")
 *       .model("moss-tts-1.5-flash")
 *       .voice("94aa4989-c7e9-5007-ae42-ab401823e6c9")
 *       .format("mp3")
 *       .synthesize("欢迎使用 Moss API。[pause 0.8s]现在开始生成语音。");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"moss", "mosi"})
public class MossTextToAudioClient implements TextToAudioClient {

    /**
     * Moss 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.mosi.cn/v1";

    /**
     * 默认语音合成模型（同时支持非流式与流式）
     */
    private static final String DEFAULT_MODEL = "moss-tts-1.5-flash";

    /**
     * 默认输出格式（官方推荐 mp3）
     */
    private static final String DEFAULT_FORMAT = "mp3";

    /**
     * 默认交付方式：直接返回二进制音频。
     * 走 url 模式时服务端回 JSON，需要再下载一次，同步合成没必要多一跳。
     */
    private static final String DEFAULT_DELIVERY = "audio";

    /**
     * Moss 语言全名表：语言代码 → 官方全名。
     *
     * <p>上游只接受 {@code Chinese} / {@code English} 这类全名，传 {@code zh} 会被拒。
     * 调用方（如 {@code /api/tts}）习惯传 ISO 639-1，这里做一次归一。</p>
     */
    private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(
            Map.entry("zh", "Chinese"),
            Map.entry("zh-cn", "Chinese"),
            Map.entry("zh-hans", "Chinese"),
            Map.entry("cmn", "Chinese"),
            Map.entry("yue", "Cantonese"),
            Map.entry("zh-hk", "Cantonese"),
            Map.entry("en", "English"),
            Map.entry("en-us", "English"),
            Map.entry("en-gb", "English"),
            Map.entry("ar", "Arabic"),
            Map.entry("cs", "Czech"),
            Map.entry("da", "Danish"),
            Map.entry("nl", "Dutch"),
            Map.entry("fi", "Finnish"),
            Map.entry("fr", "French"),
            Map.entry("de", "German"),
            Map.entry("el", "Greek"),
            Map.entry("he", "Hebrew"),
            Map.entry("hi", "Hindi"),
            Map.entry("hu", "Hungarian"),
            Map.entry("it", "Italian"),
            Map.entry("ja", "Japanese"),
            Map.entry("ko", "Korean"),
            Map.entry("mk", "Macedonian"),
            Map.entry("ms", "Malay"),
            Map.entry("fa", "Persian"),
            Map.entry("pl", "Polish"),
            Map.entry("pt", "Portuguese"),
            Map.entry("ro", "Romanian"),
            Map.entry("ru", "Russian"),
            Map.entry("es", "Spanish"),
            Map.entry("sw", "Swahili"),
            Map.entry("sv", "Swedish"),
            Map.entry("tl", "Tagalog"),
            Map.entry("th", "Thai"),
            Map.entry("tr", "Turkish"),
            Map.entry("vi", "Vietnamese")
    );

    /**
     * 客户端配置
     */
    private final TextToAudioClientSetting setting;

    /**
     * 当前模型（如 moss-tts-1.5-flash）
     */
    private String model;

    /**
     * 当前音色 id（对应上游 voice_id）
     */
    private String voice;

    /**
     * 当前语言（可传语言代码，发送前归一为全名）
     */
    private String language;

    /**
     * 当前输出格式
     */
    private String format;

    /**
     * 当前文本（未通过方法参数传入时使用）
     */
    private String text;

    /**
     * 交付方式：audio / url
     */
    private String deliveryMethod;

    /**
     * 期望音频时长（秒），为空则不发送
     */
    private Double expectedDurationSec;

    /**
     * 构造 Moss TTS 客户端。
     *
     * @param setting 客户端配置
     */
    public MossTextToAudioClient(TextToAudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.voice = setting.getVoice();
        this.language = setting.getLanguage();
        this.format = setting.getFormat();
        this.text = setting.getText();
        this.deliveryMethod = DEFAULT_DELIVERY;
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

    /**
     * 设置音色。
     *
     * <p>Moss 上游字段名为 {@code voice_id}，这里沿用统一的 {@code voice}
     * 入参名，发送时再映射成 {@code voice_id}。</p>
     *
     * @param voice 音色 id（从 List Voices 或 MOSS Create 音色库复制）
     * @return 当前客户端
     */
    @Override
    public TextToAudioClient voice(String voice) {
        this.voice = voice;
        return this;
    }

    @Override
    public TextToAudioClient language(String language) {
        this.language = language;
        return this;
    }

    @Override
    public TextToAudioClient format(String format) {
        this.format = format;
        return this;
    }

    @Override
    public TextToAudioClient speed(Double speed) {
        // Moss 内部没有语速倍率参数，忽略以保持接口一致
        return this;
    }

    @Override
    public TextToAudioClient sampleRate(Integer sampleRate) {
        // 非流式接口不接受 sample_rate，忽略
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

    /**
     * 设置交付方式。
     *
     * @param deliveryMethod {@code audio}（二进制音频）或 {@code url}（JSON 结果地址）
     * @return 当前客户端
     */
    public MossTextToAudioClient deliveryMethod(String deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
        return this;
    }

    /**
     * 设置期望音频时长。
     *
     * @param seconds 期望时长（秒），必须大于 0
     * @return 当前客户端
     */
    public MossTextToAudioClient expectedDurationSec(Double seconds) {
        this.expectedDurationSec = seconds;
        return this;
    }

    @Override
    public byte[] synthesize(String text) {
        String target = text != null ? text : this.text;
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("待合成文本不能为空");
        }
        String voiceId = voice;
        if (voiceId == null || voiceId.isBlank()) {
            throw new IllegalArgumentException("Moss 语音合成必须提供 voice_id（音色 id），请在配置里填写音色");
        }

        String mode = deliveryMethod == null || deliveryMethod.isBlank() ? DEFAULT_DELIVERY : deliveryMethod;
        String normalizedLanguage = normalizeLanguage(language);
        JsonObject payload = JsonObject.create()
                .fluentPut("model", model != null ? model : DEFAULT_MODEL)
                .fluentPut("input", target)
                .fluentPut("voice_id", voiceId)
                .fluentPut("response_format", format != null ? format : DEFAULT_FORMAT)
                .fluentPut("delivery_method", mode)
                .fluentPut(normalizedLanguage != null, "language", normalizedLanguage)
                .fluentPut(expectedDurationSec != null && expectedDurationSec > 0,
                        "expected_duration_sec", expectedDurationSec);

        ClientResponse resp = newBuilder("/audio/speech")
                .json()
                .body(payload.toJSONString())
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("Moss 语音合成请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        if ("url".equalsIgnoreCase(mode)) {
            return download(resolveUrl(resp.getBodyString()));
        }
        return resp.getBody();
    }

    /**
     * 创建异步合成任务。
     *
     * <p>Moss 的异步模式要求 {@code async=true}；返回的 {@code task_id}
     * 交给 {@link #queryTask(String)} 轮询。</p>
     *
     * @param text 要合成的文本
     * @return 任务 id
     */
    @Override
    public String createTask(String text) {
        String target = text != null ? text : this.text;
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("待合成文本不能为空");
        }
        String voiceId = voice;
        if (voiceId == null || voiceId.isBlank()) {
            throw new IllegalArgumentException("Moss 语音合成必须提供 voice_id（音色 id），请在配置里填写音色");
        }
        String normalizedLanguage = normalizeLanguage(language);
        JsonObject payload = JsonObject.create()
                .fluentPut("model", model != null ? model : DEFAULT_MODEL)
                .fluentPut("input", target)
                .fluentPut("voice_id", voiceId)
                .fluentPut("response_format", format != null ? format : DEFAULT_FORMAT)
                .fluentPut("async", true)
                .fluentPut(normalizedLanguage != null, "language", normalizedLanguage)
                .fluentPut(expectedDurationSec != null && expectedDurationSec > 0,
                        "expected_duration_sec", expectedDurationSec);

        ClientResponse resp = newBuilder("/audio/speech")
                .json()
                .body(payload.toJSONString())
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("Moss 语音合成任务创建失败: "
                    + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        Map<String, Object> root = parseJson(resp.getBodyString());
        Object taskId = root == null ? null : firstNonNull(root.get("id"), root.get("task_id"));
        return taskId != null ? taskId.toString() : "moss-tts-" + UUID.randomUUID();
    }

    /**
     * 查询异步任务状态与结果。
     *
     * <p>任务成功后音频在 {@code url}（不会直接回二进制），因此这里会顺带下载，
     * 让调用方拿到的仍是统一的 {@code audioBytes}。</p>
     *
     * @param taskId 任务 id
     * @return 任务状态及结果
     */
    @Override
    public TextToAudioResponse queryTask(String taskId) {
        try {
            ClientResponse resp = newBuilder("/audio/tasks/" + taskId).get();
            if (!resp.isSuccess()) {
                throw new RuntimeException("Moss 任务查询失败: "
                        + resp.getStatusCode() + " - " + resp.getBodyString());
            }
            Map<String, Object> root = parseJson(resp.getBodyString());
            if (root == null) {
                throw new RuntimeException("Moss 任务查询返回无法解析: " + resp.getBodyString());
            }
            TextToAudioResponse.Status status = toStatus(str(root.get("status")));
            TextToAudioResponse.TextToAudioResponseBuilder builder = TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(status)
                    .format(str(root.get("response_format")));
            if (status == TextToAudioResponse.Status.FAILED) {
                Object error = root.get("error");
                builder.errorMessage(error != null ? error.toString() : "Moss 任务失败");
                return builder.build();
            }
            if (status == TextToAudioResponse.Status.SUCCESS) {
                String url = str(root.get("url"));
                if (url != null && !url.isBlank()) {
                    builder.audioBytes(download(url));
                }
                builder.voice(voice);
                builder.format(firstNonBlank(str(root.get("response_format")), currentFormat()));
            }
            return builder.build();
        } catch (Exception e) {
            log.error("查询 Moss TTS 任务失败: {}", e.getMessage(), e);
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> models = new ArrayList<>(2);
        models.add(ModelDefinition.builder()
                .id("moss-tts-1.5-flash")
                .name("moss-tts-1.5-flash")
                .provider("moss")
                .description("Moss 稳定语音合成模型，支持流式、停顿控制与语言指定")
                .capabilities(List.of("text-to-audio"))
                .build());
        models.add(ModelDefinition.builder()
                .id("moss-tts-1.0-pro")
                .name("moss-tts-1.0-pro")
                .provider("moss")
                .description("Moss 专业语音合成模型，仅支持非流式生成")
                .capabilities(List.of("text-to-audio"))
                .build());
        return models;
    }

    /**
     * 把语言代码归一为 Moss 要求的语言全名。
     *
     * @param language 语言代码或全名
     * @return 语言全名；无法识别时返回 null（不发送该字段，由模型自动检测）
     */
    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String trimmed = language.trim();
        String byCode = LANGUAGE_NAMES.get(trimmed.toLowerCase());
        if (byCode != null) {
            return byCode;
        }
        // 已经是全名（Chinese / English …）时原样透传，避免误伤上游新增语言
        return trimmed;
    }

    /**
     * 解析 {@code delivery_method=url} 响应里的音频地址。
     *
     * @param json 响应 JSON
     * @return 音频地址
     */
    private String resolveUrl(String json) {
        Map<String, Object> root = parseJson(json);
        String url = root == null ? null : str(root.get("url"));
        if (url == null || url.isBlank()) {
            throw new RuntimeException("Moss 语音合成未返回音频地址: " + json);
        }
        return url;
    }

    /**
     * 下载音频。
     *
     * @param url 音频地址
     * @return 音频字节
     */
    private byte[] download(String url) {
        ClientResponse resp = HttpClientFactory.of(url)
                .connectTimeout(120000)
                .readTimeout(120000)
                .get();
        if (!resp.isSuccess()) {
            throw new RuntimeException("下载 Moss 音频失败: " + resp.getStatusCode());
        }
        return resp.getBody();
    }

    /**
     * 解析 JSON 字符串。
     *
     * @param json JSON 文本
     * @return Map；解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return Json.fromJson(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 上游任务状态 → 统一状态枚举。
     *
     * @param status 上游状态（PENDING / PROCESSING / SUCCESS / FAILED）
     * @return 统一状态
     */
    private TextToAudioResponse.Status toStatus(String status) {
        String upper = status == null ? "" : status.trim().toUpperCase();
        return switch (upper) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED" -> TextToAudioResponse.Status.SUCCESS;
            case "FAILED", "FAILURE", "ERROR", "CANCELLED", "CANCELED" -> TextToAudioResponse.Status.FAILED;
            case "PROCESSING", "RUNNING", "IN_PROGRESS" -> TextToAudioResponse.Status.RUNNING;
            default -> TextToAudioResponse.Status.PENDING;
        };
    }

    /**
     * 当前生效的输出格式。
     *
     * @return 格式
     */
    private String currentFormat() {
        return format != null && !format.isBlank() ? format : DEFAULT_FORMAT;
    }

    /**
     * 取第一个非空字符串。
     *
     * @param values 候选值
     * @return 第一个非空值，全部为空返回 null
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 取第一个非空对象。
     *
     * @param values 候选值
     * @return 第一个非空值，全部为空返回 null
     */
    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 对象转字符串。
     *
     * @param value 任意对象
     * @return 字符串表示，null 返回 null
     */
    private String str(Object value) {
        return value == null ? null : value.toString();
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
     * <p>本客户端的路径以 {@code /audio/...} 开头，而 {@code baseUrl} 按供应商
     * 模板约定填到 {@code /v1} 为止（{@code https://api.mosi.cn/v1}），
     * 因此这里<b>保留</b>调用方给的 {@code /v1}，只在为空时回落到默认地址。</p>
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