package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.sse.SseClient;
import com.chua.common.support.network.sse.SseConnection;
import com.chua.common.support.network.sse.SseListener;
import com.chua.common.support.network.sse.SseRequest;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Ptero.pro 对话客户端
 *
 * <p>把 {@code https://ptero.pro} 的公开对话接口包装成统一的 {@link ChatClient}。
 * Ptero 是开源的 WordPress 插件（GPLv2），对外暴露 OpenAI 兼容的 REST API，
 * 因此不需要浏览器、不需要 API Key，也不依赖官方 SDK。</p>
 *
 * <h2>接口</h2>
 * <ul>
 *   <li>{@code POST {baseUrl}/chat/completions} —— 同步或 SSE 流式对话；</li>
 *   <li>{@code GET {baseUrl}/models} —— 模型目录。</li>
 * </ul>
 * <p>默认 {@code baseUrl} 为 {@code https://ptero.pro/wp-json/mlp/v1}；
 * 传入站点根地址（如 {@code https://ptero.pro}）时会自动补全路径。</p>
 *
 * <h2>身份与认证</h2>
 * <ul>
 *   <li>不设置 {@code appKey} 时按游客身份访问，服务端按出口 IP 计量；</li>
 *   <li>可通过 {@code extraHeaders} 传 {@code x-mlp-guest-token}（64 位字母数字）
 *       标识设备，同一令牌共享配额；也可用系统属性 {@code ptero.guestToken}
 *       或环境变量 {@code PTERO_GUEST_TOKEN} 提供；</li>
 *   <li>设置 {@code appKey} 时按 {@code Authorization: Bearer <key>} 发送真实 API Key；
 *       游客模式请勿设置，否则服务端会因无效 Key 返回 401。</li>
 * </ul>
 *
 * <h2>模式</h2>
 * <p>服务端支持 {@code smart}（默认）/ {@code fast} / {@code quick} /
 * {@code complex} / {@code full} 五档。通过 {@code extraHeaders} 的
 * {@code x-ptero-mode} 选择，或用系统属性 {@code ptero.mode}、环境变量
 * {@code PTERO_MODE} 提供；该控制头不会真正发往上游。</p>
 *
 * <h2>流式说明</h2>
 * <p>上游虽然返回标准 SSE（{@code data: ...} + {@code [DONE]}，末帧带 usage），
 * 但实测长回答通常整段生成完后一次性下发，首个内容分片可能等于总耗时。
 * 本实现按分片原样回调，不假设逐字增量，调用方也不应依赖首字延迟。</p>
 *
 * <h2>已知约束</h2>
 * <ul>
 *   <li>显式传入不存在的模型不会报错，服务端会静默回退到默认模型，
 *       响应里的 {@code model} 字段是实际使用的模型；</li>
 *   <li>上游限额为 30 次/分钟、1,000,000 tokens/小时（按身份统计），
 *       超限返回 HTTP 429；</li>
 *   <li>站点维护时返回 HTTP 503；</li>
 *   <li>单次输入实测 200,000 字符仍可用，但服务端可能截断，不能据此认定
 *       全部上下文都进入模型。</li>
 * </ul>
 *
 * <h2>示例</h2>
 * <pre>{@code
 *   ChatClient client = ChatClient.create(ChatClientSetting.builder()
 *       .provider("ptero")
 *       .model("deepseek-v4.1-flash:free")
 *       .extraHeaders(Map.of(
 *           "x-mlp-guest-token", "guest0123456789",
 *           "x-ptero-mode", "quick"))
 *       .build());
 *   System.out.println(client.chatSync("你好"));
 * }</pre>
 *
 * @author CHUA
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"ptero"})
public class PteroChatClient implements ChatClient {

    /**
     * 服务商标识
     */
    private static final String PROVIDER = "ptero";

    /**
     * 默认接口地址
     */
    private static final String DEFAULT_BASE_URL = "https://ptero.pro/wp-json/mlp/v1";

    /**
     * 默认模型
     */
    private static final String DEFAULT_MODEL = "qwen3.7-flash:minirouter";

    /**
     * 默认超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 600_000L;

    /**
     * 对话路径
     */
    private static final String CHAT_PATH = "/chat/completions";

    /**
     * 模型列表路径
     */
    private static final String MODELS_PATH = "/models";

    /**
     * 游客令牌请求头
     */
    private static final String HEADER_GUEST_TOKEN = "x-mlp-guest-token";

    /**
     * 游客令牌请求头（别名）
     */
    private static final String HEADER_GUEST_TOKEN_ALIAS = "x-ptero-guest-token";

    /**
     * 模式控制头
     */
    private static final String HEADER_MODE = "x-ptero-mode";

    /**
     * 内置模式
     */
    private static final List<String> SUPPORTED_MODES =
            List.of("smart", "fast", "complex", "quick", "full");

    /**
     * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
     * 当前模型
     */
    private String model;

    /**
     * 系统提示词
     */
    private String system;

    /**
     * 本地历史
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 外部历史
     */
    private List<ChatMessage> externalHistory;

    /**
     * 是否使用 SSE 流式请求
     */
    private boolean stream = true;

    /**
     * 动态额外请求头
     */
    private Map<String, String> extraHeaders;

    /**
     * 动态额外请求体参数
     */
    private final Map<String, Object> extraBody = new LinkedHashMap<>();

    /**
     * 温度
     */
    private Double temperature;

    /**
     * 最大输出 Token
     */
    private Integer maxTokens;

    /**
     * Top-P
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
     * 响应格式
     */
    private String responseFormat;

    /**
     * 上游模式
     */
    private String mode;

    /**
     * 游客令牌
     */
    private String guestToken;

    /**
     * 构造客户端
     *
     * @param setting 客户端配置
     */
    public PteroChatClient(ChatClientSetting setting) {
        this.setting = setting == null ? ChatClientSetting.builder().build() : setting;
        this.model = this.setting.getModel();
        this.system = this.setting.getSystem();
        this.temperature = this.setting.getTemperature();
        this.maxTokens = this.setting.getMaxTokens();
        this.topP = this.setting.getTopP();
        this.stop = this.setting.getStop();
        this.seed = this.setting.getSeed();
        this.responseFormat = this.setting.getResponseFormat();
        this.extraHeaders = this.setting.getExtraHeaders();
        this.guestToken = resolveGuestToken();
        this.mode = resolveMode();
    }

    @Override
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public ChatClient system(String system) {
        this.system = system;
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
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    public ChatClient stop(List<String> stop) {
        this.stop = stop;
        return this;
    }

    @Override
    public ChatClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public ChatClient responseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
        return this;
    }

    @Override
    public ChatClient extraHeaders(Map<String, String> headers) {
        this.extraHeaders = headers;
        this.guestToken = resolveGuestToken();
        this.mode = resolveMode();
        return this;
    }

    @Override
    public ChatClient extraBody(Map<String, Object> extraBody) {
        this.extraBody.clear();
        if (extraBody != null) {
            this.extraBody.putAll(extraBody);
        }
        return this;
    }

    @Override
    public ChatClient stream(boolean stream) {
        this.stream = stream;
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
    public ChatClient newChat() {
        history.clear();
        externalHistory = null;
        return this;
    }

    @Override
    public String chatSync(String prompt) {
        ChatSyncResponse response = chatSyncWithResponse(prompt);
        return response == null ? null : response.getText();
    }

    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        boolean saved = this.stream;
        this.stream = false;
        try {
            StringBuilder text = new StringBuilder();
            AiUsage[] usage = new AiUsage[1];
            String[] failure = new String[1];
            chat(prompt, response -> {
                if (response.getState() == ChatResponse.State.STOP) {
                    usage[0] = response.getUsage();
                    return;
                }
                if (response.getState() == ChatResponse.State.ERROR) {
                    failure[0] = response.getErrorMessage();
                    return;
                }
                if (response.getState() == ChatResponse.State.STREAMING && response.getContent() != null) {
                    text.append(response.getContent());
                }
            });
            if (failure[0] != null && text.length() == 0) {
                throw new IllegalStateException(failure[0]);
            }
            return ChatSyncResponse.builder().text(text.toString()).usage(usage[0]).build();
        } finally {
            this.stream = saved;
        }
    }

    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException("Ptero 对话请求失败: " + e.getMessage(), e);
        });
    }

    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());

        Turn turn = new Turn();
        try {
            List<Map<String, Object>> messages = buildMessages(prompt);
            Map<String, Object> body = buildBody(messages);
            if (this.stream) {
                streamChat(body, consumer, turn);
            } else {
                syncChat(body, consumer, turn);
            }

            long duration = System.currentTimeMillis() - startTime;
            String answer = turn.text.toString();
            remember(prompt, answer);

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .fullContent(answer.isEmpty() ? null : answer)
                    .usage(buildUsage(turn, startTime, duration))
                    .build());
            onComplete.run();
        } catch (Throwable e) {
            log.error("[ptero] 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 流式请求
     *
     * <p>通过 {@link SseClient} SPI 建立 SSE 连接，逐帧回调解析；方法在流结束后返回，
     * 保持与既有同步语义一致（调用方拿到结果后才会构建用量）。</p>
     *
     * @param body     请求体
     * @param consumer 流式回调
     * @param turn     本轮结果
     * @throws Exception 请求失败
     */
    private void streamChat(Map<String, Object> body, Consumer<ChatResponse> consumer, Turn turn)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        SseConnection[] holder = new SseConnection[1];
        SseRequest request = SseRequest.builder()
                .url(baseUrl() + CHAT_PATH)
                .method(HttpMethod.POST)
                .headers(requestHeaders(true))
                .body(Json.toJson(body))
                .connectTimeout(30000)
                .readTimeout(timeoutMillis())
                .build();
        SseConnection connection = SseClient.create().connect(request, new SseListener() {
            /**
             * 收到 SSE 数据帧
             *
             * @param data data 内容
             */
            @Override
            public void onData(String data) {
                try {
                    handleChunk(data, consumer, turn);
                } catch (Throwable e) {
                    failure[0] = e;
                    SseConnection current = holder[0];
                    if (current != null) {
                        current.close();
                    }
                    done.countDown();
                }
            }

            /**
             * 流式连接正常结束
             */
            @Override
            public void onComplete() {
                done.countDown();
            }

            /**
             * 流式连接异常结束
             *
             * @param error 异常信息
             */
            @Override
            public void onError(Throwable error) {
                failure[0] = error;
                done.countDown();
            }
        });
        holder[0] = connection;

        long waitMillis = timeoutMillis() + 60_000L;
        if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
            connection.close();
            throw new IllegalStateException("Ptero 流式响应超时（" + waitMillis + "ms 未结束）");
        }
        if (failure[0] != null) {
            Throwable error = failure[0];
            if (error instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException(describeFailure(0, error.getMessage()), error);
        }
    }

    /**
     * 处理单个 SSE 数据帧
     *
     * @param data     数据帧内容
     * @param consumer 流式回调
     * @param turn     本轮结果
     */
    private void handleChunk(String data, Consumer<ChatResponse> consumer, Turn turn) {
        String payload = data == null ? "" : data.trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        Map<String, Object> chunk;
        try {
            chunk = Json.fromJson(payload);
        } catch (Exception e) {
            log.debug("[ptero] 跳过无法解析的 SSE 帧: {}", truncate(payload));
            return;
        }
        if (chunk == null) {
            return;
        }
        fillMeta(chunk, turn);
        Object choices = chunk.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return;
        }
        Object finish = choice.get("finish_reason");
        if (finish instanceof String finishText && !finishText.isEmpty()) {
            turn.finishReason = finishText;
        }
        Object delta = choice.get("delta");
        if (!(delta instanceof Map<?, ?> deltaMap)) {
            return;
        }
        Object reasoning = deltaMap.get("reasoning_content");
        if (reasoning instanceof String reasoningText && !reasoningText.isEmpty()) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STREAMING)
                    .reasoningContent(reasoningText)
                    .build());
        }
        Object content = deltaMap.get("content");
        if (content instanceof String deltaText && !deltaText.isEmpty()) {
            if (turn.firstTokenAt == 0L) {
                turn.firstTokenAt = System.currentTimeMillis();
            }
            turn.text.append(deltaText);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STREAMING)
                    .content(deltaText)
                    .build());
        }
    }

    /**
     * 非流式请求
     *
     * @param body     请求体
     * @param consumer 流式回调
     * @param turn     本轮结果
     * @throws Exception 请求失败
     */
    private void syncChat(Map<String, Object> body, Consumer<ChatResponse> consumer, Turn turn)
            throws Exception {
        ClientResponse response = newRequestBuilder(CHAT_PATH, false)
                .json()
                .body(Json.toJson(body))
                .post();
        if (response.getStatusCode() != 200) {
            throw new IllegalStateException(describeFailure(response.getStatusCode(),
                    response.getBodyString()));
        }

        Map<String, Object> result = Json.fromJson(response.getBodyString());
        if (result == null) {
            throw new IllegalStateException("Ptero 返回了空响应体");
        }
        fillMeta(result, turn);
        Object choices = result.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("Ptero 响应缺少 choices: "
                    + truncate(response.getBodyString()));
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("Ptero 响应 choices 结构异常: "
                    + truncate(response.getBodyString()));
        }
        Object finish = choice.get("finish_reason");
        if (finish instanceof String finishText && !finishText.isEmpty()) {
            turn.finishReason = finishText;
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("Ptero 响应缺少 message: "
                    + truncate(response.getBodyString()));
        }
        Object content = messageMap.get("content");
        if (content instanceof String text && !text.isEmpty()) {
            turn.firstTokenAt = System.currentTimeMillis();
            turn.text.append(text);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STREAMING)
                    .content(text)
                    .build());
        }
        Object reasoning = messageMap.get("reasoning_content");
        if (reasoning instanceof String reasoningText && !reasoningText.isEmpty()) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STREAMING)
                    .reasoningContent(reasoningText)
                    .build());
        }
    }

    /**
     * 填充响应元数据（模型、请求 ID、用量）
     *
     * @param payload 响应或分片
     * @param turn    本轮结果
     */
    private static void fillMeta(Map<String, Object> payload, Turn turn) {
        Object modelName = payload.get("model");
        if (modelName instanceof String text && !text.isEmpty()) {
            turn.model = text;
        }
        Object requestId = payload.get("id");
        if (requestId instanceof String text && !text.isEmpty()) {
            turn.requestId = text;
        }
        Object usage = payload.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return;
        }
        turn.inputTokens = intValue(usageMap.get("prompt_tokens"));
        turn.outputTokens = intValue(usageMap.get("completion_tokens"));
        turn.totalTokens = intValue(usageMap.get("total_tokens"));
        if (turn.totalTokens == null && turn.inputTokens != null && turn.outputTokens != null) {
            turn.totalTokens = turn.inputTokens + turn.outputTokens;
        }
    }

    /**
     * 构建上游消息
     *
     * <p>Ptero 兼容标准 OpenAI 角色，{@code system} 会原样放在消息列表首位。</p>
     *
     * @param prompt 本轮输入
     * @return 消息列表
     */
    private List<Map<String, Object>> buildMessages(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(system)) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "system");
            message.put("content", system);
            messages.add(message);
        }
        List<ChatMessage> source = externalHistory != null ? externalHistory : history;
        for (ChatMessage item : source) {
            if (item == null || StringUtils.isBlank(item.getContent())) {
                continue;
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", StringUtils.isBlank(item.getRole()) ? "user" : item.getRole());
            message.put("content", item.getContent());
            messages.add(message);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        return messages;
    }

    /**
     * 构建请求体
     *
     * @param messages 消息列表
     * @return 请求体
     */
    private Map<String, Object> buildBody(List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelOr());
        body.put("messages", messages);
        body.put("stream", this.stream);
        if (StringUtils.isNotBlank(mode)) {
            body.put("mode", mode);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        if (topP != null) {
            body.put("top_p", topP);
        }
        if (stop != null && !stop.isEmpty()) {
            body.put("stop", stop);
        }
        if (seed != null) {
            body.put("seed", seed);
        }
        if (StringUtils.isNotBlank(responseFormat) && !"text".equalsIgnoreCase(responseFormat)) {
            body.put("response_format", Map.of("type", responseFormat));
        }
        if (!extraBody.isEmpty()) {
            body.putAll(extraBody);
        }
        return body;
    }

    /**
     * 记录本轮历史
     *
     * @param prompt 用户输入
     * @param answer 模型输出
     */
    private void remember(String prompt, String answer) {
        if (externalHistory != null) {
            return;
        }
        history.add(ChatMessage.builder().role("user").content(prompt).build());
        if (answer != null && !answer.isEmpty()) {
            history.add(ChatMessage.builder().role("assistant").content(answer).build());
        }
    }

    /**
     * 构建用量信息
     *
     * @param turn      本轮结果
     * @param startTime 开始时间
     * @param duration  总耗时
     * @return 用量
     */
    private AiUsage buildUsage(Turn turn, long startTime, long duration) {
        long firstTokenLatency = turn.firstTokenAt > 0L ? turn.firstTokenAt - startTime : duration;
        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .inputTokens(turn.inputTokens)
                .outputTokens(turn.outputTokens)
                .totalTokens(turn.totalTokens)
                .model(StringUtils.isBlank(turn.model) ? modelOr() : turn.model)
                .provider(PROVIDER)
                .requestId(turn.requestId)
                .finishReason(turn.finishReason)
                .startTime(startTime)
                .firstTokenLatencyMillis(firstTokenLatency)
                .durationMillis(duration);
        if (turn.outputTokens != null && duration > 0L) {
            builder.tokensPerSecond(turn.outputTokens * 1000.0 / duration);
        }
        return builder.build();
    }

    /**
     * 创建请求构建器。
     *
     * <p>内部统一走 {@link HttpClientFactory}，因此需要携带的公共请求头
     * （Content-Type / Accept / Accept-Language / User-Agent、额外头、游客令牌、鉴权头）
     * 集中由 {@link #requestHeaders(boolean)} 生成。</p>
     *
     * @param path        接口路径（含前导斜杠）
     * @param eventStream 是否流式
     * @return 请求构建器
     */
    private HttpClientBuilder newRequestBuilder(String path, boolean eventStream) {
        HttpClientBuilder builder = HttpClientFactory.of(baseUrl())
                .path(path)
                .headers(requestHeaders(eventStream))
                .connectTimeout(30000)
                .readTimeout(timeoutMillis());
        String[] proxy = resolveProxy(setting.getProxy());
        if (proxy != null) {
            try {
                builder.proxy(proxy[0], Integer.parseInt(proxy[1]));
            } catch (NumberFormatException e) {
                log.warn("[ptero] 代理端口非法，已忽略: {}", setting.getProxy());
            }
        }
        return builder;
    }

    /**
     * 解析代理地址为 {@code [host, port]}。
     *
     * <p>支持 {@code http://host:port}、{@code https://host:port}、{@code socks5://host:port}
     * 以及裸 {@code host:port}；底层仅支持 HTTP 代理，SOCKS 前缀会被剥离后按 HTTP 代理使用。</p>
     *
     * @param proxyStr 代理地址
     * @return {@code [host, port]}；未配置或格式非法返回 null
     */
    private static String[] resolveProxy(String proxyStr) {
        if (StringUtils.isBlank(proxyStr)) {
            return null;
        }
        String hostPort = proxyStr.trim();
        int sep = hostPort.indexOf("://");
        if (sep >= 0) {
            hostPort = hostPort.substring(sep + 3);
        }
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx == hostPort.length() - 1) {
            return null;
        }
        return new String[]{hostPort.substring(0, idx), hostPort.substring(idx + 1)};
    }

    /**
     * 组装公共请求头。
     *
     * @param eventStream 是否流式（决定 Accept）
     * @return 请求头
     */
    private Map<String, String> requestHeaders(boolean eventStream) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", eventStream ? "text/event-stream" : "application/json");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("User-Agent", "ptero-java-client/1.0");
        for (Map.Entry<String, String> entry : mergedHeaders().entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (StringUtils.isBlank(name) || value == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (HEADER_GUEST_TOKEN.equals(lower) || HEADER_GUEST_TOKEN_ALIAS.equals(lower)
                    || lower.startsWith("x-ptero-")) {
                continue;
            }
            headers.put(name, value);
        }
        if (StringUtils.isNotBlank(guestToken)) {
            headers.put(HEADER_GUEST_TOKEN, guestToken);
        }
        Map.Entry<String, String> auth = resolveAuthHeader();
        if (auth != null) {
            headers.put(auth.getKey(), auth.getValue());
        }
        return headers;
    }

    /**
     * 合并请求头
     *
     * @return 合并结果
     */
    private Map<String, String> mergedHeaders() {
        Map<String, String> merged = new LinkedHashMap<>();
        if (setting.getExtraHeaders() != null) {
            merged.putAll(setting.getExtraHeaders());
        }
        if (extraHeaders != null) {
            merged.putAll(extraHeaders);
        }
        return merged;
    }

    /**
     * 解析游客令牌
     *
     * @return 令牌；未配置返回 null
     */
    private String resolveGuestToken() {
        String token = headerValue(mergedHeaders(), HEADER_GUEST_TOKEN);
        if (StringUtils.isBlank(token)) {
            token = headerValue(mergedHeaders(), HEADER_GUEST_TOKEN_ALIAS);
        }
        if (StringUtils.isBlank(token)) {
            token = System.getProperty("ptero.guestToken");
        }
        if (StringUtils.isBlank(token)) {
            token = System.getenv("PTERO_GUEST_TOKEN");
        }
        return StringUtils.isBlank(token) ? null : token.trim();
    }

    /**
     * 解析上游模式
     *
     * @return 模式
     */
    private String resolveMode() {
        String value = headerValue(mergedHeaders(), HEADER_MODE);
        if (StringUtils.isBlank(value)) {
            value = System.getProperty("ptero.mode");
        }
        if (StringUtils.isBlank(value)) {
            value = System.getenv("PTERO_MODE");
        }
        if (StringUtils.isBlank(value)) {
            return "smart";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODES.contains(normalized)) {
            log.warn("[ptero] 未知模式 {}，回退到 smart（可选：{}）", value, SUPPORTED_MODES);
            return "smart";
        }
        return normalized;
    }

    /**
     * 读取头值（大小写不敏感）
     *
     * @param headers 请求头
     * @param name    头名
     * @return 值；不存在返回 null
     */
    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 当前模型
     *
     * @return 模型名
     */
    private String modelOr() {
        return StringUtils.isBlank(model) ? DEFAULT_MODEL : model;
    }

    /**
     * 规范化接口地址
     *
     * @return 基础地址
     */
    private String baseUrl() {
        String url = setting.getBaseUrl();
        if (StringUtils.isBlank(url)) {
            url = DEFAULT_BASE_URL;
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith(CHAT_PATH)) {
            url = url.substring(0, url.length() - CHAT_PATH.length());
        }
        if (url.endsWith(MODELS_PATH)) {
            url = url.substring(0, url.length() - MODELS_PATH.length());
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.contains("/wp-json")) {
            url = url + "/wp-json/mlp/v1";
        } else if (url.endsWith("/wp-json")) {
            url = url + "/mlp/v1";
        }
        return url;
    }

    /**
     * 超时时间
     *
     * @return 毫秒
     */
    private long timeoutMillis() {
        Long configured = setting.getTimeoutMillis();
        return configured != null && configured > 0L ? configured : DEFAULT_TIMEOUT_MILLIS;
    }

    /**
     * 组装错误信息
     *
     * @param status  状态码
     * @param snippet 响应片段
     * @return 错误描述
     */
    private static String describeFailure(int status, String snippet) {
        String hint = switch (status) {
            case 401 -> "（API Key 无效；游客模式请勿设置 appKey，改用 x-mlp-guest-token）";
            case 429 -> "（已触发上游限流：30 次/分钟或 1,000,000 tokens/小时）";
            case 503 -> "（Ptero 当前处于维护状态）";
            default -> "";
        };
        return "Ptero 上游返回 HTTP " + status + hint + "，响应片段: " + truncate(snippet);
    }

    /**
     * 截断文本
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }

    /**
     * 数字转换
     *
     * @param value 原始值
     * @return 整数；无法转换返回 null
     */
    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public List<ModelDefinition> models() {
        try {
            ClientResponse response = newRequestBuilder(MODELS_PATH, false).get();
            if (response.getStatusCode() != 200) {
                log.warn("[ptero] 获取模型列表失败: {}",
                        describeFailure(response.getStatusCode(), response.getBodyString()));
                return List.of();
            }
            Map<String, Object> body = Json.fromJson(response.getBodyString());
            if (body == null || !(body.get("data") instanceof List<?> items)) {
                return List.of();
            }
            List<ModelDefinition> models = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> model)) {
                    continue;
                }
                Object id = model.get("id");
                if (!(id instanceof String modelId) || modelId.isBlank()) {
                    continue;
                }
                Object owner = model.get("owned_by");
                models.add(ModelDefinition.builder()
                        .id(modelId)
                        .name(modelId)
                        .provider(PROVIDER)
                        .description(owner instanceof String ownerName ? ownerName : null)
                        .build());
            }
            return models;
        } catch (Exception e) {
            log.warn("[ptero] 获取模型列表失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public ChatClientSetting getSetting() {
        return setting;
    }

    @Override
    public String getModel() {
        return modelOr();
    }

    @Override
    public void close() {
        // HTTP 连接由全局 HttpClientFactory 统一管理，此处无需显式释放
    }

    /**
     * 单轮结果
     */
    private static final class Turn {

        /**
         * 文本
         */
        private final StringBuilder text = new StringBuilder();

        /**
         * 实际模型
         */
        private String model;

        /**
         * 请求 ID
         */
        private String requestId;

        /**
         * 结束原因
         */
        private String finishReason;

        /**
         * 输入 Token
         */
        private Integer inputTokens;

        /**
         * 输出 Token
         */
        private Integer outputTokens;

        /**
         * 总 Token
         */
        private Integer totalTokens;

        /**
         * 首内容分片时间
         */
        private long firstTokenAt;
    }
}
