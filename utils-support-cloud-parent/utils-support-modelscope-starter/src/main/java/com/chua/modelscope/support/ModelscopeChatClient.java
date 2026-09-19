package com.chua.modelscope.support;

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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * ModelScope 对话客户端（SPI provider="modelscope"）。
 *
 * <p>调用 ModelScope API-Inference 的 OpenAI 兼容 chat 端点
 * {@code POST {baseUrl}/v1/chat/completions}，支持纯文本对话、多轮历史、
 * 图片（image_url，多模态模型）与文件附件。鉴权使用 Bearer Token
 * （{@code MODELSCOPE_TOKEN}，从魔搭个人中心获取）。
 *
 * <p>调用示例：
 * <pre>{@code
 *   // 纯文本对话
 *   String answer = ChatClient.create("modelscope", "ms-xxx")
 *       .model("Qwen/Qwen2.5-7B-Instruct")
 *       .temperature(0.7)
 *       .maxTokens(2048)
 *       .chatSync("你好，介绍下 ModelScope");
 *
 *   // 多模态：图片+文本
 *   String reply = ChatClient.create("modelscope", "ms-xxx")
 *       .model("Qwen/Qwen2-VL-7B-Instruct")
 *       .addImage("https://example.com/cat.jpg")
 *       .chatSync("描述这张图");
 *
 *   // 多轮历史
 *   String reply = ChatClient.create("modelscope", "ms-xxx")
 *       .model("Qwen/Qwen2.5-7B-Instruct")
 *       .system("你是助手")
 *       .addUserHistory("我叫小明")
 *       .chatSync("你还记得我叫什么吗？");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("modelscope")
public class ModelscopeChatClient implements ChatClient {

    /**
     * 默认对话模型
     */
    private static final String DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct";

    private final ChatClientSetting setting;

    /** 模型 */
    private String model;
    /** system */
    private String system;
    /** temperature */
    private Double temperature;
    /** 最大值Tokens */
    private Integer maxTokens;
    /** 顶部P */
    private Double topP;
    private final List<ChatMessage> history = new ArrayList<>();
    /** externalHistory */
    private List<ChatMessage> externalHistory;
    private final List<String> imageUrls = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();

    /**
     * 构造方法，创建 ModelscopeChat客户端 实例。
     *
     * @param setting 方法入参 setting
     */
    public ModelscopeChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.system = setting.getSystem();
    }

    @Override
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    @Override
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).data(data).mimeType(mimeType).build());
        return this;
    }

    @Override
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).url(url).mimeType(mimeType).build());
        return this;
    }

    @Override
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    @Override
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    @Override
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    @Override
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.attachments.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    public String chatSync(String prompt) {
        return chatSyncWithResponse(prompt).getText();
    }

    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        Map<String, Object> root = postChatCompletions(prompt);
        String text = null;
        AiUsage usage = null;
        try {
            List<Map<String, Object>> choices = castList(root.get("choices"));
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = castMap(choices.getFirst().get("message"));
                Object content = message != null ? message.get("content") : null;
                text = content != null ? content.toString() : null;
            }
            usage = parseUsage(root.get("usage"));
        } catch (Exception e) {
            throw new RuntimeException("ModelScope chat 响应解析失败: " + root, e);
        }
        return ChatSyncResponse.builder().text(text).usage(usage).build();
    }

    @Override
    public List<ModelDefinition> models() {
        return MODELS;
    }

    @Override
    public void close() {
        newChat();
    }

    /**
     * postChatCompletions。
     *
     * @param prompt 提示词，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private Map<String, Object> postChatCompletions(String prompt) {
        JsonObject body = JsonObject.create()
                .fluentPut("model", model != null && !model.isBlank() ? model : DEFAULT_MODEL)
                .fluentPut("messages", buildMessages(prompt));
        if (temperature != null) {
            body.fluentPut("temperature", temperature);
        }
        if (maxTokens != null) {
            body.fluentPut("max_tokens", maxTokens);
        }
        if (topP != null) {
            body.fluentPut("top_p", topP);
        }
        body.fluentPut("stream", false);

        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path(ModelscopeConstants.PATH_CHAT_COMPLETIONS)
                .header("Authorization", buildAuthHeader())
                .json()
                .body(body.toJSONString())
                .connectTimeout(ModelscopeConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(ModelscopeConstants.READ_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("ModelScope chat 请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        try {
            return Json.fromJson(resp.getBodyString(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("ModelScope chat 响应 JSON 解析失败: " + resp.getBodyString(), e);
        }
    }

    /**
     * 构建Messages。
     *
     * @param prompt 提示词，不允许为 null
     * @return 结果列表，无数据时为空列表
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
     * 构建用户消息。
     *
     * @param prompt 提示词，不允许为 null
     * @return Json对象 对象
     */
    private JsonObject buildUserMessage(String prompt) {
        List<JsonObject> parts = new ArrayList<>();
        for (String imageUrl : imageUrls) {
            parts.add(JsonObject.create()
                    .fluentPut("type", "image_url")
                    .fluentPut("image_url", JsonObject.create().fluentPut("url", toRequestUrl(imageUrl))));
        }
        for (Attachment attachment : attachments) {
            if (attachment.data() != null && attachment.data().length > 0) {
                String mime = attachment.mimeType() != null ? attachment.mimeType() : "image/png";
                String dataUri = "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(attachment.data());
                parts.add(JsonObject.create()
                        .fluentPut("type", "image_url")
                        .fluentPut("image_url", JsonObject.create().fluentPut("url", dataUri)));
            } else if (attachment.url() != null) {
                parts.add(JsonObject.create()
                        .fluentPut("type", "image_url")
                        .fluentPut("image_url", JsonObject.create().fluentPut("url", toRequestUrl(attachment.url()))));
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
     * 转为请求URL。
     *
     * @param url URL，不允许为 null
     * @return 结果字符串
     */
    private String toRequestUrl(String url) {
        if (url == null) {
            return "";
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
            return url;
        }
        if (url.startsWith("file:")) {
            return url;
        }
        return Path.of(url).toAbsolutePath().toUri().toString();
    }

    /**
     * 解析Usage。
     *
     * @param usageObj usage对象，不允许为 null
     * @return AiUsage 对象
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
                .provider("modelscope")
                .build();
    }

    /**
     * 构建Auth请求头。
     *
     * @return 结果字符串
     */
    private String buildAuthHeader() {
        String appKey = setting.getAppKey();
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalStateException("ModelScope provider 需要设置 API Token（魔搭个人中心 -> 访问令牌）");
        }
        return "Bearer " + appKey;
    }

    /**
     * normalizeBaseURL。
     *
     * @return 结果字符串
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = ModelscopeConstants.DEFAULT_INFERENCE_BASE_URL;
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
     * cast映射。
     *
     * @param obj 对象，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    /**
     * cast列出。
     *
     * @param obj 对象，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object obj) {
        return obj instanceof List ? (List<Map<String, Object>>) obj : null;
    }

    /**
     * asInteger。
     *
     * @param obj 对象，不允许为 null
     * @return Integer 对象
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
     * ModelScope 知名对话模型清单（节选）。
     */
    private static final List<ModelDefinition> MODELS = List.of(
            ModelDefinition.builder()
                    .id("Qwen/Qwen2.5-7B-Instruct").name("Qwen2.5-7B-Instruct").provider("modelscope")
                    .description("通义千问 2.5 7B 指令对话")
                    .capabilities(List.of("chat")).build(),
            ModelDefinition.builder()
                    .id("Qwen/Qwen2.5-14B-Instruct").name("Qwen2.5-14B-Instruct").provider("modelscope")
                    .description("通义千问 2.5 14B 指令对话")
                    .capabilities(List.of("chat")).build(),
            ModelDefinition.builder()
                    .id("Qwen/Qwen2.5-72B-Instruct").name("Qwen2.5-72B-Instruct").provider("modelscope")
                    .description("通义千问 2.5 72B 指令对话")
                    .capabilities(List.of("chat")).build(),
            ModelDefinition.builder()
                    .id("Qwen/Qwen2-VL-7B-Instruct").name("Qwen2-VL-7B-Instruct").provider("modelscope")
                    .description("通义千问视觉语言 7B 多模态对话")
                    .capabilities(List.of("chat", "vision")).build(),
            ModelDefinition.builder()
                    .id("Qwen/Qwen2-VL-72B-Instruct").name("Qwen2-VL-72B-Instruct").provider("modelscope")
                    .description("通义千问视觉语言 72B 多模态对话")
                    .capabilities(List.of("chat", "vision")).build(),
            ModelDefinition.builder()
                    .id("LLM-Research/Llama-3.2-3B-Instruct").name("Llama-3.2-3B-Instruct").provider("modelscope")
                    .description("Meta Llama 3.2 3B 指令对话")
                    .capabilities(List.of("chat")).build(),
            ModelDefinition.builder()
                    .id("deepseek-ai/DeepSeek-V2-Chat").name("DeepSeek-V2-Chat").provider("modelscope")
                    .description("DeepSeek V2 MoE 对话模型")
                    .capabilities(List.of("chat")).build(),
            ModelDefinition.builder()
                    .id("iic/nlp_gte_sentence-embedding_chinese-large").name("GTE Large").provider("modelscope")
                    .description("GTE 中文大模型向量嵌入")
                    .capabilities(List.of("text-embedding")).build()
    );
}
