package com.chua.zhipu.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.zhipu.oapi.ClientV3;
import com.zhipu.oapi.Constants;
import com.zhipu.oapi.service.v3.ModelApiRequest;
import com.zhipu.oapi.service.v3.ModelApiResponse;
import com.zhipu.oapi.service.v3.Choice;
import com.zhipu.oapi.service.v3.Usage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 智谱 GLM 大模型对话客户端
 *
 * <p>基于智谱 AI 开放平台 GLM API 的 {@link ChatClient} 实现，通过官方的 oapi-java-sdk
 * 调用智谱 GLM-4 系列模型的对话接口。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"zhipu", "glm"})
public class ZhipuChatClient implements ChatClient {

    /**
     * 智谱 GLM 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4";

    /**
     * Zhipu SDK 客户端
     */
    private final ClientV3 client;

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
     * 构造智谱 GLM 对话客户端
     *
     * @param setting 客户端配置
     */
    public ZhipuChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        setupProxy(setting.getProxy());
        this.client = new ClientV3.Builder(setting.getAppKey())
                .build();
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
        return chatSyncInternal(prompt).getText();
    }

    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        return chatSyncInternal(prompt);
    }

    /**
     * 同步对话内部实现，同时捕获文本与用量信息。
     *
     * @param prompt 用户输入
     * @return 包含文本与用量的响应
     */
    private ChatSyncResponse chatSyncInternal(String prompt) {
        StringBuilder result = new StringBuilder();
        AiUsage[] usageHolder = new AiUsage[1];
        chat(prompt, response -> {
            if ((response.getState() == ChatResponse.State.STREAMING
                    || response.getState() == ChatResponse.State.STOP)
                    && response.getContent() != null) {
                result.append(response.getContent());
            }
            if (response.getUsage() != null) {
                usageHolder[0] = response.getUsage();
            }
        });
        return ChatSyncResponse.builder()
                .text(result.toString())
                .usage(usageHolder[0])
                .build();
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
            long startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            ModelApiRequest request = new ModelApiRequest();
            request.setModelId(model != null ? model : "glm-4");
            request.setInvokeMethod(Constants.invokeMethod);
            request.setReturnType(Constants.RETURN_TYPE_JSON);
            request.setTemperature(temperature != null ? temperature.floatValue() : 0.3f);
            request.setTopP(0.7f);
            if (maxTokens != null) {
                request.setMaxTokens(maxTokens);
            }
            request.setRequestId(UUID.randomUUID().toString());

            List<ModelApiRequest.Prompt> prompts = new ArrayList<>();
            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }
            if (StringUtils.isNotEmpty(actualSystem)) {
                prompts.add(new ModelApiRequest.Prompt("system", actualSystem));
            }
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                prompts.add(new ModelApiRequest.Prompt(msg.getRole(), msg.getContent()));
            }
            prompts.add(new ModelApiRequest.Prompt("user", prompt));
            request.setPrompt(prompts);

            Map<String, Object> ref = new HashMap<>();
            if (thinking) {
                ref.put("thinking", true);
            }
            if (smartSearch) {
                ref.put("enable_search", true);
            }
            if (!ref.isEmpty()) {
                request.setRef(ref);
            }

            ModelApiResponse response = client.invokeModelApi(request);

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "glm-4")
                    .provider("zhipu")
                    .startTime(startTime)
                    .durationMillis(System.currentTimeMillis() - startTime);

            if (response.isSuccess() && response.getData() != null) {
                Usage usage = response.getData().getUsage();
                if (usage != null) {
                    usageBuilder.inputTokens(usage.getPromptTokens())
                            .outputTokens(usage.getCompletionTokens())
                            .totalTokens(usage.getTotalTokens());
                }

                StringBuilder content = new StringBuilder();
                List<Choice> choices = response.getData().getChoices();
                if (choices != null) {
                    for (Choice choice : choices) {
                        if (choice.getContent() != null) {
                            content.append(unquote(choice.getContent()));
                        }
                    }
                }

                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(content.toString())
                        .fullContent(content.toString())
                        .usage(usageBuilder.build())
                        .build());
            } else {
                log.warn("智谱 GLM API 返回失败: code={}, msg={}", response.getCode(), response.getMsg());
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage("智谱 GLM API 返回错误: " + response.getCode() + " - " + response.getMsg())
                        .build());
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("智谱 GLM 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 剥离 JSON 字符串外层引号（智谱 SDK 返回的 content 为带引号的 JSON 字面量）。
     *
     * @param value 原始内容
     * @return 去除首尾引号后的内容
     */
    private static String unquote(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 通过系统属性配置代理
     *
     * <p>Zhipu SDK 内部使用 OkHttp，需通过系统属性设置代理。
     *
     * @param proxyStr 代理字符串，如 http://127.0.0.1:8080
     */
    private static void setupProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return;
        }
        String host;
        int port;
        String scheme;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            scheme = "socks";
            String hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
            String[] parts = hostPort.split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 1080;
        } else if (proxyStr.startsWith("http://")) {
            scheme = "http";
            String hostPort = proxyStr.substring(7);
            String[] parts = hostPort.split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        } else if (proxyStr.startsWith("https://")) {
            scheme = "https";
            String hostPort = proxyStr.substring(8);
            String[] parts = hostPort.split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;
        } else {
            scheme = "http";
            String[] parts = proxyStr.split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        }
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", String.valueOf(port));
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", String.valueOf(port));
        if ("socks".equals(scheme)) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", String.valueOf(port));
        }
    }

}