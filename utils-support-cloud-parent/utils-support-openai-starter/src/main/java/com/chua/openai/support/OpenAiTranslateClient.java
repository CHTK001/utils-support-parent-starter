package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.AuthHeaders;
import com.chua.common.support.ai.translate.TranslateClient;
import com.chua.common.support.ai.translate.TranslateClientSetting;
import com.chua.common.support.ai.translate.TranslateResponse;
import com.chua.common.support.ai.translate.TranslateSyncResponse;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.completions.CompletionUsage.PromptTokensDetails;
import com.openai.core.http.StreamResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * OpenAI 翻译客户端
 *
 * <p>基于 openai-java SDK 的 {@link TranslateClient} 实现，通过标准
 * Chat Completions 接口提供翻译能力，适用于所有 OpenAI 兼容协议的服务商
 * （如 OpenAI、硅基流动、商汤、GitHub Models、Gitee AI、Dots Studio 等，
 * 通过 {@code baseUrl} 区分）。
 *
 * <p>SPI 名称：{@code openai} 及其兼容别名。翻译指令由
 * {@link TranslateClientSetting#getPrompt() prompt} 或源/目标语言自动构建，
 * 模型返回的正文即译文。
 *
 * <p>使用示例：
 * <pre>{@code
 *   String text = TranslateClient.create("openai", "sk-xxx")
 *       .model("gpt-4o-mini")
 *       .targetLang("英语")
 *       .translateSync("你好，世界。");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"openai", "siliconflow", "sensetime", "github", "gitee", "dots", "custom"})
public class OpenAiTranslateClient implements TranslateClient {

    /**
     * OpenAI 默认 API 地址
     */
    protected static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * 默认翻译模型
     */
    protected static final String DEFAULT_MODEL = "gpt-4o-mini";

    /**
     * 默认超时时间（秒）
     */
    protected static final long DEFAULT_TIMEOUT_SECONDS = 90L;

    /**
     * 默认目标语言（未显式指定 targetLang 时使用）
     */
    protected static final String DEFAULT_TARGET_LANGUAGE = "English";

    /**
     * 默认温度（翻译场景取较低值保证译文确定性）
     */
    private static final double DEFAULT_TEMPERATURE = 0.3D;

    /**
     * 默认最大输出 Token 数
     */
    private static final long DEFAULT_MAX_TOKENS = 2048L;

    /**
     * 客户端配置
     */
    private final TranslateClientSetting setting;

    /**
     * 当前使用的模型名称
     */
    private String model;

    /**
     * 当前源语言
     */
    private String sourceLanguage;

    /**
     * 当前目标语言
     */
    private String targetLanguage;

    /**
     * 当前翻译指令模板
     */
    private String prompt;

    /**
     * 当前温度参数
     */
    private Double temperature;

    /**
     * 当前最大输出 Token 数
     */
    private Integer maxTokens;

    /**
     * 当前 Top-P 采样参数
     */
    private Double topP;

    /**
     * 是否使用流式请求（默认 false：翻译结果通常较短，非流式更稳）
     */
    private boolean stream;

    /**
     * 构造 OpenAI 翻译客户端
     *
     * @param setting 客户端配置
     */
    public OpenAiTranslateClient(TranslateClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.sourceLanguage = setting.getSourceLanguage();
        this.targetLanguage = setting.getTargetLanguage();
        this.prompt = setting.getPrompt();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.topP = setting.getTopP();
    }

    @Override
    /**
     * 模型
     */
    public TranslateClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /**
     * 源语言
     */
    public TranslateClient sourceLang(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage;
        return this;
    }

    @Override
    /**
     * 目标语言
     */
    public TranslateClient targetLang(String targetLanguage) {
        this.targetLanguage = targetLanguage;
        return this;
    }

