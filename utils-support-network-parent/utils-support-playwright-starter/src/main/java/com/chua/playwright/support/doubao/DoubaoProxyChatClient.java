package com.chua.playwright.support.doubao;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.generation.ImageGenerationResult;
import com.chua.common.support.ai.generation.VideoGenerationResult;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 豆包逆向代理对话客户端。
 *
 * <p>基于 {@link DoubaoBrowserSession} 在 Playwright 浏览器页面内发起
 * 原生 fetch 请求，借助字节跳动前端 JS hook 自动注入 {@code a_bogus} +
 * {@code msToken} 签名，绕过反爬虫墙，实现 Cookie 认证的豆包免费对话。
 *
 * <p>SPI 名称：{@code doubao-proxy}，appKey 为 Cookie 串
 * （{@code sessionid=xxx; ttwid=xxx; passport_csrf_token=xxx}）。
 *
 * <p>用法：
 * <pre>{@code
 * ChatClient client = ChatClient.create("doubao-proxy",
 *     "sessionid=abc; ttwid=def; passport_csrf_token=ghi");
 * String answer = client.model("doubao-think").chatSync("你好");
 * }</pre>
 *
 * @author CH
 * @since 2026/08/11
 */
@Slf4j
@Spi("doubao-proxy")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class DoubaoProxyChatClient implements ChatClient {

    /**
     * 默认豆包 Web 基础地址。
     */
    private static final String DEFAULT_BASE_URL = "https://www.doubao.com";

    /**
     * 浏览器会话。
     */
    private final DoubaoBrowserSession session;

    /**
     * 客户端配置。
     */
    private final ChatClientSetting setting;

    /**
     * 当前模型名称。
     */
    private String model;

    /**
     * 当前温度参数。
     */
    private Double temperature;

    /**
     * 当前最大 Token 数。
     */
    private Integer maxTokens;

    /**
     * 当前系统提示词。
     */
    private String system;

    /**
     * 当前会话 ID。
     */
    private String conversationId;

    /**
     * extra Body
     */
    private Map<String, Object> extraBody;

    /**
     * top P
     */
    private Double topP;
    /**
     * stop
     */
    private List<String> stop;
    /**
     * seed
     */
    private Long seed;
    /**
     * response Format
     */
    private String responseFormat;
    /**
     * image Urls
     */
    private final List<String> imageUrls = new ArrayList<>();
    /**
     * attachments
     */
    private final List<Attachment> attachments = new ArrayList<>();
    /**
     * tools
     */
    private final List<ChatTool> tools = new ArrayList<>();
    /**
     * tool Choice
     */
    private String toolChoice;

    /**
     * 是否启用深度思考。
     */
    private boolean thinking;

    /**
     * 深度思考力度。
     */
    private String thinkingEffort;

    /**
     * 是否启用智能搜索。
     */
    private boolean smartSearch;

    /**
     * 技能管理器（用于 prompt 注入）。
     */
    private SkillManager skillManager;

    /**
     * 对话历史消息列表。
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 外部传入的完整历史记录。
     */
    private List<ChatMessage> externalHistory;

    /**
     * 构造豆包逆向代理对话客户端。
     *
     * @param setting 客户端配置，其中 appKey 为 Cookie 串
     */
    public DoubaoProxyChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        this.session = new DoubaoBrowserSession(setting.getAppKey(), null);
        this.session.init();
    }

    @Override
    /** Model */
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** Temperature */
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    /** 最大值Tokens */
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    /** System */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /** ExtraBody */
    public ChatClient extraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody;
        return this;
    }

    @Override
    /** Thinking */
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    @Override
    /** ThinkingEffort */
    public ChatClient thinkingEffort(String effort) {
        this.thinkingEffort = effort;
        return this;
    }

    @Override
    /** Smart搜索 */
    public ChatClient smartSearch(boolean smartSearch) {
        this.smartSearch = smartSearch;
        return this;
    }

