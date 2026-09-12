package com.chua.playwright.support.qwen;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.generation.ImageGenerationResult;
import com.chua.common.support.ai.generation.VideoGenerationResult;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
* 通义千问逆向代理对话客户端。
*
* <p>基于 {@link QwenBrowserSession} 在 Playwright 浏览器页面内发起
* 原生 获取 请求，借助阿里云前端 JS 自动注入 {@code ssxmod_itna} 指纹，
* 实现 Cookie 认证的通义千问免费对话。
*
* <p>SPI 名称：{@code qwen-proxy}，appKey 为 Cookie 串
* （{@code token=xxx; ssxmod_itna=xxx}）。
*
* <p>用法：
* <pre>{@code
* ChatClient client = ChatClient.create("qwen-proxy",
*     "token=xxx; ssxmod_itna=xxx");
* String answer = client.model("qwen-plus").chatSync("你好");
* }</pre>-plus").chatSync("你好");
* }</pre>
*
* @author CH
* @since 2026/08/12
 */
@Slf4j
@Spi("qwen-proxy")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class QwenProxyChatClient implements ChatClient {

    /**
    * 默认通义千问基础地址。
     */
    private static final String DEFAULT_BASE_URL = "https://chat.qwen.ai";

    /**
    * 浏览器会话。
     */
    private final QwenBrowserSession session;

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
    * 当前最大 令牌 数。
     */
    private Integer maxTokens;

    /**
    * 当前系统提示词。
     */
    private String system;

    /**
    * 当前会话 标识。
     */
    private String conversationId;

    /**
    * 额外请求体参数。
     */
    private Map<String, Object> extraBody;

    /**
    * top P
     */
    private Double topP;
    /**
    * 停止
     */
    private List<String> stop;
    /**
    * seed
     */
    private Long seed;
    /**
    * 响应 格式化
     */
    private String responseFormat;
    /**
    * 镜像 Urls
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
    * 是否启用智能搜索。
     */
    private boolean smartSearch;

    /**
    * 技能管理器（用于 提示符 注入）。
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
    * 构造通义千问逆向代理对话客户端。
    *
    * @param setting 客户端配置，其中 app键 为 Cookie 串
     */
    public QwenProxyChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        this.session = new QwenBrowserSession(setting.getAppKey(), null);
        this.session.init();
    }

    @Override
    /** 模型 */
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
    /** 最大值令牌 */
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    /** 系统 */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /** extra主体 */
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
    /** topp */
    public ChatClient topP(Double topP) { this.topP = topP; return this; }

    @Override
    /** 停止 */
    public ChatClient stop(List<String> stop) { this.stop = stop; return this; }

    @Override
    /** Seed */
    public ChatClient seed(Long seed) { this.seed = seed; return this; }

    @Override
    /** 响应格式化 */
    public ChatClient responseFormat(String responseFormat) { this.responseFormat = responseFormat; return this; }

    @Override
    /** 添加镜像 */
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
    /** 添加attachmenturl */
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
    /** toolchoice */
    public ChatClient toolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
        return this;
    }

    @Override
    /** 添加用户历史 */
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    @Override
    /** 添加assistant历史 */
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    @Override
    /** 历史 */
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    @Override
    /** 会话 */
    public ChatClient session(String sessionId) {
        this.conversationId = sessionId;
        return this;
    }

    @Override
    /** 新对话 */
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        this.conversationId = null;
        this.imageUrls.clear();
        this.attachments.clear();
        this.tools.clear();
        session.newChat();
        return this;
    }

    @Override
    /** 对话同步 */
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
    /** 对话 */
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
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
            String actualModel = model != null ? model : "qwen3.8-max";

            List<ChatMessage> msgs = externalHistory != null ? externalHistory : history;
            if (!msgs.isEmpty()) {
                StringBuilder context = new StringBuilder();
                for (ChatMessage msg : msgs) {
                    context.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
                }
                context.append("user: ").append(prompt);
                prompt = context.toString();
            }

            String body = buildRequestBody(prompt, actualModel);

            QwenChatResult result = session.chat(body, actualModel, (type, content) -> {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content("text".equals(type) ? content : null)
                        .reasoningContent("thinking".equals(type) ? content : null)
                        .build());
            });

            if (result.isSuccess()) {
                if (result.conversationId() != null && !result.conversationId().isEmpty()) {
                    this.conversationId = result.conversationId();
                }
                String fullText = result.text();
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(fullText)
                        .fullContent(fullText)
                        .reasoningContent(result.thinkingContent())
                        .usage(AiUsage.builder()
                                .model(actualModel)
                                .provider("qwen-proxy")
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
            log.error("通义千问对话请求失败: {}", e.getMessage(), e);
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
            fullPrompt = "生成图片：" + prompt + " 比例 " + ratio;
        } else {
            fullPrompt = "生成图片：" + prompt;
        }

        QwenChatResult result = session.chat("{\"messages\":[{\"role\":\"user\",\"content\":\"" + fullPrompt + "\"}]}",
                "qwen3.8-max", null);
        if (!result.isSuccess()) {
            throw new RuntimeException("Qwen 图像生成失败: " + result.errorMessage());
        }

 // 从 raw事件 中提取 镜像_列表
        List<ImageGenerationResult.GeneratedImage> images = new ArrayList<>();
        List<Map<String, Object>> rawEvents = result.rawEvents();
        if (rawEvents != null) {
            for (Map<String, Object> event : rawEvents) {
                try {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) event.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                    if (delta == null) {
                        continue;
                    }
                    Map<String, Object> extra = (Map<String, Object>) delta.get("extra");
                    if (extra == null) {
                        continue;
                    }
                    List<Map<String, Object>> imageList = (List<Map<String, Object>>) extra.get("image_list");
                    if (imageList == null) {
                        continue;
                    }
                    for (Map<String, Object> img : imageList) {
                        String url = (String) img.get("image");
                        if (url != null && !url.isEmpty()) {
                            images.add(new ImageGenerationResult.GeneratedImage("", url, url, "", 0, 0, ""));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // 也尝试从文本中提取 markdown 图片
        if (images.isEmpty() && result.text() != null) {
            images.addAll(extractImagesFromText(result.text(), prompt));
        }

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
            fullPrompt += " 比例 " + ratio;
        }
        if (cameraMovement != null) {
            fullPrompt += " 运镜 " + cameraMovement;
        }

        QwenChatResult result = session.chat("{\"messages\":[{\"role\":\"user\",\"content\":\"" + fullPrompt + "\"}]}",
                "qwen3.8-max", null);
        List<VideoGenerationResult.GeneratedVideo> videos = new ArrayList<>();
        if (result.isSuccess() && result.text() != null) {
            // 从文本中提取视频 URL
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "https?://[^\\s)\"'<>]+(?:\\.(?:mp4|webm|ogg))(?:\\?[^\\s)\"'<>]*)?"
            ).matcher(result.text());
            while (m.find()) {
                videos.add(new VideoGenerationResult.GeneratedVideo(m.group(), "", 0, 0, 0));
            }
        }
        return new VideoGenerationResult(videos, prompt);
    }

    /**
    * 从回答文本中提取图片 URL。
    * @param text 文本
    * @param prompt 提示符
    * @return extract镜像从文本的结果
     */
    private static List<ImageGenerationResult.GeneratedImage> extractImagesFromText(String text, String prompt) {
        List<ImageGenerationResult.GeneratedImage> images = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return images;
        }
        // 匹配 Markdown 图片 ![alt](url) 或直接 URL
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "!\\[.*?\\]\\((https?://[^)]+)\\)|https?://[^\\s)\"'<>]+(?:\\.(?:png|jpg|jpeg|webp|gif))(?:\\?[^\\s)\"'<>]*)?"
        ).matcher(text);
        int idx = 0;
        while (m.find() && idx < 10) {
            String url = m.group(1) != null ? m.group(1) : m.group(0);
            images.add(new ImageGenerationResult.GeneratedImage("", url, url, "", 0, 0, ""));
            idx++;
        }
        return images;
    }

    @Override
    /** 关闭 */
    public void close() {
        session.close();
    }

    /**
    * 构建通义千问请求体。
    *
    * @param prompt   用户输入
    * @param modelName 模型名称
    * @return JSON 请求体字符串
     */
    private String buildRequestBody(String prompt, String modelName) {
        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(actualSystem, skillManager);
        }

        JsonArray messages = new JsonArray();
        List<ChatMessage> msgs = externalHistory != null ? externalHistory : history;
        for (ChatMessage msg : msgs) {
            JsonObject m = JsonObject.create()
                    .fluent("role", msg.getRole())
                    .fluent("content", msg.getContent())
                    .fluent("chat_type", "t2t")
                    .fluent("extra", JsonObject.create())
                    .fluent("feature_config", JsonObject.create()
                            .fluent("output_schema", "phase")
                            .fluent("thinking_enabled", thinking));
            messages.add(m);
        }

        // 用户当前消息
        JsonObject userMsg = JsonObject.create()
                .fluent("role", "user")
                .fluent("content", prompt)
                .fluent("chat_type", "t2t")
                .fluent("extra", JsonObject.create())
                .fluent("feature_config", JsonObject.create()
                        .fluent("output_schema", "phase")
                        .fluent("thinking_enabled", thinking));
        messages.add(userMsg);

        JsonObject body = JsonObject.create()
                .fluent("messages", messages)
                .fluent("model", modelName)
                .fluent("stream", true)
                .fluent("chat_id", conversationId != null ? conversationId : "")
                .fluent("chatId", conversationId != null ? conversationId : "")
                .fluent("parent_id", null)
                .fluent("parentId", null);

        if (actualSystem != null && !actualSystem.isEmpty()) {
            body.fluent("system_info", actualSystem);
        }

        return body.toJSONString();
    }
}