    @Override
    /**
     * 翻译指令
     */
    public TranslateClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    /**
     * Temperature
     */
    public TranslateClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    /**
     * 最大值令牌
     */
    public TranslateClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    /**
     * topp
     */
    public TranslateClient topP(double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    /**
     * 流
     */
    public TranslateClient stream(boolean stream) {
        this.stream = stream;
        return this;
    }

    @Override
    /**
     * 翻译同步
     */
    public String translateSync(String text) {
        TranslateSyncResponse response = translateSyncWithResponse(text);
        return response == null ? null : response.getText();
    }

    @Override
    /**
     * 翻译同步（携带用量信息）
     *
     * @param text 待翻译文本
     * @return 同步响应
     */
    public TranslateSyncResponse translateSyncWithResponse(String text) {
        // 同步方法强制使用非流式，不受外部 stream(true) 影响
        boolean savedStream = this.stream;
        this.stream = false;
        try {
            StringBuilder result = new StringBuilder();
            final AiUsage[] usage = new AiUsage[1];
            translate(text, response -> {
                if (response.getState() == TranslateResponse.State.STREAMING
                        && response.getContent() != null) {
                    result.append(response.getContent());
                }
                if (response.getState() == TranslateResponse.State.STOP
                        && response.getUsage() != null) {
                    usage[0] = response.getUsage();
                }
            });
            return TranslateSyncResponse.builder()
                    .text(result.toString())
                    .usage(usage[0])
                    .build();
        } finally {
            this.stream = savedStream;
        }
    }

    @Override
    /**
     * 翻译
     *
     * @param text  待翻译文本
     * @param consumer 响应回调
     * @param onComplete 完成回调
     * @param onError 错误回调
     */
    public void translate(String text, Consumer<TranslateResponse> consumer,
                          Runnable onComplete, Consumer<Throwable> onError) {
        String actualBaseUrl = normalizeBaseUrl();
        String actualApiKey = setting.getAppKey();

        // 构建 OpenAI 请求参数
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model != null ? model : defaultModel())
                .temperature(temperature != null ? temperature : DEFAULT_TEMPERATURE)
                .maxTokens(maxTokens != null ? (long) maxTokens : DEFAULT_MAX_TOKENS);

        // 翻译指令作为唯一的用户消息下发
        paramsBuilder.addUserMessage(buildPrompt(text));

        if (topP != null) {
            paramsBuilder.topP(topP);
        }
        if (stream) {
            // OpenAI 兼容协议默认不在流式分片回 usage，须显式声明后末帧才带 token 统计
            paramsBuilder.streamOptions(ChatCompletionStreamOptions.builder()
                    .includeUsage(true)
                    .build());
        }

        ChatCompletionCreateParams params = paramsBuilder.build();

        OpenAIClient client = null;
        StreamResponse<ChatCompletionChunk> streamResponse = null;
        try {
            // 构建 OpenAI HTTP 客户端
            OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                    .baseUrl(actualBaseUrl)
                    .timeout(setting.getTimeoutMillis() != null && setting.getTimeoutMillis() > 0L
                            ? Duration.ofMillis(setting.getTimeoutMillis())
                            : Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));

            // 认证头解析：SDK 的 apiKey() 恒产生 Authorization: Bearer <key>，
            // 无法表达「api-key: <key>」这类非标准方案（如 Dots Studio 只认 api-key 头）。
            // 标准方案交给 SDK 原生通道；非标准方案改用 SDK 的 provider-authentication
            // 通道（见 OpenAiProviderAuth），目标认证头再由 putHeader 承载。
            Map.Entry<String, String> auth = resolveAuthHeader();
            boolean customAuth = false;
            if (!AuthHeaders.isDefault(auth)) {
                customAuth = OpenAiProviderAuth.apply(clientBuilder);
                if (!customAuth) {
                    // 反射不可用（SDK 升级改名等）时的降级：退回标准 Bearer
                    clientBuilder.apiKey(actualApiKey);
                } else if (auth != null) {
                    clientBuilder.putHeader(auth.getKey(), auth.getValue());
                }
            } else {
                clientBuilder.apiKey(actualApiKey);
            }

            // 配置 HTTP 代理
            String proxyStr = setting.getProxy();
            if (proxyStr != null && !proxyStr.isBlank()) {
                clientBuilder.proxy(resolveProxy(proxyStr));
            }
            // 配置自定义请求头（认证头由上面统一处理，同名键跳过以免重复）
            Map<String, String> headers = setting.getExtraHeaders();
            if (headers != null) {
                String authName = customAuth && auth != null ? auth.getKey() : null;
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (authName != null && authName.equalsIgnoreCase(entry.getKey())) {
                        continue;
                    }
                    clientBuilder.putHeader(entry.getKey(), entry.getValue());
                }
            }
            client = clientBuilder.build();