@Override
    /** Skill */
    public ChatClient skill(SkillManager skillManager) {
        this.skillManager = skillManager;
        return this;
    }

    @Override
    /** TopP */
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    /** 停止 */
    public ChatClient stop(List<String> stop) {
        this.stop = stop;
        return this;
    }

    @Override
    /** Seed */
    public ChatClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    /** Response格式化 */
    public ChatClient responseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
        return this;
    }

    @Override
    /** 添加Image */
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
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
    /** Tools */
    public ChatClient tools(List<ChatTool> tools) {
        this.tools.clear();
        if (tools != null) {
            this.tools.addAll(tools);
        }
        return this;
    }

    @Override
    /** Tool */
    public ChatClient tool(ChatTool tool) {
        if (tool != null) {
            this.tools.add(tool);
        }
        return this;
    }

    @Override
    /** ToolChoice */
    public ChatClient toolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
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
    /** Session */
    public ChatClient session(String sessionId) {
        this.conversationId = sessionId;
        return this;
    }

@Override
    /** NewChat */
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        this.conversationId = null;
        this.imageUrls.clear();
        this.attachments.clear();
        this.tools.clear();
        return this;
    }

    @Override
    /** ChatSync */
    public String chatSync(String prompt) {
        StringBuilder result = new StringBuilder();
        chat(prompt, response -> {
            if (response.getState() == ChatResponse.State.STREAMING
                    && response.getContent() != null) {
                result.append(response.getContent());
            }
        });
        return result.toString();
    }

    @Override
    /** Chat */
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException(e);
        });
    }

    @Override
    /**
     * 对话
     * @param prompt prompt
     * @param consumer consumer
     * @param onComplete onComplete
     * @param onError onError
     */
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder()
                .state(ChatResponse.State.START)
                .build());

        try {
            String actualModel = model != null ? model : setting.getModel();
            String baseUrl = setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()
                    ? setting.getBaseUrl() : DEFAULT_BASE_URL;
            String url = baseUrl + DoubaoConstants.CHAT_COMPLETION_PATH
                    + "?aid=" + DoubaoConstants.AID + "&device_platform=" + DoubaoConstants.DEVICE_PLATFORM;

            if (tools != null && !tools.isEmpty()) {
                StringBuilder toolPrompt = new StringBuilder();
                toolPrompt.append("可用的工具：\n");
                for (ChatTool tool : tools) {
                    toolPrompt.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
                    if (tool.getParameters() != null) {
                        toolPrompt.append("  参数：").append(Json.toJson(tool.getParameters())).append("\n");
                    }
                }
                if (toolChoice != null) {
                    toolPrompt.append("请使用工具：").append(toolChoice).append("\n");
                }
                prompt = toolPrompt.toString() + "\n" + prompt;
            }

            String body = buildRequestBody(prompt, actualModel);
            history.add(ChatMessage.builder().role("user").content(prompt).build());

            DoubaoChatResult result = session.chat(url, body, conversationId, (type, content) -> {
                ChatResponse.State state = "text".equals(type)
                        ? ChatResponse.State.STREAMING : ChatResponse.State.STREAMING;
                consumer.accept(ChatResponse.builder()
                        .state(state)
                        .content(content)
                        .reasoningContent("thinking".equals(type) ? content : null)
                        .build());
            });

if (result.isSuccess()) {
                if (result.conversationId() != null && !result.conversationId().isEmpty()) {
                    this.conversationId = result.conversationId();
                }
                String fullText = result.text();
                history.add(ChatMessage.builder().role("assistant").content(fullText).build());
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(fullText)
                        .fullContent(fullText)
                        .reasoningContent(result.thinkingContent())
                        .usage(AiUsage.builder()
                                .model(model != null ? model : "doubao")
                                .provider("doubao-proxy")
                                .startTime(startTime)
                                .durationMillis(System.currentTimeMillis() - startTime)
                                .build())
                        .build());
            } else {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage(result.errorMessage())
                        .build());
            }
            onComplete.run();
        } catch (Exception e) {
            log.error("豆包逆向代理对话失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    @Override
/** 关闭 */
public void close() {
        if (conversationId != null && !conversationId.isEmpty()) {
            session.deleteConversation(conversationId);
        }
        session.close();
    }

    /**
    * 构建豆包逆向协议请求体。
    *
    * <p>思考模式（{@code use_deep_think} / {@code use_auto_cot}）等参数
    * 通过 {@link #extraBody(Map)} 传入，模型名仅作为标识透传。
    *
    * @param prompt   用户输入
    * @param modelName 模型名称
    * @return JSON 请求体字符串
    */
    private String buildRequestBody(String prompt, String modelName) {
        boolean useDeepThink = thinking;
        boolean useAutoCot = false;

        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(actualSystem, skillManager);
        }

        JsonObject completionOption = JsonObject.create()
                .fluent("is_regen", false)
                .fluent("with_suggest", true)
                .fluent("need_create_conversation", conversationId == null || conversationId.isEmpty())
                .fluent("launch_stage", 1)
                .fluent("is_replace", false)
                .fluent("is_delete", false)
                .fluent("is_ai_playground", false)
                .fluent("memory_type", 2)
                .fluent("message_from", 0)
                .fluent("use_deep_think", useDeepThink)
                .fluent("use_auto_cot", useAutoCot)
                .fluent("enable_web_search", smartSearch)
                .fluent("resend_for_regen", false)
.fluent("enable_commerce_credit", false);

        if (topP != null) {
            completionOption.fluent("top_p", topP);
        }
        if (stop != null && !stop.isEmpty()) {
            JsonArray stopArr = new JsonArray();
            for (String s : stop) { stopArr.add(s); }
            completionOption.fluent("stop", stopArr);
        }
        if (seed != null) {
            completionOption.fluent("seed", seed);
        }
        if (responseFormat != null) {
            completionOption.fluent("response_format", responseFormat);
        }

        if (extraBody != null && !extraBody.isEmpty()) {
            Object dt = extraBody.get("use_deep_think");
            if (dt != null) {
                completionOption.fluentPut("use_deep_think", dt);
            }
            Object ac = extraBody.get("use_auto_cot");
            if (ac != null) {
                completionOption.fluentPut("use_auto_cot", ac);
            }
            extraBody.forEach((k, v) -> {
                if (!"use_deep_think".equals(k) && !"use_auto_cot".equals(k)) {
                    completionOption.fluentPut(k, v);
                }
            });
        }

        String actualSystemText = actualSystem;
JsonObject content = JsonObject.of("text", prompt);
        JsonArray attachments = new JsonArray();
        for (String imgUrl : imageUrls) {
            attachments.add(JsonObject.create()
                .fluent("type", 1)
                .fluent("image", JsonObject.of("url", imgUrl)));
        }
        for (Attachment att : this.attachments) {
            JsonObject file = JsonObject.create()
                .fluent("type", 3)
                .fluent("file", JsonObject.create()
                    .fluent("name", att.name())
                    .fluent("uri", att.url() != null ? att.url() : ""));
            attachments.add(file);
        }
        JsonObject message = JsonObject.create()
                .fluent("content", Json.toJson(content))
                .fluent("content_type", 2001)
                .fluent("attachments", attachments)
                .fluent("references", new JsonArray());

        if (actualSystemText != null && !actualSystemText.isEmpty()) {
            message.fluent("system", actualSystemText);
        }

        JsonArray messages = new JsonArray();
        List<ChatMessage> msgs = externalHistory != null ? externalHistory : history;
        for (ChatMessage msg : msgs) {
            JsonObject hm = JsonObject.create()
                    .fluent("content", Json.toJson(JsonObject.of("text", msg.getContent())))
                    .fluent("content_type", 2001)
                    .fluent("attachments", new JsonArray())
                    .fluent("references", new JsonArray());
            messages.add(hm);
        }
        messages.add(message);

        String localId = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");

        JsonObject body = JsonObject.create()
                .fluent("bot_id", DoubaoConstants.DEFAULT_BOT_ID)
                .fluent("messages", messages)
                .fluent("completion_option", completionOption)
                .fluent("evaluate_option", JsonObject.of("web_ab_params", ""))
                .fluent("local_conversation_id", localId)
                .fluent("local_message_id", localId)
                .fluentPut(conversationId != null && !conversationId.isEmpty(),
                        "conversation_id", conversationId);

return body.toJSONString();
    }

    @Override
    /**
     * GenerateImage
     * @param prompt prompt
     * @param ratio ratio
     * @param n n
     * @param width width
     * @param height height
     * @param quality quality
     * @param refImageKey refImageKey
     */
    public ImageGenerationResult generateImage(String prompt, String ratio, int n,
                                               int width, int height, String quality,
                                               String refImageKey) {
        return doGenerateImage(prompt, ratio, refImageKey);
    }

    @Override
    /** GenerateImage */
    public ImageGenerationResult generateImage(String prompt, String ratio) {
        return doGenerateImage(prompt, ratio, null);
    }

    /**
     * DoGenerateImage
     * @param prompt 提示词，不允许为 null
     * @param ratio 比率，不允许为 null
     * @param refImageKey refImage键，不允许为 null
     * @return ImageGeneration结果 对象
     */
    private ImageGenerationResult doGenerateImage(String prompt, String ratio, String refImageKey) {
        String baseUrl = setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()
                ? setting.getBaseUrl() : DEFAULT_BASE_URL;
        String url = baseUrl + "/samantha/chat/completion"
                + "?aid=" + DoubaoConstants.AID + "&device_platform=" + DoubaoConstants.DEVICE_PLATFORM;

        JsonObject contentObj = JsonObject.of("text", prompt);
        if (ratio != null && !ratio.isEmpty()) {
            contentObj.fluent("ratio", ratio);
        }

        JsonObject message = JsonObject.create()
                .fluent("content", Json.toJson(contentObj))
                .fluent("content_type", 2009)
                .fluent("attachments", new JsonArray())
                .fluent("references", new JsonArray())
                .fluent("skill", JsonObject.create()
                        .fluent("skill_type", 3)
                        .fluent("skill_type_no_default", 3)
                        .fluent("skill_id", "3")
                        .fluent("skill_id_no_default", "3"));

        if (refImageKey != null && !refImageKey.isEmpty()) {
            JsonArray refAttachments = new JsonArray();
            refAttachments.add(JsonObject.create()
                    .fluent("type", "image")
                    .fluent("key", refImageKey)
                    .fluent("extra", JsonObject.of("refer_types", "overall")));
        }

        String localId = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");

        JsonObject body = JsonObject.create()
                .fluent("messages", arrayOf(message))
                .fluent("completion_option", JsonObject.create()
                        .fluent("is_regen", false)
                        .fluent("with_suggest", true)
                        .fluent("need_create_conversation", true)
                        .fluent("launch_stage", 1)
                        .fluent("is_replace", false)
                        .fluent("is_delete", false)
                        .fluent("is_ai_playground", false)
                        .fluent("memory_type", 2)
                        .fluent("message_from", 0)
                        .fluent("use_deep_think", false)
                        .fluent("use_auto_cot", false)
                        .fluent("resend_for_regen", false)
                        .fluent("enable_commerce_credit", false)
                        .fluent("action_bar_skill_id", 3))
                .fluent("evaluate_option", JsonObject.of("web_ab_params", ""))
                .fluent("local_conversation_id", localId)
                .fluent("local_message_id", localId);

        DoubaoChatResult result = session.chat(url, body.toJSONString(), "", null);
        if (!result.isSuccess()) {
            throw new RuntimeException("图像生成失败: " + result.errorMessage());
        }

return parseImageResult(result, prompt);
    }

    @Override
    /**
     * GenerateVideo
     * @param prompt prompt
     * @param ratio ratio
     * @param cameraMovement cameraMovement
     * @param refImageKey refImageKey
     * @param timeoutSeconds timeoutSeconds
     */
    public VideoGenerationResult generateVideo(String prompt, String ratio,
                                               String cameraMovement, String refImageKey,
                                               int timeoutSeconds) {
        return doGenerateVideo(prompt, ratio, cameraMovement, refImageKey);
    }

    @Override
    /** GenerateVideo */
    public VideoGenerationResult generateVideo(String prompt, String ratio) {
        return doGenerateVideo(prompt, ratio, null, null);
    }

    /**
    * DoGenerateVideo
    * @param prompt prompt
    * @param ratio ratio
    * @param cameraMovement cameraMovement
    * @param refImageKey refImageKey
    */
    private VideoGenerationResult doGenerateVideo(String prompt, String ratio,
                                                   String cameraMovement, String refImageKey) {
        String baseUrl = setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()
                ? setting.getBaseUrl() : DEFAULT_BASE_URL;
        String url = baseUrl + "/samantha/chat/completion"
                + "?aid=" + DoubaoConstants.AID + "&device_platform=" + DoubaoConstants.DEVICE_PLATFORM;

        JsonObject contentObj = JsonObject.of("text", prompt);
        if (ratio != null && !ratio.isEmpty()) {
            contentObj.fluent("ratio", ratio);
        }
        if (cameraMovement != null && !cameraMovement.isEmpty()) {
            contentObj.fluent("camera_movement", cameraMovement);
        }

        JsonObject message = JsonObject.create()
                .fluent("content", Json.toJson(contentObj))
                .fluent("content_type", 2020)
                .fluent("attachments", new JsonArray())
                .fluent("references", new JsonArray())
                .fluent("skill", JsonObject.create()
                        .fluent("skill_type", 17)
                        .fluent("skill_type_no_default", 17)
                        .fluent("skill_id", "17")
                        .fluent("skill_id_no_default", "17"));

        if (refImageKey != null && !refImageKey.isEmpty()) {
            JsonArray refAttachments = new JsonArray();
            refAttachments.add(JsonObject.create().fluent("type", "image").fluent("key", refImageKey));
            message.fluent("attachments", refAttachments);
        }

        String localId = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");

        JsonObject body = JsonObject.create()
                .fluent("messages", arrayOf(message))
                .fluent("completion_option", JsonObject.create()
                        .fluent("is_regen", false)
                        .fluent("with_suggest", true)
                        .fluent("need_create_conversation", true)
                        .fluent("launch_stage", 1)
                        .fluent("is_replace", false)
                        .fluent("is_delete", false)
                        .fluent("is_ai_playground", false)
                        .fluent("memory_type", 2)
                        .fluent("message_from", 0)
                        .fluent("use_deep_think", false)
                        .fluent("use_auto_cot", false)
                        .fluent("resend_for_regen", false)
                        .fluent("enable_commerce_credit", false)
                        .fluent("action_bar_skill_id", 17))
                .fluent("evaluate_option", JsonObject.of("web_ab_params", ""))
                .fluent("local_conversation_id", localId)
                .fluent("local_message_id", localId);

        DoubaoChatResult result = session.chat(url, body.toJSONString(), "", null);
        if (!result.isSuccess()) {
            throw new RuntimeException("视频生成失败: " + result.errorMessage());
        }

        // 提取异步任务 ID
        String taskId = extractAsyncTaskId(result);
        if (taskId != null) {
            return pollAsyncVideo(taskId, prompt);
        }

        // 同步结果（罕见情况）
        return parseVideoResult(result, prompt);
    }

    /**
     * 从 SSE 事件中提取异步任务 ID。
     * @param result 结果，不允许为 null
     * @return 结果字符串
     */
    private String extractAsyncTaskId(DoubaoChatResult result) {
        List<Map<String, Object>> rawEvents = result.rawEvents();
        if (rawEvents == null) {
            return null;
        }
        for (Map<String, Object> event : rawEvents) {
            Object eventData = event.get("event_data");
            if (eventData instanceof String ed && !ed.isEmpty()) {
                try {
                    Map<String, Object> parsed = Json.fromJson(ed, Map.class);
                    if (parsed == null) {
                        continue;
                    }
                    Object finReason = parsed.get("fin_reason");
                    if (finReason instanceof Map<?, ?> fr) {
                        Object reason = fr.get("reason");
                        if (reason instanceof Number n && n.intValue() == 1) {
                            Object asyncTask = fr.get("async_task");
                            if (asyncTask instanceof Map<?, ?> at) {
                                Object tid = at.get("id");
                                if (tid instanceof String s && !s.isEmpty()) {
                                    return s;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 轮询异步视频生成结果。
     * @param taskId taskID，不允许为 null
     * @param prompt 提示词，不允许为 null
     * @return VideoGeneration结果 对象
     */
    private VideoGenerationResult pollAsyncVideo(String taskId, String prompt) {
        String baseUrl = setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()
                ? setting.getBaseUrl() : DEFAULT_BASE_URL;
        String url = baseUrl + "/samantha/chat/async/stream"
                + "?aid=" + DoubaoConstants.AID + "&device_platform=" + DoubaoConstants.DEVICE_PLATFORM;

        String body = JsonObject.create().fluent("task_id", taskId).fluent("event_id", 0).toJSONString();
        DoubaoChatResult result = session.chat(url, body, "", null);
        return parseVideoResult(result, prompt);
    }

    /**
     * 解析图像生成结果。
     * @param result 结果，不允许为 null
     * @param prompt 提示词，不允许为 null
     * @return ImageGeneration结果 对象
     */
    private ImageGenerationResult parseImageResult(DoubaoChatResult result, String prompt) {
        List<ImageGenerationResult.GeneratedImage> images = new ArrayList<>();
        List<Map<String, Object>> rawEvents = result.rawEvents();
        if (rawEvents != null) {
            for (Map<String, Object> event : rawEvents) {
                try {
                    Object eventData = event.get("event_data");
                    if (!(eventData instanceof String ed)) {
                        continue;
                    }
                    Map<String, Object> parsed = Json.fromJson(ed, Map.class);
                    if (parsed == null) {
                        continue;
                    }
                    Object msgObj = parsed.get("message");
                    if (!(msgObj instanceof Map<?, ?> msg)) {
                        continue;
                    }
                    Number ct = (Number) msg.get("content_type");
                    if (ct == null || ct.intValue() != 2010) {
                        continue;
                    }
                    Object contentStr = msg.get("content");
                    if (!(contentStr instanceof String cs)) {
                        continue;
                    }
                    Map<String, Object> content = Json.fromJson(cs, Map.class);
                    if (content == null) {
                        continue;
                    }
                    Object dataObj = content.get("data");
                    if (dataObj instanceof List<?> dataList) {
                        for (Object item : dataList) {
                            if (item instanceof Map<?, ?> img) {
                                Map<?, ?> ori = getMap(img, "image_ori");
                                Map<?, ?> thumb = getMap(img, "image_thumb");
                                Map<?, ?> raw = getMap(img, "image_raw");
                                images.add(new ImageGenerationResult.GeneratedImage(
                                        getStr(img, "key"),
                                        getStr(thumb, "url"),
                                        getStr(ori, "url"),
                                        getStr(raw, "url"),
                                        intVal(ori, "width", thumb, "width"),
                                        intVal(ori, "height", thumb, "height"),
                                        strVal(ori, "format", thumb, "format")
                                ));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return new ImageGenerationResult(images, prompt);
    }

    /**
     * 解析视频生成结果。
     * @param result 结果，不允许为 null
     * @param prompt 提示词，不允许为 null
     * @return VideoGeneration结果 对象
     */
    private VideoGenerationResult parseVideoResult(DoubaoChatResult result, String prompt) {
        List<VideoGenerationResult.GeneratedVideo> videos = new ArrayList<>();
        List<Map<String, Object>> rawEvents = result.rawEvents();
        if (rawEvents != null) {
            for (Map<String, Object> event : rawEvents) {
                try {
                    Object eventData = event.get("event_data");
                    if (!(eventData instanceof String ed)) {
                        continue;
                    }
                    Map<String, Object> parsed = Json.fromJson(ed, Map.class);
                    if (parsed == null) {
                        continue;
                    }
                    Object msgObj = parsed.get("message");
                    if (!(msgObj instanceof Map<?, ?> msg)) {
                        continue;
                    }
                    Number ct = (Number) msg.get("content_type");
                    if (ct == null || ct.intValue() != 2021) {
                        continue;
                    }
                    Object contentStr = msg.get("content");
                    if (!(contentStr instanceof String cs)) {
                        continue;
                    }
                    Map<String, Object> content = Json.fromJson(cs, Map.class);
                    if (content == null) {
                        continue;
                    }
                    Object dataObj = content.get("data");
                    List<?> dataList = dataObj instanceof List<?> dl ? dl : List.of(content);
                    for (Object item : dataList) {
                        if (item instanceof Map<?, ?> v) {
                            String videoUrl = getStr(v, "video_url", "url");
                            if (videoUrl == null || videoUrl.isEmpty()) {
                                videoUrl = extractVideoUrlFromModel(v);
                            }
                            String coverUrl = getStr(v, "cover_url");
                            if (coverUrl == null || coverUrl.isEmpty()) {
                                Map<?, ?> cover = getMap(v, "cover");
                                if (cover != null) {
                                    coverUrl = getStr(cover, "url");
                                }
                            }
                            if (videoUrl != null && !videoUrl.isEmpty()) {
                                videos.add(new VideoGenerationResult.GeneratedVideo(
                                        videoUrl, coverUrl,
                                        toInt(v, "width"), toInt(v, "height"),
                                        toDouble(v, "duration")
                                ));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return new VideoGenerationResult(videos, prompt);
    }

/**
 * ArrayOf
 * @param obj 对象，不允许为 null
 * @return Json数组 对象
 */
private static JsonArray arrayOf(JsonObject obj) {
        JsonArray arr = new JsonArray();
        arr.add(obj);
        return arr;
    }

    /**
     * 获取Str
     * @param map 映射，不允许为 null
     * @param keys 方法入参 keys
     * @return 结果字符串
     */
    private static String getStr(Map<?, ?> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /**
     * 获取映射。
     *
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private static Map<?, ?> getMap(Map<?, ?> map, String key) {
        Object val = map != null ? map.get(key) : null;
        return val instanceof Map<?, ?> m ? m : null;
    }

    /**
     * IntVal
     * @param first 首个，不允许为 null
     * @param firstKey 首个键，不允许为 null
     * @param second 方法入参 second
     * @param secondKey second键，不允许为 null
     * @return 结果数值
     */
    private static int intVal(Map<?, ?> first, String firstKey, Map<?, ?> second, String secondKey) {
        if (first != null) {
            Object v = first.get(firstKey);
            if (v instanceof Number n) {
                return n.intValue();
            }
        }
        if (second != null) {
            Object v = second.get(secondKey);
            if (v instanceof Number n) {
                return n.intValue();
            }
        }
        return 0;
    }

    /**
     * StrVal
     * @param first 首个，不允许为 null
     * @param firstKey 首个键，不允许为 null
     * @param second 方法入参 second
     * @param secondKey second键，不允许为 null
     * @return 结果字符串
     */
    private static String strVal(Map<?, ?> first, String firstKey, Map<?, ?> second, String secondKey) {
        if (first != null) {
            Object v = first.get(firstKey);
            if (v instanceof String s) {
                return s;
            }
        }
        if (second != null) {
            Object v = second.get(secondKey);
            if (v instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /**
     * ToInt
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private static int toInt(Map<?, ?> map, String key) {
        Object val = map != null ? map.get(key) : null;
        if (val instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    /**
     * ToDouble
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private static double toDouble(Map<?, ?> map, String key) {
        Object val = map != null ? map.get(key) : null;
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    /**
     * ExtractVideoUrlFromModel
     * @param item 项，不允许为 null
     * @return 结果字符串
     */
    private static String extractVideoUrlFromModel(Map<?, ?> item) {
        try {
            Object vmStr = item.get("video_model");
            if (vmStr instanceof String vs && !vs.isEmpty()) {
                Map<?, ?> vm = Json.fromJson(vs, Map.class);
                if (vm == null) {
                    return null;
                }
                Object vlist = vm.get("video_list");
                if (vlist instanceof Map<?, ?> vl) {
                    for (Object val : vl.values()) {
                        if (val instanceof Map<?, ?> vinfo) {
                            Object mainB64 = vinfo.get("main_url");
                            if (mainB64 instanceof String b64 && !b64.isEmpty()) {
                                return new String(java.util.Base64.getDecoder().decode(b64));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}


