package com.chua.google.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.ResponseStream;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Google Gemini 大模型对话客户端
 *
 * <p>基于 Google Gemini API 的 {@link ChatClient} 实现，通过 Google Gen AI SDK
 * 调用 Gemini 系列模型的对话接口，支持 Gemini 1.5 Pro/Flash 等模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"google", "gemini"})
public class GoogleChatClient implements ChatClient {

    /**
     * Gemini 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta";

    /**
     * Google Gen AI 客户端
     */
    private final Client client;

    /**
     * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
     * 当前使用的模型名称
     */
    private String model;

    /**
     * 当前温度参数
     */
    private Double temperature;

    /**
     * 当前最大 Token 数
     */
    private Integer maxTokens;

    /**
     * 当前系统提示词
     */
    private String system;

    /**
     * 当前会话 ID
     */
    private String sessionId;

    /**
     * 对话历史消息列表
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 外部传入的完整历史记录
     */
    private List<ChatMessage> externalHistory;

    /**
     * 图片附件 URL 列表
     */
    private final List<String> imageUrls = new ArrayList<>();

    /**
     * 是否启用深度思考
     */
    private boolean thinking;

    /**
     * 深度思考力度
     */
    private String thinkingEffort;

    /**
     * 是否启用智能搜索
     */
    private boolean smartSearch;

    /**
     * 技能管理器
     */
    private SkillManager skillManager;

    /**
     * 构造 Google Gemini 对话客户端
     *
     * @param setting 客户端配置
     */
    public GoogleChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        applyProxy(setting.getProxy());
        this.client = Client.builder()
                .apiKey(setting.getAppKey())
                .build();
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
    /** 添加Image */
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
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
        history.add(ChatMessage.builder().role("model").content(content).build());
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
        this.sessionId = sessionId;
        return this;
    }

    @Override
    /** 添加Attachment */
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持文件附件");
    }

    @Override
    /** 添加AttachmentUrl */
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持远程文件附件");
    }

    @Override
    /** NewChat */
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    /** ChatSync */
    public String chatSync(String prompt) {
        StringBuilder result = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        chat(prompt, response -> {
            if (response.getState() == ChatResponse.State.STREAMING) {
                if (response.getContent() != null) {
                    result.append(response.getContent());
                }
                if (response.getReasoningContent() != null) {
                    reasoning.append(response.getReasoningContent());
                }
            }
        });
        if (reasoning.length() > 0) {
            return reasoning.toString() + "\n---\n" + result.toString();
        }
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
        String actualModel = model != null ? model : "gemini-1.5-pro";
        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(system, skillManager);
        }

        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder()
                .state(ChatResponse.State.START)
                .build());

        try {
            GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();
            if (actualSystem != null && !actualSystem.isEmpty()) {
                configBuilder.systemInstruction(Content.builder().parts(List.of(Part.builder().text(actualSystem).build())).build());
            }
            configBuilder.temperature(temperature != null ? temperature.floatValue() : 0.3f);
            configBuilder.maxOutputTokens(maxTokens != null ? maxTokens : 2048);

            if (thinking) {
                int budget = "low".equals(thinkingEffort) ? 1 : "medium".equals(thinkingEffort) ? 2 : 3;
                configBuilder.thinkingConfig(
                        com.google.genai.types.ThinkingConfig.builder()
                                .thinkingBudget(budget)
                                .build());
            }
            if (smartSearch) {
                configBuilder.tools(List.of(
                        com.google.genai.types.Tool.builder()
                                .googleSearch(com.google.genai.types.GoogleSearch.builder().build())
                                .build()));
            }
            GenerateContentConfig config = configBuilder.build();

            List<Content> contents = new ArrayList<>();
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                contents.add(Content.builder()
                        .role(msg.getRole())
                        .parts(List.of(Part.builder().text(msg.getContent()).build()))
                        .build());
            }
            contents.add(Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(prompt).build()))
                    .build());

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(actualModel)
                    .provider("google")
                    .startTime(startTime);

            StringBuilder fullContent = new StringBuilder();
            StringBuilder reasoningContent = new StringBuilder();
            boolean first = true;

            ResponseStream<GenerateContentResponse> stream = client.models.generateContentStream(
                    actualModel, contents, config);

            for (GenerateContentResponse chunk : stream) {
                if (first) {
                    usageBuilder.firstTokenLatencyMillis(System.currentTimeMillis() - startTime);
                    first = false;
                }

                String chunkText = null;
                String chunkReasoning = null;
                if (chunk.candidates().isPresent()) {
                    var candidate = chunk.candidates().get().get(0);
                    if (candidate.content().isPresent() && candidate.content().get().parts().isPresent()) {
                        for (Part part : candidate.content().get().parts().get()) {
                            String text = part.text().orElse(null);
                            if (text == null) {
                                continue;
                            }
                            if (Boolean.TRUE.equals(part.thought().orElse(false))) {
                                reasoningContent.append(text);
                                chunkReasoning = text;
                            } else {
                                fullContent.append(text);
                                chunkText = text;
                            }
                        }
                    }
                }

                if (chunkText != null || chunkReasoning != null) {
                    consumer.accept(ChatResponse.builder()
                            .state(ChatResponse.State.STREAMING)
                            .content(chunkText)
                            .reasoningContent(chunkReasoning)
                            .build());
                }
            }

            usageBuilder.durationMillis(System.currentTimeMillis() - startTime);
            String reasoningText = reasoningContent.length() > 0 ? reasoningContent.toString() : null;
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .content(reasoningText != null
                            ? reasoningText + "\n---\n" + fullContent.toString()
                            : fullContent.toString())
                    .fullContent(fullContent.toString())
                    .reasoningContent(reasoningText)
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();

        } catch (Exception e) {
            log.error("Google Gemini 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 通过系统属性配置 HTTP 代理
     *
     * @param proxyStr 代理地址，支持 http://、socks5:// 格式，可为空
     */
    private static void applyProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return;
        }
        String host;
        int port;
        boolean isSocks;

        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            isSocks = true;
            String hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
            String[] parts = hostPort.split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 1080;
        } else {
            isSocks = false;
            String tmp = proxyStr;
            if (tmp.startsWith("https://")) {
                tmp = tmp.substring(8);
            } else if (tmp.startsWith("http://")) {
                tmp = tmp.substring(7);
            }
            String[] parts = tmp.split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        }

        if (isSocks) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", String.valueOf(port));
        } else {
            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", String.valueOf(port));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", String.valueOf(port));
        }
    }

}