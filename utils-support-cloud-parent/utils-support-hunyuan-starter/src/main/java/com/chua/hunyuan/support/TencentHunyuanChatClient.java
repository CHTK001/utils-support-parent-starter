package com.chua.hunyuan.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.hunyuan.v20230901.HunyuanClient;
import com.tencentcloudapi.hunyuan.v20230901.models.ChatCompletionsRequest;
import com.tencentcloudapi.hunyuan.v20230901.models.ChatCompletionsResponse;
import com.tencentcloudapi.hunyuan.v20230901.models.Choice;
import com.tencentcloudapi.hunyuan.v20230901.models.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 腾讯混元大模型对话客户端
 *
 * <p>基于腾讯云混元（Hunyuan）大模型 SDK 的 {@link ChatClient} 实现，通过腾讯云
   * hunyuan客户端 调用混元的对话接口，支持混元 Pro、标准 等系列模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"tencent-hunyuan", "tencent"})
public class TencentHunyuanChatClient implements ChatClient {

    /**
     * 腾讯混元默认地域
     */
    private static final String DEFAULT_REGION = "ap-guangzhou";

    /**
     * 腾讯混元 SDK 客户端
     */
    private final HunyuanClient client;

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
     * 构造腾讯混元对话客户端
     *
     * @param setting 客户端配置
     */
    public TencentHunyuanChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        Credential credential = new Credential(setting.getAppKey(), setting.getAppSecret());
        ClientProfile clientProfile = new ClientProfile();
        HttpProfile httpProfile = new HttpProfile();
        applyProxy(httpProfile, setting.getProxy());
        clientProfile.setHttpProfile(httpProfile);
        this.client = new HunyuanClient(credential, DEFAULT_REGION, clientProfile);
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

            // 构建腾讯混元请求
            ChatCompletionsRequest request = new ChatCompletionsRequest();
            request.setModel(model != null ? model : "hunyuan-pro");
            request.setMessages(buildMessages(prompt));
            request.setTemperature(temperature != null ? temperature.floatValue() : 0.3f);
            request.setTopP(0.7f);
            request.setStream(false);
            request.setEnableThinking(thinking);
            request.setSearchInfo(smartSearch);

            // 调用腾讯混元对话接口
            ChatCompletionsResponse response = client.ChatCompletions(request);

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "hunyuan-pro")
                    .provider("tencent-hunyuan")
                    .startTime(startTime)
                    .durationMillis(System.currentTimeMillis() - startTime);
            com.tencentcloudapi.hunyuan.v20230901.models.Usage usage = response.getUsage();
            if (usage != null) {
                usageBuilder
                        .inputTokens(usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : null)
                        .outputTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : null)
                        .totalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null);
            }

            StringBuilder fullContent = new StringBuilder();
            if (response.getChoices() != null) {
                for (Choice choice : response.getChoices()) {
                    if (choice.getMessage() != null && choice.getMessage().getContent() != null) {
                        fullContent.append(choice.getMessage().getContent());
                    }
                }
            }

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .content(fullContent.toString())
                    .fullContent(fullContent.toString())
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();

        } catch (Exception e) {
            log.error("腾讯混元对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 构建腾讯混元消息数组
     *
     * <p>依次放入系统提示词（如有）、对话历史与当前用户消息。
     *
     * @param prompt 当前用户消息内容
     * @return 混元 消息 数组
     */
    private Message[] buildMessages(String prompt) {
        List<Message> messages = new ArrayList<>();
        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(system, skillManager);
        }
        if (actualSystem != null && !actualSystem.isEmpty()) {
            Message systemMessage = new Message();
            systemMessage.setRole("system");
            systemMessage.setContent(actualSystem);
            messages.add(systemMessage);
        }
        List<ChatMessage> chatMessages = externalHistory != null ? externalHistory : history;
        for (ChatMessage chatMessage : chatMessages) {
            Message hunyuanMessage = new Message();
            hunyuanMessage.setRole(chatMessage.getRole());
            hunyuanMessage.setContent(chatMessage.getContent());
            messages.add(hunyuanMessage);
        }
        Message userMessage = new Message();
        userMessage.setRole("user");
        userMessage.setContent(prompt);
        messages.add(userMessage);
        return messages.toArray(new Message[0]);
    }

    /**
     * 为 SDK 客户端配置代理
     *
     * <p>解析代理地址，将其应用到腾讯云 SDK 的 HTTP 配置。若未配置代理则忽略。
     *
     * @param httpProfile 腾讯云 SDK HTTP 配置
     * @param proxyStr    代理地址
     */
    private static void applyProxy(HttpProfile httpProfile, String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return;
        }
        String hostPort = proxyStr;
        if (hostPort.startsWith("socks5://") || hostPort.startsWith("socks://")
                || hostPort.startsWith("http://")) {
            hostPort = hostPort.substring(hostPort.indexOf("://") + 3);
        } else if (hostPort.startsWith("https://")) {
            hostPort = hostPort.substring(8);
        }
        String[] parts = hostPort.split(":");
        httpProfile.setProxyHost(parts[0]);
        httpProfile.setProxyPort(parts.length > 1 ? Integer.parseInt(parts[1]) : 80);
    }

}