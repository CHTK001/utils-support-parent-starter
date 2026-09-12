package com.chua.doubao.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 豆包/火山引擎大模型对话客户端
 *
 * <p>基于 Ark SDK 的 {@link ChatClient} 实现，调用豆包系列模型的对话接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"doubao", "volcengine"})
public class DoubaoChatClient implements ChatClient {

    /** 默认 API 地址 */
    private static final String DEFAULT_URL = "https://ark.cn-beijing.volces.com/api/v3";

    /** 火山引擎 Ark 服务客户端 */
    private final ArkService arkService;
    /** 配置对象 */
    private final ChatClientSetting setting;
    /** 模型名称 */
    private String model;
    /** 温度参数 */
    private Double temperature;
    /** 最大令牌数 */
    private Integer maxTokens;
    /** 系统提示 */
    private String system;
    /** 会话 标识 */
    private String sessionId;
    /** 对话历史消息 */
    private final List<ChatMessage> history = new ArrayList<>();
    /** 外部历史消息 */
    private List<ChatMessage> externalHistory;
    /** 是否开启思考模式 */
    private boolean thinking;
    /** 思考努力程度 */
    private String thinkingEffort;
    /** 是否启用智能搜索 */
    private boolean smartSearch;
    /** 技能管理器 */
    private SkillManager skillManager;

    /**
      * 创建 doubao对话客户端 实例
     * @param setting setting
     */
    public DoubaoChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();

        ArkService.Builder builder = ArkService.builder()
                .apiKey(setting.getAppKey())
                .timeout(Duration.ofSeconds(90))
                .connectTimeout(Duration.ofSeconds(30));
        if (setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            builder.baseUrl(setting.getBaseUrl());
        }
        String proxyStr = setting.getProxy();
        if (proxyStr != null && !proxyStr.isBlank()) {
            java.net.Proxy proxy = toProxy(proxyStr);
            if (proxy != null) {
                builder.proxy(proxy);
            }
        }
        this.arkService = builder.build();
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
    /** Thinking */
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    @Override
    /** thinkingeffort */
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
        this.sessionId = sessionId;
        return this;
    }

    @Override
    /** 新对话 */
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    /** 对话同步 */
    public String chatSync(String prompt) {
        StringBuilder result = new StringBuilder();
        chat(prompt, response -> {
            if (response.getState() == ChatResponse.State.STREAMING && response.getContent() != null) {
                result.append(response.getContent());
            }
        });
        return result.toString();
    }

    @Override
    /** 对话 */
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {}, e -> { throw new RuntimeException(e); });
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
        consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());

        try {
            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }

            List<com.volcengine.ark.runtime.model.completion.chat.ChatMessage> messages = new ArrayList<>();
            if (actualSystem != null && !actualSystem.isEmpty()) {
                messages.add(com.volcengine.ark.runtime.model.completion.chat.ChatMessage.builder()
                        .role(ChatMessageRole.SYSTEM)
                        .content(actualSystem)
                        .build());
            }
            List<ChatMessage> msgs = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : msgs) {
                messages.add(com.volcengine.ark.runtime.model.completion.chat.ChatMessage.builder()
                        .role("user".equals(msg.getRole()) ? ChatMessageRole.USER : ChatMessageRole.ASSISTANT)
                        .content(msg.getContent())
                        .build());
            }
            messages.add(com.volcengine.ark.runtime.model.completion.chat.ChatMessage.builder()
                    .role(ChatMessageRole.USER)
                    .content(prompt)
                    .build());

            ChatCompletionRequest.Builder requestBuilder = ChatCompletionRequest.builder()
                    .model(model != null ? model : "doubao-1.5-pro-32k")
                    .messages(messages)
                    .temperature(temperature != null ? temperature : 0.3)
                    .maxTokens(maxTokens != null ? maxTokens : 2048);

            if (thinking) {
                requestBuilder.thinking(new com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest.ChatCompletionRequestThinking("enabled"));
            }
            if (smartSearch) {
                requestBuilder.tools(List.of(new com.volcengine.ark.runtime.model.completion.chat.ChatTool("web_search", null)));
            }

            Flowable<ChatCompletionChunk> flowable = arkService.streamChatCompletion(requestBuilder.build());
            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "doubao-1.5-pro-32k")
                    .provider("doubao")
                    .startTime(startTime);

            AtomicBoolean isDone = new AtomicBoolean(false);
            flowable.blockingForEach(chunk -> {
                if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                    var choice = chunk.getChoices().get(0);
                    String finishReason = choice.getFinishReason();
                    if ("stop".equals(finishReason)) {
                        isDone.set(true);
                        return;
                    }
                    var delta = choice.getMessage();
                    if (delta != null) {
                        Object contentObj = delta.getContent();
                        if (contentObj instanceof String content && !content.isEmpty()) {
                            consumer.accept(ChatResponse.builder()
                                    .state(ChatResponse.State.STREAMING)
                                    .content(content)
                                    .build());
                        }
                        String reasoning = delta.getReasoningContent();
                        if (reasoning != null && !reasoning.isEmpty()) {
                            consumer.accept(ChatResponse.builder()
                                    .state(ChatResponse.State.STREAMING)
                                    .reasoningContent(reasoning)
                                    .build());
                        }
                    }
                }
                if (chunk.getUsage() != null) {
                    var usage = chunk.getUsage();
                    usageBuilder.inputTokens((int) usage.getPromptTokens())
                            .outputTokens((int) usage.getCompletionTokens())
                            .totalTokens((int) usage.getTotalTokens());
                }
            });

            usageBuilder.durationMillis(System.currentTimeMillis() - startTime);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();

        } catch (Exception e) {
            log.error("豆包对话请求失败: {}", e.getMessage(), e);
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
        try {
            arkService.shutdownExecutor();
        } catch (Exception e) {
            log.debug("关闭 ArkService 失败", e);
        }
    }

    /**
     * 转为代理
     *
     * @param proxyStr 代理str
     * @return 转为代理的结果
     */
    private static java.net.Proxy toProxy(String proxyStr) {
        try {
            java.net.Proxy.Type proxyType;
            String hostPort;
            if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
                proxyType = java.net.Proxy.Type.SOCKS;
                hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
            } else if (proxyStr.startsWith("http://")) {
                proxyType = java.net.Proxy.Type.HTTP;
                hostPort = proxyStr.substring(7);
            } else if (proxyStr.startsWith("https://")) {
                proxyType = java.net.Proxy.Type.HTTP;
                hostPort = proxyStr.substring(8);
            } else {
                proxyType = java.net.Proxy.Type.HTTP;
                hostPort = proxyStr;
            }
            String[] parts = hostPort.split(":");
            return new java.net.Proxy(proxyType, new InetSocketAddress(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 80));
        } catch (Exception e) {
            return null;
        }
    }
}