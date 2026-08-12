package com.chua.xunfei.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.unfbx.sparkdesk.SparkDeskClient;
import com.unfbx.sparkdesk.entity.AIChatRequest;
import com.unfbx.sparkdesk.entity.AIChatResponse;
import com.unfbx.sparkdesk.entity.Chat;
import com.unfbx.sparkdesk.entity.InHeader;
import com.unfbx.sparkdesk.entity.InPayload;
import com.unfbx.sparkdesk.entity.Message;
import com.unfbx.sparkdesk.entity.Parameter;
import com.unfbx.sparkdesk.entity.Text;
import com.unfbx.sparkdesk.entity.Usage;
import com.unfbx.sparkdesk.listener.ChatListener;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 讯飞星火大模型对话客户端
 *
 * <p>基于 SparkDesk-Java SDK 的 {@link ChatClient} 实现，通过 WebSocket
 * 协议调用星火大模型的对话接口，支持星火 3.0、4.0 等版本。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"xunfei", "spark"})
public class XunfeiChatClient implements ChatClient {

    /**
     * 默认 API 地址（V3.1）
     */
    private static final String DEFAULT_HOST = "https://spark-api.xf-yun.com/v3.1/chat";

    /**
     * 默认超时时间（秒）
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 90;

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
     * 构造讯飞星火对话客户端
     *
     * @param setting 客户端配置
     */
    public XunfeiChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
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
        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] errorRef = new Throwable[1];
        chat(prompt, response -> {
            if (response.getState() == ChatResponse.State.STREAMING
                    && response.getContent() != null) {
                result.append(response.getContent());
            }
        }, latch::countDown, e -> {
            errorRef[0] = e;
            latch.countDown();
        });
        try {
            latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("讯飞星火对话被中断", e);
        }
        if (errorRef[0] != null) {
            throw new RuntimeException(errorRef[0]);
        }
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
        String actualHost = resolveHost();
        String appid = setting.getAppKey();
        String apiKey = setting.getAppKey();
        String apiSecret = setting.getAppSecret();
        String actualDomain = resolveDomain();
        double actualTemperature = temperature != null ? temperature : 0.3;
        int actualMaxTokens = maxTokens != null ? maxTokens : 2048;
        String uid = sessionId != null ? sessionId : "default";

        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(system, skillManager);
        }

        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder()
                .state(ChatResponse.State.START)
                .build());

        // 构建消息列表
        List<Text> textList = new ArrayList<>();
        if (StringUtils.isNotEmpty(actualSystem)) {
            textList.add(Text.builder().role("system").content(actualSystem).build());
        }
        List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
        for (ChatMessage msg : messages) {
            textList.add(Text.builder().role(msg.getRole()).content(msg.getContent()).build());
        }
        textList.add(Text.builder().role("user").content(prompt).build());

        // 构建请求参数
        AIChatRequest aiChatRequest = AIChatRequest.builder()
                .header(InHeader.builder().appid(appid).uid(uid).build())
                .parameter(Parameter.builder()
                        .chat(Chat.builder()
                                .domain(actualDomain)
                                .temperature(actualTemperature)
                                .maxTokens(actualMaxTokens)
                                .topK(4)
                                .build())
                        .build())
                .payload(InPayload.builder()
                        .message(Message.builder().text(textList).build())
                        .build())
                .build();

        // 构建 OkHttpClient（支持代理）
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder();
        String proxyStr = setting.getProxy();
        if (StringUtils.isNotEmpty(proxyStr)) {
            okBuilder.proxy(parseProxy(proxyStr));
        }

        SparkDeskClient sparkClient = SparkDeskClient.builder()
                .appid(appid)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .host(actualHost)
                .okHttpClient(okBuilder.build())
                .build();

        String resolvedModel = model != null ? model : "spark-3.5";
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder contentBuilder = new StringBuilder();

        ChatListener listener = new ChatListener(aiChatRequest) {
            @Override
            public void onChatOutput(AIChatResponse response) {
                if (response.getPayload() == null
                        || response.getPayload().getChoices() == null
                        || response.getPayload().getChoices().getText() == null) {
                    return;
                }
                for (Text text : response.getPayload().getChoices().getText()) {
                    String content = text.getContent();
                    if (content != null) {
                        contentBuilder.append(content);
                        consumer.accept(ChatResponse.builder()
                                .state(ChatResponse.State.STREAMING)
                                .content(content)
                                .build());
                    }
                }
            }

            @Override
            public void onChatError(AIChatResponse response) {
                String errorMsg = "讯飞星火返回错误: code=" + response.getHeader().getCode()
                        + ", message=" + response.getHeader().getMessage();
                log.error("讯飞星火对话请求失败: {}", errorMsg);
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage(errorMsg)
                        .build());
                onError.accept(new RuntimeException(errorMsg));
                latch.countDown();
            }

            @Override
            public void onChatEnd() {
                // 不做任何操作，等待 onChatToken 回调
            }

            @Override
            public void onChatToken(Usage usage) {
                AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                        .model(resolvedModel)
                        .provider("xunfei")
                        .startTime(startTime)
                        .durationMillis(System.currentTimeMillis() - startTime);
                if (usage != null && usage.getText() != null) {
                    usageBuilder.inputTokens(usage.getText().getPromptTokens())
                            .outputTokens(usage.getText().getCompletionTokens())
                            .totalTokens(usage.getText().getTotalTokens());
                }

                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .fullContent(contentBuilder.toString())
                        .usage(usageBuilder.build())
                        .build());

                onComplete.run();
                latch.countDown();
            }
        };

        try {
            sparkClient.chat(listener);
            latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("讯飞星火对话被中断: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage("对话被中断: " + e.getMessage())
                    .build());
            onError.accept(e);
        } catch (Exception e) {
            log.error("讯飞星火对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 解析 API 主机地址
     *
     * <p>优先使用配置的 baseUrl，否则根据模型自动选择。
     *
     * @return API 主机地址
     */
    private String resolveHost() {
        String url = setting.getBaseUrl();
        if (StringUtils.isNotEmpty(url)) {
            return url;
        }
        String m = model != null ? model : "spark-3.5";
        if (m.contains("4.0") || m.contains("v4")) {
            return "https://spark-api.xf-yun.com/v4.0/chat";
        }
        if (m.contains("2.0") || m.contains("v2")) {
            return "https://spark-api.xf-yun.com/v2.1/chat";
        }
        if (m.contains("1.5") || m.contains("v1")) {
            return "https://spark-api.xf-yun.com/v1.1/chat";
        }
        return DEFAULT_HOST;
    }

    /**
     * 解析模型领域参数
     *
     * @return 领域名称
     */
    private String resolveDomain() {
        String m = model != null ? model : "spark-3.5";
        if (m.contains("4.0") || m.contains("v4")) {
            return "4.0Ultra";
        }
        if (m.contains("2.0") || m.contains("v2")) {
            return "generalv2";
        }
        if (m.contains("1.5") || m.contains("v1")) {
            return "general";
        }
        return "generalv3.5";
    }

    /**
     * 解析代理字符串为 Proxy 对象
     *
     * @param proxyStr 代理地址字符串
     * @return Proxy 对象
     */
    private static Proxy parseProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return null;
        }
        Proxy.Type proxyType;
        String hostPort;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            proxyType = Proxy.Type.SOCKS;
            hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
        } else if (proxyStr.startsWith("http://")) {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr.substring(7);
        } else if (proxyStr.startsWith("https://")) {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr.substring(8);
        } else {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        return new Proxy(proxyType, new InetSocketAddress(host, port));
    }
}