            // 发送开始事件
            consumer.accept(TranslateResponse.builder()
                    .state(TranslateResponse.State.START)
                    .build());

            long startTime = System.currentTimeMillis();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : defaultModel())
                    // 用量归属取实际 provider（本客户端同时承载 openai/siliconflow/dots 等
                    // 多个 OpenAI 兼容服务商，写死 openai 会让统计串味）
                    .provider(setting.getProvider() != null ? setting.getProvider() : "openai")
                    .startTime(startTime);

            /**
             * 流式首包时间戳（首个内容 chunk 到达时刻），用于计算首字延迟
             */
            long firstTokenAt = 0;

            if (stream) {
                // 进行流式请求
                streamResponse = client.chat().completions().createStreaming(params);
                Iterator<ChatCompletionChunk> it = streamResponse.stream().iterator();

                while (it.hasNext()) {
                    ChatCompletionChunk chunk = it.next();
                    // include_usage 时 usage 只在末帧给出，且该帧 choices 为空，必须先于 choices 判空读取
                    if (chunk.usage().isPresent()) {
                        CompletionUsage usage = chunk.usage().get();
                        usageBuilder
                                .inputTokens((int) usage.promptTokens())
                                .outputTokens((int) usage.completionTokens())
                                .totalTokens((int) usage.totalTokens())
                                .cacheTokens(extractCacheTokens(usage));
                    }
                    List<ChatCompletionChunk.Choice> choices = chunk.choices();
                    if (choices != null && !choices.isEmpty()) {
                        ChatCompletionChunk.Choice choice = choices.getFirst();

                        Optional<ChatCompletionChunk.Choice.FinishReason> finishReason = choice.finishReason();
                        if (finishReason.isPresent()) {
                            usageBuilder.finishReason(finishReason.get().toString());
                        }

                        ChatCompletionChunk.Choice.Delta delta = choice.delta();
                        if (delta != null) {
                            Optional<String> content = delta.content();
                            if (content.isPresent()) {
                                if (firstTokenAt == 0) {
                                    firstTokenAt = System.currentTimeMillis();
                                }
                                consumer.accept(TranslateResponse.builder()
                                        .state(TranslateResponse.State.STREAMING)
                                        .content(content.get())
                                        .build());
                            }
                        }
                    }
                }
            } else {
                // 非流式请求：一次返回完整结果，避免部分中转服务商流式长连接挂起
                ChatCompletion completion = client.chat().completions().create(params);
                if (completion.choices() != null && !completion.choices().isEmpty()) {
                    ChatCompletion.Choice choice = completion.choices().getFirst();
                    ChatCompletionMessage message = choice.message();
                    if (message != null) {
                        message.content().ifPresent(content -> consumer.accept(TranslateResponse.builder()
                                .state(TranslateResponse.State.STREAMING)
                                .content(content)
                                .build()));
                    }
                    if (choice.finishReason() != null) {
                        usageBuilder.finishReason(choice.finishReason().toString());
                    }
                }
                if (completion.usage() != null) {
                    CompletionUsage usage = completion.usage().get();
                    usageBuilder
                            .inputTokens((int) usage.promptTokens())
                            .outputTokens((int) usage.completionTokens())
                            .totalTokens((int) usage.totalTokens())
                            .cacheTokens(extractCacheTokens(usage));
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            usageBuilder.durationMillis(duration);
            if (firstTokenAt > 0) {
                usageBuilder.firstTokenLatencyMillis(firstTokenAt - startTime);
            } else if (!stream) {
                // 非流式请求无法观测首包，用户看到首字的时间即完整响应到达时间
                usageBuilder.firstTokenLatencyMillis(duration);
            }

            // 发送结束事件
            consumer.accept(TranslateResponse.builder()
                    .state(TranslateResponse.State.STOP)
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();

        } catch (Exception e) {
            log.error("OpenAI 翻译请求失败: {}", e.getMessage(), e);
            // 发送错误事件
            consumer.accept(TranslateResponse.builder()
                    .state(TranslateResponse.State.ERROR)
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
     * 构建翻译指令
     *
     * <p>优先级：自定义 prompt &gt; 源语言 + 目标语言 &gt; 仅目标语言 &gt; 默认目标语言（英语）。
     * 指令统一使用英文下发，避免中文指令对多语言模型的干扰；
     * 自定义 prompt 支持 {@code {text}} 占位符（替换为待翻译文本），
     * 不含占位符时在指令后换行拼接文本。
     *
     * @param text 待翻译文本
     * @return 完整的翻译指令
     */
    protected String buildPrompt(String text) {
        String safeText = text == null ? "" : text;
        String instruction;
        if (prompt != null && !prompt.isBlank()) {
            instruction = prompt;
        } else if (notBlank(sourceLanguage) && notBlank(targetLanguage)) {
            instruction = "Translate the following text from " + sourceLanguage
                    + " to " + targetLanguage
                    + ". Output only the translation, without any explanation.";
        } else if (notBlank(targetLanguage)) {
            instruction = "Translate the following text to " + targetLanguage
                    + ". Output only the translation, without any explanation.";
        } else {
            instruction = "Translate the following text into " + DEFAULT_TARGET_LANGUAGE
                    + ". Output only the translation, without any explanation.";
        }
        if (instruction.contains("{text}")) {
            return instruction.replace("{text}", safeText);
        }
        return instruction + "\n\n" + safeText;
    }

    /**
     * 子类默认 API 基础地址
     *
     * @return 默认地址
     */
    protected String defaultBaseUrl() {
        return DEFAULT_URL;
    }

    /**
     * 子类默认翻译模型
     *
     * @return 默认模型
     */
    protected String defaultModel() {
        return DEFAULT_MODEL;
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>移除末尾多余的斜杠，若未配置则使用子类默认地址。
     *
     * @return 规范化后的 URL
     */
    protected String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = defaultBaseUrl();
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
     * @return Proxy 对象，解析失败时返回 空
     */
    protected static Proxy resolveProxy(String proxyStr) {
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
     * 从 {@link CompletionUsage} 中提取缓存命中 Token 数。
     *
     * <p>OpenAI Prompt Caching 功能会在 {@code prompt_tokens_details.cached_tokens}
     * 中返回缓存命中的 Token 数。
     *
     * @param usage 完成用量
     * @return 缓存命中 Token 数，不可用时返回 0
     */
    protected static int extractCacheTokens(CompletionUsage usage) {
        if (usage == null) {
            return 0;
        }
        try {
            return usage.promptTokensDetails()
                    .map(PromptTokensDetails::cachedTokens)
                    .filter(Optional::isPresent)
                    .map(opt -> opt.get().intValue())
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 判断字符串非空白
     *
     * @param value 待判断字符串
     * @return true 表示非空且非空白
     */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public TranslateClientSetting getSetting() {
        return setting;
    }

    @Override
    public String getModel() {
        return model;
    }
}
