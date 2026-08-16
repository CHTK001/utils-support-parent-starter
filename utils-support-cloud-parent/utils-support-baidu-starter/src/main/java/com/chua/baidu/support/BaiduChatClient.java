package com.chua.baidu.support;

import com.baidubce.qianfan.Qianfan;
import com.baidubce.qianfan.model.chat.ChatResponse;
import com.baidubce.qianfan.model.chat.ChatUsage;
import com.baidubce.qianfan.model.chat.Message;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse.State;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 百度文心一言大模型对话客户端
 *
 * <p>基于百度千帆大模型平台 SDK 的 {@link ChatClient} 实现，通过千帆 SDK
 * 调用文心一言（ERNIE-Bot）的对话接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"baidu"})
public class BaiduChatClient implements ChatClient {

    /**
     * 千帆 SDK 客户端
     */
    private final Qianfan qianfan;

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
     * 构造百度文心一言对话客户端
     *
     * @param setting 客户端配置
     */
    public BaiduChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        configureProxy(setting.getProxy());
        String baseUrl = normalizeBaseUrl();
        this.qianfan = new Qianfan(setting.getAppKey(), setting.getAppSecret(), baseUrl);
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
        StringBuilder result = new StringBuilder();
        chat(prompt, response -> {
            if (response.getState() == State.STREAMING
                    && response.getContent() != null) {
                result.append(response.getContent());
            }
        });
        return result.toString();
    }

    @Override
    public void chat(String prompt, Consumer<com.chua.common.support.ai.chat.ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException(e);
        });
    }

    @Override
    public void chat(String prompt, Consumer<com.chua.common.support.ai.chat.ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        try {
            long startTime = System.currentTimeMillis();
            consumer.accept(com.chua.common.support.ai.chat.ChatResponse.builder()
                    .state(State.START)
                    .build());

            String actualModel = model != null ? model : "ernie-3.5-8k";
            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }

            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            List<Message> sdkMessages = new ArrayList<>();
            for (ChatMessage msg : messages) {
                sdkMessages.add(new Message()
                        .setRole(msg.getRole())
                        .setContent(msg.getContent()));
            }
            sdkMessages.add(new Message()
                    .setRole("user")
                    .setContent(prompt));

            com.baidubce.qianfan.core.builder.ChatBuilder builder = qianfan.chatCompletion()
                    .model(actualModel)
                    .messages(sdkMessages)
                    .temperature(temperature != null ? temperature : 0.3)
                    .maxOutputTokens(maxTokens != null ? maxTokens : 2048);

            if (actualSystem != null && !actualSystem.isEmpty()) {
                builder.system(actualSystem);
            }
            if (smartSearch) {
                builder.disableSearch(false);
            }
            if (thinking) {
                builder.addExtraParameter("thinking", true);
            }

            ChatResponse response = builder.execute();
            String result = response.getResult();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(actualModel)
                    .provider("baidu")
                    .startTime(startTime)
                    .durationMillis(System.currentTimeMillis() - startTime);
            ChatUsage usage = response.getUsage();
            if (usage != null) {
                usageBuilder.inputTokens(usage.getPromptTokens())
                        .outputTokens(usage.getCompletionTokens())
                        .totalTokens(usage.getTotalTokens());
            }

            consumer.accept(com.chua.common.support.ai.chat.ChatResponse.builder()
                    .state(State.STOP)
                    .content(result)
                    .fullContent(result)
                    .usage(usageBuilder.build())
                    .build());

            onComplete.run();

        } catch (Exception e) {
            log.error("百度文心一言对话请求失败: {}", e.getMessage(), e);
            consumer.accept(com.chua.common.support.ai.chat.ChatResponse.builder()
                    .state(State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>若未配置地址则使用默认的文心一言 API 地址。
     *
     * @return 规范化后的 URL
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = "https://aip.baidubce.com";
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 配置代理
     *
     * <p>设置 JVM 系统属性以启用代理，千帆 SDK 内部 HttpClient 会读取这些属性。
     *
     * @param proxyStr 代理地址字符串，如 http://127.0.0.1:8080 或 socks5://127.0.0.1:1080
     */
    private static void configureProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return;
        }
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
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", String.valueOf(port));
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", String.valueOf(port));
    }

}