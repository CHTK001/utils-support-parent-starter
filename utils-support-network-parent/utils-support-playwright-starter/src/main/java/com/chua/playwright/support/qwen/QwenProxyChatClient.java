package com.chua.playwright.support.qwen;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
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
 * 原生 fetch 请求，借助阿里云前端 JS 自动注入 {@code ssxmod_itna} 指纹，
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
     * 额外请求体参数。
     */
    private Map<String, Object> extraBody;

    /**
     * 是否启用深度思考。
     */
    private boolean thinking;

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
     * 构造通义千问逆向代理对话客户端。
     *
     * @param setting 客户端配置，其中 appKey 为 Cookie 串
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
    public ChatClient model(String model) {
        this.model = model;
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
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    public ChatClient extraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody;
        return this;
    }

    @Override
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    @Override
    public ChatClient smartSearch(boolean smartSearch) {
        this.smartSearch = smartSearch;
        return this;
    }

    @Override
    public ChatClient skill(SkillManager skillManager) {
        this.skillManager = skillManager;
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
    public ChatClient session(String sessionId) {
        this.conversationId = sessionId;
        return this;
    }

    @Override
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        this.conversationId = null;
        return this;
    }

    @Override
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
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException(e);
        });
    }

    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder()
                .state(ChatResponse.State.START)
                .build());

        try {
            String actualModel = model != null ? model : "qwen-plus";
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