package com.chua.alibaba.support;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 阿里云通义千问大模型对话客户端
 *
 * <p>基于 DashScope SDK 的 {@link ChatClient} 实现，通过 Generation API
 * 调用阿里云模型服务灵积（DashScope）的对话接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"alibaba"})
public class AlibabaChatClient implements ChatClient {

    private static final String DEFAULT_MODEL = "qwen-turbo";
    private static final double DEFAULT_TEMPERATURE = 0.3;
    private static final int DEFAULT_MAX_TOKENS = 2048;
    private static final int HISTORY_CAPACITY = 16;

    private final Generation generation;
    private final ChatClientSetting setting;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String system;
    private String sessionId;
    private final List<ChatMessage> history = new ArrayList<>(HISTORY_CAPACITY);
    private List<ChatMessage> externalHistory;
    private final List<String> imageUrls = new ArrayList<>(4);
    private boolean thinking;
    private String thinkingEffort;
    private boolean smartSearch;
    private SkillManager skillManager;

    public AlibabaChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        this.generation = buildGeneration(setting);
    }

    private static Generation buildGeneration(ChatClientSetting setting) {
        var proxyStr = setting.getProxy();
        if (proxyStr == null || proxyStr.isBlank()) {
            return new Generation(setting.getAppKey());
        }
        var connOpts = buildConnectionOptions(proxyStr);
        return new Generation(setting.getAppKey(), null, connOpts);
    }

    private static ConnectionOptions buildConnectionOptions(String proxyStr) {
        String hostPort;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
        } else if (proxyStr.startsWith("http://")) {
            hostPort = proxyStr.substring(7);
        } else if (proxyStr.startsWith("https://")) {
            hostPort = proxyStr.substring(8);
        } else {
            hostPort = proxyStr;
        }
        var parts = hostPort.split(":");
        var host = parts[0];
        var port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        return ConnectionOptions.builder().proxyHost(host).proxyPort(port).build();
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
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    @Override
    public ChatClient thinkingEffort(String effort) {
        this.thinkingEffort = effort;
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
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    @Override
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role(Role.USER.getValue()).content(content).build());
        return this;
    }

    @Override
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role(Role.ASSISTANT.getValue()).content(content).build());
        return this;
    }

    @Override
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    @Override
    public ChatClient session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    @Override
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持文件附件");
    }

    @Override
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持远程文件附件");
    }

    @Override
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    public String chatSync(String prompt) {
        var result = new StringBuilder();
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
        try {
            var startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            var actualModel = model != null ? model : DEFAULT_MODEL;
            var actualTemperature = (float) (temperature != null ? temperature : DEFAULT_TEMPERATURE);
            var actualMaxTokens = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;

            var actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }

            var paramBuilder = GenerationParam.builder()
                    .model(actualModel)
                    .messages(buildMessages(prompt, actualSystem))
                    .temperature(actualTemperature)
                    .maxTokens(actualMaxTokens)
                    .incrementalOutput(true);

            if (thinking) {
                paramBuilder.enableThinking(true);
            }
            if (smartSearch) {
                paramBuilder.enableSearch(true);
            }

            var param = paramBuilder.build();
            var fullContent = new StringBuilder();
            var reasoningContent = new StringBuilder();
            var usageBuilder = AiUsage.builder()
                    .model(actualModel)
                    .provider("alibaba")
                    .startTime(startTime);
            var firstTokenReceived = new boolean[]{false};

            Flowable<GenerationResult> flowable = generation.streamCall(param);
            flowable.blockingForEach(chunk -> {
                if (!firstTokenReceived[0]) {
                    firstTokenReceived[0] = true;
                    usageBuilder.firstTokenLatencyMillis(System.currentTimeMillis() - startTime);
                }

                var choices = chunk.getOutput().getChoices();
                if (choices != null && !choices.isEmpty()) {
                    var choice = choices.get(0);
                    var message = choice.getMessage();
                    if (message != null) {
                        var content = message.getContent();
                        if (content != null) {
                            fullContent.append(content);
                            consumer.accept(ChatResponse.builder()
                                    .state(ChatResponse.State.STREAMING)
                                    .content(content)
                                    .build());
                        }
                        var reasoning = message.getReasoningContent();
                        if (reasoning != null) {
                            reasoningContent.append(reasoning);
                        }
                    }
                }

                var usage = chunk.getUsage();
                if (usage != null) {
                    usageBuilder.inputTokens(usage.getInputTokens())
                            .outputTokens(usage.getOutputTokens())
                            .totalTokens(usage.getTotalTokens());
                }
            });

            var fullContentStr = fullContent.toString();
            var reasoningContentStr = reasoningContent.isEmpty() ? null : reasoningContent.toString();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .content(fullContentStr)
                    .fullContent(fullContentStr)
                    .reasoningContent(reasoningContentStr)
                    .usage(usageBuilder
                            .durationMillis(System.currentTimeMillis() - startTime)
                            .build())
                    .build());
            onComplete.run();

        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
            log.error("阿里云通义千问对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        } catch (Exception e) {
            log.error("阿里云通义千问对话请求异常: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    private List<Message> buildMessages(String prompt, String actualSystem) {
        var messages = new ArrayList<Message>();
        if (actualSystem != null && !actualSystem.isEmpty()) {
            messages.add(Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(actualSystem)
                    .build());
        }
        var source = externalHistory != null ? externalHistory : history;
        for (var msg : source) {
            messages.add(Message.builder()
                    .role(msg.getRole())
                    .content(msg.getContent())
                    .build());
        }
        messages.add(Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build());
        return messages;
    }

}