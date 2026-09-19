package com.chua.kimi.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.ai.generation.ImageGenerationResult;
import com.chua.common.support.ai.generation.VideoGenerationResult;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kimi 网页版逆向代理对话客户端。
 *
 * <p>基于 Kimi Web 的 connect-rpc 协议直接调用，无需浏览器：
 * <ol>
 *   <li>appKey 为 access token（JWT）或 refresh token，refresh token 会按需自动换取</li>
 *   <li>请求体编码为 {@code 5 字节 connect 帧头 + JSON}，POST 到 {@code /apiv2/kimi.gateway.chat.v1.ChatService/Chat}</li>
 *   <li>响应为连续 gRPC 帧流，解析 delta 得到回答/思考内容</li>
 *   <li>通过 {@code chat_id} + {@code parent_id} 保持多轮上下文</li>
 * </ol>
 *
 * <p>SPI 名称：{@code kimi-proxy}，appKey 为 token 串。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("kimi-proxy")
@ConditionalOnClass("java.net.http.HttpClient")
public class KimiChatClient implements ChatClient {

    /**
     * 默认模型。
     */
    private static final String DEFAULT_MODEL = "kimi-k2.6";

    /**
     * 默认场景标识。
     */
    private static final String DEFAULT_SCENARIO = "SCENARIO_K2D5";

    /**
     * 思考阶段标识。
     */
    private static final String STAGE_NAME_THINKING = "STAGE_NAME_THINKING";

    /**
     * 会话客户端。
     */
    private final KimiSession session;

    /**
     * 远程 对话 标识（多轮上下文）。
     */
    private String remoteChatId;

    /**
     * 最后一条 assistant 消息 标识（多轮上下文）。
     */
    private String lastAssistantMessageId;

    /**
     * 临时会话 标识。
     */
    private String requestConversationId;

    /**
     * 客户端配置。
     */
    private final ChatClientSetting setting;

    /**
     * 当前模型。
     */
    private String model;

    /**
     * 当前温度。
     */
    private Double temperature;

    /**
     * 当前最大 令牌 数。
     */
    private Integer maxTokens;

    /**
     * 当前系统提示词。
     */
    private String system;

    /**
     * 会话字符串 标识。
     */
    private String conversationId;

    /**
     * 额外请求体参数。
     */
    private Map<String, Object> extraBody;

    /**
     * topp 参数。
     */
    private Double topP;

    /**
     * 停止 参数。
     */
    private List<String> stop;

    /**
     * seed 参数。
     */
    private Long seed;

    /**
     * 响应格式化 参数。
     */
    private String responseFormat;

    /**
     * 图片 URL 列表。
     */
    private final List<String> imageUrls = new ArrayList<>();

    /**
     * 附件列表。
     */
    private final List<Attachment> attachments = new ArrayList<>();

    /**
     * 工具列表。
     */
    private final List<ChatTool> tools = new ArrayList<>();

    /**
     * toolchoice 参数。
     */
    private String toolChoice;

    /**
     * 是否启用思考。
     */
    private boolean thinking;

    /**
     * 是否启用智能搜索。
     */
    private boolean smartSearch;

