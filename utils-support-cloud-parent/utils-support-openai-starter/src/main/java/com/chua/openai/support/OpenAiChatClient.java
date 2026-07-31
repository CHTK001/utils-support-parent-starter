package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.probe.ProbeReport;
import com.chua.common.support.spi.annotations.Spi;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * OpenAI 大模型对话客户端
 *
 * <p>基于 OpenAI Java SDK 的 {@link ChatClient} 实现，支持 OpenAI 兼容接口的
 * 所有服务商（如 OpenAI、SiliconFlow、SenseTime 等）。
 *
 * <p>通过 SPI 机制注册以下别名：
 * <ul>
 *   <li>openai — OpenAI 官方</li>
 *   <li>siliconflow — 硅基流动</li>
 *   <li>sensetime — 商汤科技</li>
 *   <li>github — GitHub Models</li>
 *   <li>gitee — Gitee AI</li>
 * </ul>
 *
 * <p>流式调用示例：
 * <pre>{@code
 *   ChatClient.create("openai", "sk-xxx")
 *       .model("gpt-4")
 *       .system("你是一名助手")
 *       .chat("你好", response -> {
 *           if (response.getState() == ChatResponse.State.STREAMING) {
 *               System.out.print(response.getContent());
 *           }
 *       });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"openai", "siliconflow", "sensetime", "github", "gitee"})
public class OpenAiChatClient implements ChatClient {

    /**
     * OpenAI 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

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
     * 附件列表
     */
    private final List<Attachment> attachments = new ArrayList<>();

    /**
     * 真伪探测器实例（延迟初始化）
     */
    private OpenAiProbeStation probeStation;

    /**
     * 构造 OpenAI 对话客户端
     *
     * @param setting 客户端配置
     */
    public OpenAiChatClient(ChatClientSetting setting) {
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
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    @Override
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).data(data).mimeType(mimeType).build());
        return this;
    }

    @Override
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).url(url).mimeType(mimeType).build());
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
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.attachments.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
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
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException(e);
        });
    }

    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        String actualBaseUrl = normalizeBaseUrl();
        String actualApiKey = setting.getAppKey();

        // 构建 OpenAI 请求参数
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model != null ? model : "gpt-3.5-turbo")
                .temperature(temperature != null ? temperature : 0.3)
                .maxTokens(maxTokens != null ? (long) maxTokens : 2048L);

        // 添加系统提示词
        if (system != null && !system.isEmpty()) {
            paramsBuilder.addSystemMessage(system);
        }

        // 添加对话历史（优先使用外部传入的历史）
        List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                paramsBuilder.addUserMessage(msg.getContent());
            } else {
                paramsBuilder.addAssistantMessage(msg.getContent());
            }
        }

        // 添加当前用户输入
        paramsBuilder.addUserMessage(prompt);

        ChatCompletionCreateParams params = paramsBuilder.build();

        OpenAIClient client = null;
        StreamResponse<ChatCompletionChunk> streamResponse = null;
        try {
            // 构建 OpenAI HTTP 客户端
            OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                    .apiKey(actualApiKey)
                    .baseUrl(actualBaseUrl)
                    .timeout(Duration.ofSeconds(90));

            // 配置 HTTP 代理
            String proxyStr = setting.getProxy();
            if (proxyStr != null && !proxyStr.isBlank()) {
                clientBuilder.proxy(resolveProxy(proxyStr));
            }
            client = clientBuilder.build();

            // 发送开始事件
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            long startTime = System.currentTimeMillis();

            // 进行流式请求
            streamResponse = client.chat().completions().createStreaming(params);
            Iterator<ChatCompletionChunk> it = streamResponse.stream().iterator();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model)
                    .provider("openai")
                    .startTime(startTime);

            while (it.hasNext()) {
                ChatCompletionChunk chunk = it.next();
                List<ChatCompletionChunk.Choice> choices = chunk.choices();
                if (choices != null && !choices.isEmpty()) {
                    ChatCompletionChunk.Choice choice = choices.get(0);

                    // 检查是否完成
                    Optional<ChatCompletionChunk.Choice.FinishReason> finishReason = choice.finishReason();
                    if (finishReason.isPresent()
                            && finishReason.get() == ChatCompletionChunk.Choice.FinishReason.STOP) {
                        usageBuilder.finishReason("stop");
                        // 记录用量信息
                        if (chunk.usage().isPresent()) {
                            CompletionUsage usage = chunk.usage().get();
                            usageBuilder
                                    .inputTokens((int) usage.promptTokens())
                                    .outputTokens((int) usage.completionTokens())
                                    .totalTokens((int) usage.totalTokens());
                        }
                        break;
                    }

                    // 提取内容片段
                    ChatCompletionChunk.Choice.Delta delta = choice.delta();
                    if (delta != null) {
                        Optional<String> content = delta.content();
                        String reasoning = extractReasoning(delta._additionalProperties());
                        if (content.isPresent()) {
                            consumer.accept(ChatResponse.builder()
                                    .state(ChatResponse.State.STREAMING)
                                    .content(content.get())
                                    .reasoningContent(reasoning)
                                    .build());
                        } else if (reasoning != null) {
                            consumer.accept(ChatResponse.builder()
                                    .state(ChatResponse.State.STREAMING)
                                    .reasoningContent(reasoning)
                                    .build());
                        }
                    }
                }
            }

            usageBuilder.durationMillis(System.currentTimeMillis() - startTime);

            // 发送结束事件
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();

        } catch (Exception e) {
            log.error("OpenAI 对话请求失败: {}", e.getMessage(), e);
            // 发送错误事件
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        } finally {
            // 释放资源
            if (streamResponse != null) {
                try {
                    streamResponse.close();
                } catch (Exception e) {
                    log.debug("关闭流式响应失败", e);
                }
            }
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.debug("关闭 OpenAI 客户端失败", e);
                }
            }
        }
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>移除末尾多余的斜杠，若未配置则使用默认地址。
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
     * 解析代理地址字符串
     *
     * @param proxyStr 代理地址字符串，支持 http://、socks5:// 格式
     * @return Proxy 对象，解析失败时返回 null
     */
    private static Proxy resolveProxy(String proxyStr) {
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
        } else {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        return new Proxy(proxyType, new InetSocketAddress(host, port));
    }

    /**
     * 附加
     * @param  附加
     */
    private static String extractReasoning(Map<String, JsonValue> additionalProps) {
        if (additionalProps == null || additionalProps.isEmpty()) {
            return null;
        }
        for (String key : new String[]{"reasoning_content", "reasoning"}) {
            JsonValue rv = additionalProps.get(key);
            if (rv != null) {
                Optional<String> val = rv.asString();
                if (val.isPresent()) {
                    return val.get();
                }
            }
        }
        return null;
    }

    @Override
    public ProbeReport probe() {
        if (probeStation == null) {
            probeStation = new OpenAiProbeStation(setting);
        }
        return probeStation.probe();
    }
}
