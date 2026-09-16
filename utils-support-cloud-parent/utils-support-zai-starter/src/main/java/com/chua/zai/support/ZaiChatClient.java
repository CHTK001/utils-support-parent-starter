package com.chua.zai.support;

import ai.z.openapi.ZaiClient;
import ai.z.openapi.service.chat.ChatService;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatThinking;
import ai.z.openapi.service.model.Choice;
import ai.z.openapi.service.model.Usage;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
* Z.AI 大模型对话客户端
*
* <p>基于 Z.AI OpenAPI 的 {@link ChatClient} 实现，通过 Z.AI SDK
* 调用 Z.AI 平台的对话接口，支持 Z.AI 系列模型。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"zai"})
public class ZaiChatClient implements ChatClient {

    /**
    * Z.AI 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.z.ai/v1";

    /**
    * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
    * Z.AI SDK 客户端
     */
    private final ZaiClient zaiClient;

    /**
    * 对话服务
     */
    private final ChatService chatService;

    /**
    * 当前使用的模型名称
     */
    private String model;

    /**
    * 当前温度参数
     */
    private Double temperature;

    /**
    * 当前最大 令牌 数
     */
    private Integer maxTokens;

    /**
    * 当前系统提示词
     */
    private String system;

    /**
    * 当前会话 标识
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
    * 构造 Z.AI 对话客户端
    *
    * @param setting 客户端配置
     */
    public ZaiChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        applyProxy(setting.getProxy());
        String url = normalizeBaseUrl();
        this.zaiClient = ZaiClient.builder()
                .apiKey(setting.getAppKey())
                .baseUrl(url)
                .build();
        this.chatService = zaiClient.chat();
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
    /** 添加镜像 */
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
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
    /** 添加Attachment */
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持文件附件");
    }

    @Override
    /** 添加attachmenturl */
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持远程文件附件");
    }

    @Override
    /** 新对话 */
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.externalHistory = null;
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
        try {
            long startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            List<ai.z.openapi.service.model.ChatMessage> sdkMessages = new ArrayList<>();
            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }
            if (StringUtils.isNotEmpty(actualSystem)) {
                sdkMessages.add(ai.z.openapi.service.model.ChatMessage.builder()
                        .role("system")
                        .content(actualSystem)
                        .build());
            }
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                sdkMessages.add(ai.z.openapi.service.model.ChatMessage.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build());
            }
            sdkMessages.add(ai.z.openapi.service.model.ChatMessage.builder()
                    .role("user")
                    .content(prompt)
                    .build());

            ChatCompletionCreateParams.ChatCompletionCreateParamsBuilder<?, ?> paramsBuilder =
                    ChatCompletionCreateParams.builder()
                            .model(model != null ? model : "zai-1")
                            .messages(sdkMessages)
                            .stream(false)
                            .temperature(temperature != null ? temperature.floatValue() : 0.3f)
                            .maxTokens(maxTokens != null ? maxTokens : 2048);

            if (thinking) {
                paramsBuilder.thinking(ChatThinking.builder().type("enabled").build());
            }

            ChatCompletionResponse response = chatService.createChatCompletion(paramsBuilder.build());

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "zai-1")
                    .provider("zai")
                    .startTime(startTime)
                    .durationMillis(System.currentTimeMillis() - startTime);

            if (response.isSuccess() && response.getData() != null) {
                Usage usage = response.getData().getUsage();
                if (usage != null) {
                    usageBuilder.inputTokens(usage.getPromptTokens())
                            .outputTokens(usage.getCompletionTokens())
                            .totalTokens(usage.getTotalTokens());
                }

                String content = "";
                if (response.getData().getChoices() != null && !response.getData().getChoices().isEmpty()) {
                    Choice choice = response.getData().getChoices().getFirst();
                    if (choice.getMessage() != null && choice.getMessage().getContent() != null) {
                        content = choice.getMessage().getContent().toString();
                    }
                }

                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(content)
                        .fullContent(content)
                        .usage(usageBuilder.build())
                        .build());
            } else {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage("Z.AI API 返回错误: " + response.getMsg())
                        .build());
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("Z.AI 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
    * 规范化 API 基础地址
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

    /**
    * 通过系统属性配置代理
    *
    * @param proxyStr 代理字符串
     */
    private static void applyProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return;
        }
        String hostPort;
        boolean isSocks = false;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            isSocks = true;
            hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
        } else if (proxyStr.startsWith("https://")) {
            hostPort = proxyStr.substring(8);
        } else if (proxyStr.startsWith("http://")) {
            hostPort = proxyStr.substring(7);
        } else {
            hostPort = proxyStr;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
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