    /**
     * 技能管理器。
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
     * 构造 Kimi 逆向代理对话客户端。
     *
     * @param setting 客户端配置，其中 app键 为 令牌 串
     */
    public KimiChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel() != null ? setting.getModel() : DEFAULT_MODEL;
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        this.requestConversationId = UUID.randomUUID().toString();
        this.session = new KimiSession(setting.getAppKey(), setting.getBaseUrl());
    }

    @Override
    /**
     * 模型
    */
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /**
     * Temperature
    */
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    /**
     * 最大值令牌
    */
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    /**
     * 系统
    */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /**
     * extra主体
    */
    public ChatClient extraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody;
        return this;
    }

    @Override
    /**
     * Thinking
    */
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    @Override
    /**
     * Smart搜索
    */
    public ChatClient smartSearch(boolean smartSearch) {
        this.smartSearch = smartSearch;
        return this;
    }

    @Override
    /**
     * Skill
    */
    public ChatClient skill(SkillManager skillManager) {
        this.skillManager = skillManager;
        return this;
    }

    @Override
    /**
     * topp
    */
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    /**
     * 停止
    */
    public ChatClient stop(List<String> stop) {
        this.stop = stop;
        return this;
    }

    @Override
    /**
     * Seed
    */
    public ChatClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    /**
     * 响应格式化
    */
    public ChatClient responseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
        return this;
    }

    @Override
    /**
     * 添加镜像
    */
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    @Override
    /**
     * 添加Attachment
    */
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).data(data).mimeType(mimeType).build());
        return this;
    }

    @Override
    /**
     * 添加attachmenturl
    */
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).url(url).mimeType(mimeType).build());
        return this;
    }

    @Override
    /**
     * Tools
    */
    public ChatClient tools(List<ChatTool> tools) {
        this.tools.clear();
        if (tools != null) {
            this.tools.addAll(tools);
        }
        return this;
    }

    @Override
    /**
     * Tool
    */
    public ChatClient tool(ChatTool tool) {
        if (tool != null) {
            this.tools.add(tool);
        }
        return this;
    }

    @Override
    /**
     * toolchoice
    */
    public ChatClient toolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
        return this;
    }

    @Override
    /**
     * 添加用户历史
    */
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    @Override
    /**
     * 添加assistant历史
    */
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    @Override
    /**
     * 历史
    */
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    @Override
    /**
     * 会话
    */
    public ChatClient session(String sessionId) {
        this.conversationId = sessionId;
        return this;
    }

    @Override
    /**
     * 新对话
    */
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        this.conversationId = null;
        this.remoteChatId = null;
        this.lastAssistantMessageId = null;
        this.requestConversationId = UUID.randomUUID().toString();
        return this;
    }

    @Override
    /**
     * 对话同步
    */
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
    /**
     * 对话
    */
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        this.chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException(e);
        });
    }

    @Override
    /**
     * 对话
     * @param prompt 提示符
     * @param consumer consumer
     * @param onComplete on完成
     * @param onError on错误
     */
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder()
                .state(ChatResponse.State.START)
                .build());
        try {
            String actualModel = model != null ? model : DEFAULT_MODEL;

            List<ChatMessage> msgs = externalHistory != null ? externalHistory : history;
            String formatted = formatMessages(msgs, prompt, system);
            String scenario = DEFAULT_SCENARIO;

            JsonArray blocks = new JsonArray();
            blocks.add(new JsonObject()
                    .fluent("message_id", "")
                    .fluent("text", new JsonObject()
                            .fluent("content", formatted)));
            JsonObject message = new JsonObject()
                    .fluent("role", "user")
                    .fluent("blocks", blocks)
                    .fluent("scenario", scenario);
            if (lastAssistantMessageId != null) {
                message.fluent("parent_id", lastAssistantMessageId);
            }

            JsonArray toolsArray = new JsonArray();
            if (smartSearch) {
                toolsArray.add(new JsonObject()
                        .fluent("type", "TOOL_TYPE_SEARCH")
                        .fluent("search", new JsonObject()));
            }
            JsonObject payload = new JsonObject()
                    .fluent("scenario", scenario)
                    .fluent("tools", toolsArray)
                    .fluent("message", message)
                    .fluent("message", message)
                    .fluent("options", new JsonObject()
                            .fluent("thinking", thinking));
            if (remoteChatId != null) {
                payload.fluent("chat_id", remoteChatId);
            }

            byte[] body = KimiProtocol.encodeConnectRequest(payload);
            ClientResponse response = session.postChat(body);
            if (response.getStatusCode() != 200) {
                throw new RuntimeException("Kimi 对话失败，HTTP " + response.getStatusCode() + ": "
                        + new String(response.getBody(), StandardCharsets.UTF_8));
            }

            KimiChatResult result = parseFrames(response.getBody());

            if (result.errorMessage() != null) {
                throw new RuntimeException(result.errorMessage());
            }
            if (result.remoteChatId() != null) {
                this.remoteChatId = result.remoteChatId();
            }
            if (result.lastAssistantMessageId() != null) {
                this.lastAssistantMessageId = result.lastAssistantMessageId();
            }
            if (remoteChatId != null) {
                this.conversationId = remoteChatId;
            }

            String fullText = result.text();
            String thinkText = result.thinkingContent();

 // 先发送 Streaming 让 对话同步 能收集到内容
            if (fullText != null && !fullText.isEmpty()) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content(fullText)
                        .reasoningContent(thinkText)
                        .build());
            }

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .content(fullText)
                    .fullContent(fullText)
                    .reasoningContent(thinkText)
                    .usage(AiUsage.builder()
                            .provider("kimi-proxy")
                            .model(actualModel)
                            .startTime(startTime)
                            .durationMillis(System.currentTimeMillis() - startTime)
                            .build())
                    .build());
            onComplete.run();
        } catch (Exception e) {
            log.error("Kimi 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    @Override
    /**
     * generate镜像
     * @param prompt 提示符
     * @param ratio ratio
     * @param n n
     * @param width width
     * @param height height
     * @param quality quality
     * @param refImageKey ref镜像键
     */
    public ImageGenerationResult generateImage(String prompt, String ratio, int n,
                                               int width, int height, String quality,
                                               String refImageKey) {
        String fullPrompt = prompt;
        if (ratio != null) {
            fullPrompt = "生成图片，比例" + ratio + "：" + prompt;
        } else {
            fullPrompt = "生成图片：" + prompt;
        }
        String answer = chatSync(fullPrompt);
        List<ImageGenerationResult.GeneratedImage> images = extractImagesFromText(answer, prompt);
        return new ImageGenerationResult(images, prompt);
    }

    @Override
    /**
     * generate视频
     * @param prompt 提示符
     * @param ratio ratio
     * @param cameraMovement 摄像头移动
     * @param refImageKey ref镜像键
     * @param timeoutSeconds 超时seconds
     */
    public VideoGenerationResult generateVideo(String prompt, String ratio,
                                               String cameraMovement, String refImageKey,
                                               int timeoutSeconds) {
        String fullPrompt = "生成视频：" + prompt;
        if (ratio != null) {
            fullPrompt = "生成视频，比例" + ratio + "：" + prompt;
        }
        String answer = chatSync(fullPrompt);
        List<VideoGenerationResult.GeneratedVideo> videos = new ArrayList<>();
        if (answer != null) {
            Matcher matcher = Pattern.compile(
                    "https?://[^\\s)\"'<>]+(?:\\.(?:mp4|webm|ogg))(?:\\?[^\\s)\"'<>]*)?"
            ).matcher(answer);
            while (matcher.find()) {
                videos.add(new VideoGenerationResult.GeneratedVideo(matcher.group(), "", 0, 0, 0));
            }
        }
        return new VideoGenerationResult(videos, prompt);
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        session.close();
    }

    /**
     * 解析 gRPC 帧流，提取回答/思考内容与上下文。
     *
     * @param data 完整响应字节
     * @return 解析结果
     */
    private KimiChatResult parseFrames(byte[] data) {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String chatId = null;
        String assistantId = null;
        String errorMessage = null;
        int offset = 0;
        while (offset + KimiProtocol.FRAME_HEADER_LENGTH <= data.length) {
            int flag = data[offset] & 0xFF;
            int length = ((data[offset + 1] & 0xFF) << 24)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 8)
                    | (data[offset + 4] & 0xFF);
            int frameEnd = offset + KimiProtocol.FRAME_HEADER_LENGTH + length;
            if (frameEnd > data.length) {
                break;
            }
            if ((flag & 0x80) == 0) {
                String payload = new String(data, offset + KimiProtocol.FRAME_HEADER_LENGTH,
                        length, StandardCharsets.UTF_8).trim();
                if (!payload.isEmpty()) {
try {
                        JsonObject event = JsonObject.parse(payload);
                        if (event.getObject("chat") instanceof Map<?, ?> chat) {
                        Object chatIdObj = chat.get("id");
                        if (chatIdObj != null) {
                            chatId = chatIdObj.toString();
                        }
                    }
                    Object msgObj = event.getObject("message");
                    if (msgObj instanceof Map<?, ?> msg) {
                        if ("assistant".equals(msg.get("role")) && msg.get("id") != null) {
                            assistantId = msg.get("id").toString();
                        }
                    }
                    if (event.getObject("error") != null) {
                            errorMessage = event.getObject("error").toString();
                        }
                        String phase = extractPhase(event);
                        String content = extractContent(event, phase);
                        if (content == null) {
                            content = extractThink(event);
                        }
                        if (content != null && !content.isEmpty()) {
                            if ("thinking".equals(phase) && thinking.length() == 0) {
                                thinking.append(content);
                            } else if (!"thinking".equals(phase)) {
                                text.append(content);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            offset = frameEnd;
        }
        return new KimiChatResult(text.toString(), thinking.toString(), chatId, assistantId, errorMessage);
    }

    /**
     * 从事件中提取阶段标识。
     *
     * @param event 事件 JSON
     * @return thinking / answer / 空
     */
    private static String extractPhase(JsonObject event) {
        JsonObject block = event.getJsonObject("block");
        Object stagesObj = block.getObject("multiStage");
        if (stagesObj instanceof JsonObject multiStage) {
            Object stagesList = multiStage.getObject("stages");
            if (stagesList instanceof JsonArray stages && !stages.isEmpty()) {
                Object first = stages.get(0); // [P3C 四十一 豁免] <原因: JsonArray 元素下标访问，非 java.util.List>
                if (first instanceof JsonObject stage) {
                    boolean thinkingStage = STAGE_NAME_THINKING.equals(stage.getObject("name"));
                    boolean completed = "completed".equals(stage.getObject("status"));
                    if (thinkingStage) {
                        return completed ? "answer" : "thinking";
                    }
                }
            }
        }
        Object flags = block.getObject("flags");
        if ("thinking".equals(flags)) {
            return "thinking";
        }
        if ("answer".equals(flags)) {
            return "answer";
        }
        return null;
    }

    /**
     * 从事件中提取文本内容。
     *
     * @param event 事件 JSON
     * @param phase 阶段
     * @return 内容，无则返回 空
     */
    private static String extractContent(JsonObject event, String phase) {
        JsonObject block = event.getJsonObject("block");
        JsonObject textObj = block.getJsonObject("text");
        Object content = textObj.getObject("content");
        if (content == null) {
            return null;
        }
        if ("thinking".equals(phase)) {
            return null;
        }
        return content.toString();
    }

    /**
     * 从 think 块提取思考内容。
     *
     * @param event 事件 JSON
     * @return 思考内容
     */
    private static String extractThink(JsonObject event) {
        JsonObject block = event.getJsonObject("block");
        Object thinkObj = block.getObject("think");
        if (thinkObj instanceof JsonObject think) {
            Object content = think.getObject("content");
            return content == null ? null : content.toString();
        }
        return null;
    }

    /**
     * 格式化消息为 Kimi 文本协议（系统: / 角色: 逐行）。
     *
     * @param msgs   历史消息
     * @param prompt 当前问题
     * @param system 系统提示词
     * @return 组装后的文本
     */
    private String formatMessages(List<ChatMessage> msgs, String prompt, String system) {
        StringBuilder body = new StringBuilder();
        if (system != null && !system.isBlank()) {
            body.append("system:").append(system);
        }
        for (ChatMessage msg : msgs) {
            String role = msg.getRole();
            String content = msg.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("system".equals(role)) {
                if (body.length() > 0) {
                    body.append("\n");
                }
                body.append("system:").append(content);
                continue;
            }
            if ("assistant".equals(role) || "user".equals(role)) {
                if (body.length() > 0) {
                    body.append("\n");
                }
                body.append(role).append(":").append(content);
            }
        }
        if (body.length() > 0) {
            body.append("\n");
        }
        body.append("user:").append(prompt);
        return body.toString();
    }

    /**
     * 从回答文本中提取图片 URL。
     *
     * @param text   回答文本
     * @param prompt 提示词
     * @return 图片列表
     */
    private static List<ImageGenerationResult.GeneratedImage> extractImagesFromText(String text, String prompt) {
        List<ImageGenerationResult.GeneratedImage> images = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return images;
        }
        Matcher matcher = Pattern.compile(
                "!\\[.*?\\]\\((https?://[^)]+)\\)|https?://[^\\s)\"'<>]+(?:\\.(?:png|jpg|jpeg|webp|gif))(?:\\?[^\\s)\"'<>]*)?"
        ).matcher(text);
        int idx = 0;
        while (matcher.find() && idx < 10) {
            String url = matcher.group(1) != null ? matcher.group(1) : matcher.group(0);
            images.add(new ImageGenerationResult.GeneratedImage("", url, url, "", 0, 0, ""));
            idx++;
        }
        return images;
    }
}
