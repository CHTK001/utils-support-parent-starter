package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.ai.probe.ProbeReport;
import com.chua.common.support.spi.annotations.Spi;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.ReasoningEffort;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatText;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.completions.CompletionUsage;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * @since 4.0.0.42
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
     * 工具（函数调用）定义列表
     */
    private final List<ChatTool> tools = new ArrayList<>();

    /**
     * 工具选择策略（tool_choice）：auto / none / required / 指定工具名称
     */
    private String toolChoice;

    /**
     * Top-P 采样参数
     */
    private Double topP;

    /**
     * 停止序列
     */
    private List<String> stop;

    /**
     * 随机种子
     */
    private Long seed;

    /**
     * 响应格式：text / json_object
     */
    private String responseFormat;

    /**
     * 额外请求体参数
     */
    private Map<String, Object> extraBody = new HashMap<>();

    /**
     * 自定义 HTTP 请求头
     */
    private Map<String, String> extraHeaders;

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
        this.topP = setting.getTopP();
        this.toolChoice = setting.getToolChoice();
        this.stop = setting.getStop() != null ? new ArrayList<>(setting.getStop()) : null;
        this.seed = setting.getSeed();
        this.responseFormat = setting.getResponseFormat();
        if (setting.getTools() != null) {
            this.tools.addAll(setting.getTools());
        }
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
    /** 添加Image */
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    @Override
    /** 添加Attachment */
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).data(data).mimeType(mimeType).build());
        return this;
    }

    @Override
    /** 添加AttachmentUrl */
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        this.attachments.add(Attachment.builder().name(name).url(url).mimeType(mimeType).build());
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
        history.add(ChatMessage.builder().role("assistant").content(content).build());
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
    /** NewChat */
    public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.attachments.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    /** Tools */
    public ChatClient tools(List<ChatTool> tools) {
        this.tools.clear();
        if (tools != null) {
            this.tools.addAll(tools);
        }
        return this;
    }

    @Override
    /** Tool */
    public ChatClient tool(ChatTool tool) {
        if (tool != null) {
            this.tools.add(tool);
        }
        return this;
    }

    @Override
    /** ToolChoice */
    public ChatClient toolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
        return this;
    }

    @Override
    /** TopP */
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    /** 停止 */
    public ChatClient stop(List<String> stop) {
        this.stop = stop != null ? new ArrayList<>(stop) : null;
        return this;
    }

    @Override
    /** Seed */
    public ChatClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    /** Response格式化 */
    public ChatClient responseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
        return this;
    }

    @Override
    /** ExtraBody */
    public ChatClient extraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody != null ? extraBody : new HashMap<>();
        return this;
    }

    @Override
    /** ExtraHeaders */
    public ChatClient extraHeaders(Map<String, String> headers) {
        this.extraHeaders = headers;
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
        String actualBaseUrl = normalizeBaseUrl();
        String actualApiKey = setting.getAppKey();

        // 构建 OpenAI 请求参数
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model != null ? model : "gpt-3.5-turbo")
                .temperature(temperature != null ? temperature : 0.3)
                .maxTokens(maxTokens != null ? (long) maxTokens : 2048L);

        // 技能注入系统提示词
        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(system, skillManager);
        }
        if (actualSystem != null && !actualSystem.isEmpty()) {
            paramsBuilder.addSystemMessage(actualSystem);
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

        // 工具（函数调用）定义
        if (tools != null && !tools.isEmpty()) {
            List<ChatCompletionTool> toolDefs = new ArrayList<>();
            for (ChatTool tool : tools) {
                ChatCompletionTool def = toOpenAiTool(tool);
                if (def != null) {
                    toolDefs.add(def);
                }
            }
            if (!toolDefs.isEmpty()) {
                paramsBuilder.tools(toolDefs);
            }
        }

        // 工具选择策略（tool_choice）
        if (toolChoice != null && !toolChoice.isBlank()) {
            ChatCompletionToolChoiceOption choice = toToolChoice(toolChoice);
            if (choice != null) {
                paramsBuilder.toolChoice(choice);
            }
        }

        // 采样与生成参数
        if (topP != null) {
            paramsBuilder.topP(topP);
        }
        if (stop != null && !stop.isEmpty()) {
            paramsBuilder.stopOfStrings(stop);
        }
        if (seed != null) {
            paramsBuilder.seed(seed);
        }
        applyResponseFormat(paramsBuilder, responseFormat);
        applyExtraBody(paramsBuilder, extraBody);

        // 思考模式与联网搜索
        if (thinking) {
            String effort = thinkingEffort != null ? thinkingEffort : "high";
            paramsBuilder.reasoningEffort(switch (effort) {
                case "low" -> ReasoningEffort.LOW;
                case "medium" -> ReasoningEffort.MEDIUM;
                default -> ReasoningEffort.HIGH;
            });
        }
        if (smartSearch) {
            paramsBuilder.webSearchOptions(ChatCompletionCreateParams.WebSearchOptions.builder()
                    .searchContextSize(ChatCompletionCreateParams.WebSearchOptions.SearchContextSize.MEDIUM)
                    .build());
        }

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
            // 配置自定义请求头
            Map<String, String> headers = this.extraHeaders != null ? this.extraHeaders
                    : (setting.getExtraHeaders() != null ? setting.getExtraHeaders() : null);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    clientBuilder.putHeader(entry.getKey(), entry.getValue());
                }
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
     * 将统一的 {@link ChatTool} 定义转换为 OpenAI 的 {@link ChatCompletionTool}。
     *
     * @param tool 工具定义
     * @return OpenAI 工具对象，工具名称为空时返回 null
     */
    private static ChatCompletionTool toOpenAiTool(ChatTool tool) {
        if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
            return null;
        }
        FunctionDefinition.Builder functionBuilder = FunctionDefinition.builder().name(tool.getName());
        if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
            functionBuilder.description(tool.getDescription());
        }
        if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
            functionBuilder.parameters(FunctionParameters.builder()
                    .additionalProperties(toJsonValueMap(tool.getParameters()))
                    .build());
        }
        return ChatCompletionTool.ofFunction(ChatCompletionFunctionTool.builder()
                .function(functionBuilder.build())
                .build());
    }

    /**
     * 将字符串形式的 tool_choice 转换为 OpenAI 工具选择对象。
     *
     * @param toolChoice tool_choice 取值
     * @return OpenAI 工具选择对象，无法识别时返回 null
     */
    private static ChatCompletionToolChoiceOption toToolChoice(String toolChoice) {
        if (toolChoice == null || toolChoice.isBlank()) {
            return null;
        }
        String value = toolChoice.trim().toLowerCase();
        return switch (value) {
            case "none" -> ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.NONE);
            case "auto" -> ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO);
            case "required" -> ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.REQUIRED);
            default -> ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                            .function(ChatCompletionNamedToolChoice.Function.builder().name(toolChoice.trim()).build())
                            .build());
        };
    }

    /**
     * 将统一 JSON Schema（Map 形式）转换为 OpenAI JsonValue 映射。
     *
     * @param params 参数 Schema
     * @return JsonValue 映射
     */
    private static Map<String, JsonValue> toJsonValueMap(Map<String, Object> params) {
        Map<String, JsonValue> result = new HashMap<>();
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), JsonValue.from(entry.getValue()));
                }
            }
        }
        return result;
    }

    /**
     * 应用响应格式（response_format）。
     *
     * <p>当前支持 text 与 json_object，json_schema 需要额外 schema 定义，暂不自动生成。
     *
     * @param builder        OpenAI 请求参数构建器
     * @param responseFormat 响应格式取值
     */
    private static void applyResponseFormat(ChatCompletionCreateParams.Builder builder, String responseFormat) {
        if (responseFormat == null || responseFormat.isBlank()) {
            return;
        }
        String value = responseFormat.trim().toLowerCase();
        if ("json_object".equals(value)) {
            builder.responseFormat(ResponseFormatJsonObject.builder().build());
        } else if ("json_schema".equals(value)) {
            log.debug("response_format=json_schema 需要额外 schema 定义，OpenAiChatClient 暂不自动构建");
        } else {
            builder.responseFormat(ResponseFormatText.builder().build());
        }
    }

    /**
     * 应用额外请求体参数，将通用 key 映射到 OpenAI 标准字段。
     *
     * <p>支持的 key：frequency_penalty、presence_penalty、max_completion_tokens、user。
     * 未识别 key 忽略（保证向后兼容）。
     *
     * @param builder   OpenAI 请求参数构建器
     * @param extraBody 额外请求体参数
     */
    private static void applyExtraBody(ChatCompletionCreateParams.Builder builder, Map<String, Object> extraBody) {
        if (extraBody == null || extraBody.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : extraBody.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            switch (entry.getKey()) {
                case "frequency_penalty" -> {
                    if (value instanceof Number number) {
                        builder.frequencyPenalty(number.doubleValue());
                    }
                }
                case "presence_penalty" -> {
                    if (value instanceof Number number) {
                        builder.presencePenalty(number.doubleValue());
                    }
                }
                case "max_completion_tokens" -> {
                    if (value instanceof Number number) {
                        builder.maxCompletionTokens(number.longValue());
                    }
                }
                case "user" -> builder.user(String.valueOf(value));
                default -> log.debug("忽略未映射的额外请求体参数: {}", entry.getKey());
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
    /** Probe */
    public ProbeReport probe() {
        if (probeStation == null) {
            probeStation = new OpenAiProbeStation(setting);
        }
        return probeStation.probe();
    }
}
