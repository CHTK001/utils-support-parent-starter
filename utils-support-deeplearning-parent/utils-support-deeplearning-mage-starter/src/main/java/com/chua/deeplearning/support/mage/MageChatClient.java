package com.chua.deeplearning.support.mage;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Mage 多模态理解客户端（SPI provider="mage"）。
 *
 * <p>通过 HTTP 调用<b>自部署</b>的 Mage-VL 推理服务（见本模块 {@code scripts/server.py}），
 * 后端为微软 Mage-VL 4B —— 编解码器原生的图像/视频理解基础模型
 * （Mage-ViT 从零训练视觉栈 + Qwen3-4B 解码器，支持主动流式评论）。
 *
 * <p>服务端接口为 OpenAI Chat Completions 风格子集：
 * <pre>
 * POST /v1/chat/completions {model, messages[{role, content}]}
 *   content 支持纯文本或多模态数组：
 *   [{type: "text", text}, {type: "image_url", image_url: {url}}, {type: "video_url", video_url: {url}}]
 *   url 支持 http(s) 远程地址与 data:image/...;base64, 数据 URI
 * GET  /v1/models
 * </pre>
 *
 * <p>调用示例：
 * <pre>{@code
 *   // 图文理解
 *   String answer = ChatClient.create("mage", "sk-xxx")
 *       .baseUrl("http://gpu-host:7861")
 *       .model("mage-vl")
 *       .addImage("file:///data/scene.jpg")          // 也支持 http(s) 与本地路径
 *       .chatSync("描述这段画面里发生了什么");
 *
 *   // 纯文本多轮对话
 *   String reply = ChatClient.create("mage", "sk-xxx")
 *       .model("mage-vl")
 *       .system("你是监控分析助手")
 *       .addUserHistory("上一帧有人翻越围栏")
 *       .chatSync("当前帧需要注意什么？");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("mage")
public class MageChatClient implements ChatClient {

    /**
     * 默认服务地址
     */
    private static final String DEFAULT_URL = "http://127.0.0.1:7861";

    /**
     * 默认模型 ID
     */
    private static final String DEFAULT_MODEL = "mage-vl";

    /**
     * 读超时（毫秒）：长视频编码 + 自回归解码可能耗时较长
     */
    private static final long READ_TIMEOUT_MILLIS = 300_000L;

    /**
     * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
     * 当前使用的模型 ID
     */
    private String model;

    /**
     * 当前系统提示词
     */
    private String system;

    /**
     * 对话历史消息列表
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 外部传入的完整历史记录；非空时覆盖内部 history
     */
    private List<ChatMessage> externalHistory;

    /**
     * 图片附件 URL / data URI / 本地路径列表
     */
    private final List<String> imageUrls = new ArrayList<>();

    /**
     * 视频附件 URL / data URI / 本地路径列表
     */
    private final List<String> videoUrls = new ArrayList<>();

    /**
     * 文件附件列表（图片字节将转为 data URI 参与请求）
     */
    private final List<Attachment> attachments = new ArrayList<>();

    /**
     * 构造 Mage 多模态理解客户端。
     *
     * @param setting 客户端配置
     */
    public MageChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.system = setting.getSystem();
    }

    @Override
    /** Model */
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** System */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /** 添加Image */
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    /**
     * 添加视频附件（Mage-VL 支持视频理解）。
     *
     * @param videoUrl 视频 URL、data URI 或服务端可访问的本地路径
     * @return 当前客户端实例
     */
    public ChatClient addVideo(String videoUrl) {
        this.videoUrls.add(videoUrl);
        return this;
    }

    @Override
    /** 添加Attachment */
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).data(data).mimeType(mimeType).build());
        return this;
    }

    @Override
    /** 添加AttachmentUrl */
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).url(url).mimeType(mimeType).build());
        return this;
    }

    @Override
    /** 添加UserHistory */
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    @Override
    /** 添加AssistantHistory */
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    @Override
    /** History */
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    @Override
    /** NewChat */
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.videoUrls.clear();
        this.attachments.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    /** ChatSync */
    public String chatSync(String prompt) {
        Map<String, Object> root = postChatCompletions(prompt);
        try {
            List<Map<String, Object>> choices = castList(root.get("choices"));
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Mage chat/completions 返回的 choices 为空: " + root);
            }
            Map<String, Object> message = castMap(choices.getFirst().get("message"));
            Object content = message != null ? message.get("content") : null;
            if (content == null) {
                throw new RuntimeException("Mage 响应缺少 message.content: " + root);
            }
            return content.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Mage 响应解析失败: " + root, e);
        }
    }

    @Override
    /** ChatSyncWithResponse */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        Map<String, Object> root = postChatCompletions(prompt);
        String text;
        AiUsage usage = null;
        try {
            List<Map<String, Object>> choices = castList(root.get("choices"));
            Map<String, Object> message = (choices == null || choices.isEmpty())
                    ? null : castMap(choices.getFirst().get("message"));
            Object content = message != null ? message.get("content") : null;
            text = content != null ? content.toString() : null;
            usage = parseUsage(root.get("usage"));
        } catch (Exception e) {
            throw new RuntimeException("Mage 响应解析失败: " + root, e);
        }
        return ChatSyncResponse.builder().text(text).usage(usage).build();
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return MODELS;
    }

    @Override
    /** 关闭 */
    public void close() {
        newChat();
    }

    /**
     * 组装并发送 chat/completions 请求。
     *
     * @param prompt 用户输入
     * @return 服务端 JSON 响应
     */
    private Map<String, Object> postChatCompletions(String prompt) {
        JsonObject requestBody = JsonObject.create()
                .fluentPut("model", model != null && !model.isBlank() ? model : DEFAULT_MODEL)
                .fluentPut("messages", buildMessages(prompt));

        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path("/v1/chat/completions")
                .header("Authorization", buildAuthHeader())
                .json()
                .body(requestBody.toJSONString())
                .connectTimeout(10_000L)
                .readTimeout(READ_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("Mage 对话请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        try {
            return Json.fromJson(resp.getBodyString(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Mage 响应 JSON 解析失败: " + resp.getBodyString(), e);
        }
    }

    /**
     * 构建 OpenAI 风格的 messages 数组。
     *
     * <p>包含：系统提示词（可选）、外部或内部对话历史、携带多模态内容的当前用户消息。
     * 当前用户消息的图片/视频/字节附件以 content parts 形式附加；
     * 无任何附件时退化为纯文本字符串以保持兼容。
     *
     * @param prompt 用户输入
     * @return 消息列表（JsonObject 结构）
     */
    private List<JsonObject> buildMessages(String prompt) {
        List<JsonObject> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(JsonObject.create()
                    .fluentPut("role", "system")
                    .fluentPut("content", system));
        }
        List<ChatMessage> effectiveHistory = externalHistory != null ? externalHistory : history;
        for (ChatMessage msg : effectiveHistory) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            messages.add(JsonObject.create()
                    .fluentPut("role", msg.getRole())
                    .fluentPut("content", msg.getContent()));
        }
        messages.add(buildUserMessage(prompt));
        return messages;
    }

    /**
     * 构建当前用户消息。
     *
     * @param prompt 用户输入
     * @return 含多模态 content parts 或纯文本的用户消息
     */
    private JsonObject buildUserMessage(String prompt) {
        List<JsonObject> parts = new ArrayList<>();
        for (String imageUrl : imageUrls) {
            parts.add(JsonObject.create()
                    .fluentPut("type", "image_url")
                    .fluentPut("image_url", JsonObject.create().fluentPut("url", toRequestUrl(imageUrl))));
        }
        for (String videoUrl : videoUrls) {
            parts.add(JsonObject.create()
                    .fluentPut("type", "video_url")
                    .fluentPut("video_url", JsonObject.create().fluentPut("url", toRequestUrl(videoUrl))));
        }
        for (Attachment attachment : attachments) {
            if (attachment.data() != null && attachment.data().length > 0) {
                String mime = attachment.mimeType() != null ? attachment.mimeType() : "image/png";
                String dataUri = "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(attachment.data());
                boolean isVideo = mime.startsWith("video/");
                parts.add(JsonObject.create()
                        .fluentPut("type", isVideo ? "video_url" : "image_url")
                        .fluentPut(isVideo ? "video_url" : "image_url",
                                JsonObject.create().fluentPut("url", dataUri)));
            } else if (attachment.url() != null) {
                boolean isVideo = attachment.mimeType() != null && attachment.mimeType().startsWith("video/");
                parts.add(JsonObject.create()
                        .fluentPut("type", isVideo ? "video_url" : "image_url")
                        .fluentPut(isVideo ? "video_url" : "image_url",
                                JsonObject.create().fluentPut("url", toRequestUrl(attachment.url()))));
            }
        }
        if (parts.isEmpty()) {
            return JsonObject.create()
                    .fluentPut("role", "user")
                    .fluentPut("content", prompt != null ? prompt : "");
        }
        parts.add(0, JsonObject.create()
                .fluentPut("type", "text")
                .fluentPut("text", prompt != null ? prompt : ""));
        return JsonObject.create()
                .fluentPut("role", "user")
                .fluentPut("content", parts);
    }

    /**
     * 将附件地址转换为服务端可访问的 URL。
     *
     * <p>本地文件路径（含 file:// 协议）转为 {@code file://} 绝对路径 data 引用，
     * 由 Mage 服务端读取；http(s)/data URI 直接透传。
     *
     * @param url 原始地址
     * @return 服务端可访问的地址
     */
    private String toRequestUrl(String url) {
        if (url == null) {
            return "";
        }
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("data:") || lower.startsWith("file:")) {
            return url;
        }
        return java.nio.file.Path.of(url).toAbsolutePath().toUri().toString();
    }

    /**
     * 解析用量信息。
     *
     * @param usageObj 服务端返回的 usage 对象
     * @return 用量信息；缺失时返回 null
     */
    private AiUsage parseUsage(Object usageObj) {
        Map<String, Object> usage = castMap(usageObj);
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        return AiUsage.builder()
                .inputTokens(asInteger(usage.get("prompt_tokens")))
                .outputTokens(asInteger(usage.get("completion_tokens")))
                .totalTokens(asInteger(usage.get("total_tokens")))
                .model(model)
                .provider("mage")
                .build();
    }

    /**
     * 构建 Authorization 头。
     *
     * @return 已配置 appKey 时返回 Bearer 头，否则返回空串（不携带认证）
     */
    private String buildAuthHeader() {
        String appKey = setting.getAppKey();
        return (appKey == null || appKey.isBlank()) ? "" : "Bearer " + appKey;
    }

    /**
     * 规范化服务基地址。
     *
     * <p>移除末尾斜杠与多余的 {@code /v1} 后缀（路径由客户端拼接），
     * 未配置时使用默认地址 {@value DEFAULT_URL}。
     *
     * @return 规范化后的 URL
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        return url;
    }

    /**
     * 安全类型转换：Map。
     *
     * @param obj 原始对象
     * @return Map 视图；类型不符时返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    /**
     * 安全类型转换：Map 列表。
     *
     * @param obj 原始对象
     * @return 列表视图；类型不符时返回 null
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object obj) {
        return obj instanceof List ? (List<Map<String, Object>>) obj : null;
    }

    /**
     * 安全整数转换。
     *
     * @param obj 原始对象
     * @return 整数值；无法转换时返回 null
     */
    private Integer asInteger(Object obj) {
        if (obj instanceof Number number) {
            return number.intValue();
        }
        try {
            return obj != null ? Integer.parseInt(obj.toString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Mage-VL 家族模型定义。
     */
    private static final List<ModelDefinition> MODELS = List.of(
            ModelDefinition.builder()
                    .id("mage-vl").name("Mage-VL").provider("mage")
                    .description("Mage-VL-4B 编解码器原生多模态理解：图像/视频问答 + 时间定位 + 事件门控流式评论")
                    .capabilities(List.of("chat", "vision", "video", "streaming")).build(),
            ModelDefinition.builder()
                    .id("mage-vit").name("Mage-ViT").provider("mage")
                    .description("Mage-ViT 编解码器原生视觉编码器（仅 ViT 预训练权重）")
                    .capabilities(List.of("embedding")).build()
    );
}
