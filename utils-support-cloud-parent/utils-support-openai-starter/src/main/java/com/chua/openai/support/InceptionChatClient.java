// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.openai.support.inception.InceptionBrowserTransport;
import com.chua.openai.support.inception.InceptionHttpTransport;
import com.chua.openai.support.inception.InceptionTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Inception Labs Mercury 对话客户端
 *
 * <p>把网页版 {@code chat.inceptionlabs.ai} 的私有接口包装成统一的 {@link ChatClient}，
 * 不依赖 OpenAI Java SDK（上游既不是 {@code /v1/chat/completions}，也没有 OpenAI 兼容层）。</p>
 *
 * <h2>上游协议</h2>
 * <p>站点在 2026 年已经切换到 Vercel AI SDK v5 的「UI Message Stream」协议：</p>
 * <ol>
 *   <li>{@code GET /api/session} 拿本轮会话 token（响应 {@code {ok, token}}）；</li>
 *   <li>{@code POST /api/chat}，请求头带 {@code x-session-token}，请求体为
 *       {@code {reasoningEffort, webSearchEnabled, voiceMode, timezone, language,
 *       id, messages:[{id, role, parts:[{type:"text", text}]}], trigger}}；</li>
 *   <li>响应 {@code text/event-stream}，事件形如
 *       {@code {"type":"text-delta","delta":"..."}}，以 {@code data: [DONE]} 结束。</li>
 * </ol>
 *
 * <h2>风控与通道</h2>
 * <p>站点前置 Vercel 安全网关，网关按 TLS / HTTP2 指纹判定调用方：纯 HTTP 客户端
 * 即使携带从浏览器复制的有效 Cookie，也会收到
 * {@code HTTP 429 This request requires a challenge to be completed.}。
 * 因此默认走浏览器通道（{@link InceptionBrowserTransport}）：由本机 Chromium
 * 在页面上下文里发同源 {@code fetch}，天然具备浏览器指纹，且无需手工抓 Cookie。</p>
 *
 * <p>如上游已改为自建 / 反代部署（没有网关拦截），可切换成纯 HTTP 通道
 * （{@link InceptionHttpTransport}），此时用 {@code appKey} 传 Cookie 串。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 *   // 默认：浏览器通道，免 Cookie
 *   ChatClient client = ChatClient.create(ChatClientSetting.builder()
 *           .provider("inceptionlabs")
 *           .model("mercury-3")
 *           .build());
// [P3C E1 豁免] 场景化输出，非业务日志
 *   System.out.println(client.chatSync("用一句话介绍你自己"));
 *
 *   // 纯 HTTP 通道（仅在网关已放行时有意义）
 *   ChatClient http = ChatClient.create(ChatClientSetting.builder()
 *           .provider("inceptionlabs")
 *           .appKey("cf_clearance=...; _vcrcs=...")
 *           .extraHeaders(Map.of("User-Agent", "Mozilla/5.0 ... Chrome/156.0.0.0 ...",
 *                   "x-inception-transport", "http"))
 *           .build());
 * }</pre>
 *
 * <h2>可调参数（extraHeaders 中的控制项，不会真的发到上游）</h2>
 * <ul>
 *   <li>{@code x-inception-transport}：{@code browser}（默认）/ {@code http}；</li>
 *   <li>{@code x-inception-headless}：{@code true}（默认）/ {@code false}，
 *       无头模式被风控判定时改成有头；</li>
 *   <li>{@code x-inception-chrome}：Chromium 可执行文件路径；</li>
 *   <li>{@code User-Agent}：固定 UA（浏览器通道下会同步覆盖 {@code Sec-CH-UA}）；</li>
 *   <li>{@code x-cdp-rotate-ua}：{@code true} 时每个页面随机轮换 UA。</li>
 * </ul>
 *
 * <h2>浏览器生命周期</h2>
 * <p>一个客户端对应一个浏览器进程：首次对话时启动，之后的同步 / 流式调用
 * 都复用同一个页面，不会每次请求都重启浏览器；页面意外失效时才重建。
 * 调用 {@link #close()} 会关闭浏览器并清理临时目录；关闭后再次调用等同于
 * 重新创建客户端（会重新启动浏览器）。</p>
 *
 * <h2>上游未开放的参数</h2>
 * <p>网页版协议只接受 {@code reasoningEffort}、{@code webSearchEnabled} 等字段，
 * <b>不支持采样参数</b>：{@code maxTokens}、{@code temperature}、{@code topP}、
 * {@code stop}、{@code seed} 会被客户端接收但不会发给上游，也不影响输出。
 * 需要限制输出长度时，请在提示词或调用方做截断。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"inceptionlabs", "mercury"})
public class InceptionChatClient implements ChatClient {

    /**
     * 上游站点根地址
     */
    private static final String DEFAULT_URL = "https://chat.inceptionlabs.ai";

    /**
     * 默认模型标识
     */
    private static final String DEFAULT_MODEL = "mercury-3";

    /**
     * 默认模型展示名
     */
    private static final String DEFAULT_MODEL_NAME = "Mercury 3";

    /**
     * 上游默认超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 180_000L;

    /**
     * 控制项：通道类型
     */
    private static final String HEADER_TRANSPORT = "x-inception-transport";

    /**
     * 控制项：是否无头
     */
    private static final String HEADER_HEADLESS = "x-inception-headless";

    /**
     * 控制项：浏览器路径
     */
    private static final String HEADER_CHROME = "x-inception-chrome";

    /**
     * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
     * 对话历史
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 外部传入的完整历史
     */
    private List<ChatMessage> externalHistory;

    /**
     * 工具定义（上游网页接口不支持函数调用，仅保留以兼容链式调用）
     */
    private final List<ChatTool> tools = new ArrayList<>();

    /**
     * 传输通道（懒加载，避免创建客户端就拉起浏览器）
     */
    private InceptionTransport transport;

    /**
     * 通道创建锁
     */
    private final Object transportLock = new Object();

    /**
     * 当前模型标识
     */
    private String model;

    /**
     * 当前温度参数（上游网页接口不接受该参数，仅保留配置）
     */
    private Double temperature;

    /**
     * 当前最大输出 Token（上游网页接口不接受该参数，仅保留配置）
     */
    private Integer maxTokens;

    /**
     * 系统提示词
     */
    private String system;

    /**
     * 会话标识（用于稳定上游对话 id）
     */
    private String sessionId;

    /**
     * Top-P（上游网页接口不接受该参数，仅保留配置）
     */
    private Double topP;

    /**
     * 停止序列（上游网页接口不接受该参数，仅保留配置）
     */
    private List<String> stop;

    /**
     * 随机种子（上游网页接口不接受该参数，仅保留配置）
     */
    private Long seed;

    /**
     * 额外请求体参数，会覆盖同名标准字段
     */
    private Map<String, Object> extraBody;

    /**
     * 运行时自定义请求头（兼作控制项载体）
     */
    private Map<String, String> extraHeaders;

    /**
     * 技能管理器
     */
    private SkillManager skillManager;

    /**
     * 已解析的技能正文
     */
    private String skillPromptBlock;

    /**
     * 是否以流式回调下发内容
     */
    private boolean stream = true;

    /**
     * 是否启用深度思考
     */
    private boolean thinking;

    /**
     * 思考强度（low / medium / high）
     */
    private String thinkingEffort;

    /**
     * 是否启用联网搜索
     */
    private boolean smartSearch;

    /**
     * 稳定对话 ID
     */
    private String conversationId;

    /**
     * 构造客户端
     *
     * @param setting 客户端配置
     */
    public InceptionChatClient(ChatClientSetting setting) {
        this.setting = setting == null ? ChatClientSetting.builder().build() : setting;
        this.model = this.setting.getModel();
        this.temperature = this.setting.getTemperature();
        this.maxTokens = this.setting.getMaxTokens();
        this.system = this.setting.getSystem();
        this.topP = this.setting.getTopP();
        this.thinkingEffort = null;
        this.stop = this.setting.getStop();
        this.seed = this.setting.getSeed();
        if (this.setting.getTools() != null) {
            this.tools.addAll(this.setting.getTools());
        }
        this.extraHeaders = this.setting.getExtraHeaders();
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
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    public ChatClient stop(List<String> stop) {
        this.stop = stop == null ? null : new ArrayList<>(stop);
        return this;
    }

    @Override
    public ChatClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    public ChatClient session(String sessionId) {
        this.sessionId = sessionId;
        this.conversationId = null;
        return this;
    }

    @Override
    public ChatClient extraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody;
        return this;
    }

    @Override
    public ChatClient extraHeaders(Map<String, String> headers) {
        this.extraHeaders = headers;
        return this;
    }

    @Override
    public ChatClient stream(boolean stream) {
        this.stream = stream;
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
    public ChatClient skillPrompt(String block) {
        this.skillPromptBlock = block;
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
    public ChatClient tools(List<ChatTool> tools) {
        this.tools.clear();
        if (tools != null) {
            this.tools.addAll(tools);
        }
        return this;
    }

    @Override
    public ChatClient tool(ChatTool tool) {
        if (tool != null) {
            this.tools.add(tool);
        }
        return this;
    }

    @Override
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        this.conversationId = null;
        return this;
    }

    /**
     * 添加图片（网页接口只接受纯文本分段）
     *
     * @param imageUrl 图片地址
     * @return 不返回，固定抛出异常
     */
    @Override
    public ChatClient addImage(String imageUrl) {
        throw new UnsupportedOperationException("Inception 网页接口暂不支持图片输入");
    }

    /**
     * 添加文件附件（网页接口只接受纯文本分段）
     *
     * @param name     文件名
     * @param data     文件字节
     * @param mimeType MIME 类型
     * @return 不返回，固定抛出异常
     */
    @Override
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        throw new UnsupportedOperationException("Inception 网页接口暂不支持文件附件");
    }

    /**
     * 添加远程附件（网页接口只接受纯文本分段）
     *
     * @param name     文件名
     * @param url      文件地址
     * @param mimeType MIME 类型
     * @return 不返回，固定抛出异常
     */
    @Override
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        throw new UnsupportedOperationException("Inception 网页接口暂不支持远程附件");
    }

    @Override
    public String chatSync(String prompt) {
        ChatSyncResponse response = chatSyncWithResponse(prompt);
        return response == null ? null : response.getText();
    }

    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        boolean savedStream = this.stream;
        this.stream = false;
        try {
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            final AiUsage[] usage = new AiUsage[1];
            /**
             * chat
             * @param prompt 参数
             * @param { 参数
             * @return 返回值
            */
            chat(prompt, response -> {
                if (response.getState() == ChatResponse.State.STOP) {
                    usage[0] = response.getUsage();
                    return;
                }
                if (response.getState() != ChatResponse.State.STREAMING) {
                    return;
                }
                if (response.getContent() != null) {
                    text.append(response.getContent());
                }
                if (response.getReasoningContent() != null) {
                    reasoning.append(response.getReasoningContent());
                }
            });
            if (reasoning.length() > 0) {
                log.debug("[inception] 本轮推理内容 {} 字符", reasoning.length());
            }
            return ChatSyncResponse.builder()
                    .text(text.toString())
                    .usage(usage[0])
                    .build();
        } finally {
            this.stream = savedStream;
        }
    }

    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        /**
         * chat
         * @param ( 参数
         * @param consumer 参数
         * @param prompt 参数
         * @return 返回值
        */
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException("对话请求失败: " + e.getMessage(), e);
        });
    }

    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder()
                .state(ChatResponse.State.START)
                .build());

        StringBuilder bufferedText = new StringBuilder();
        StringBuilder bufferedReasoning = new StringBuilder();
        long[] firstTokenAt = {0L};
        AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                .model(modelOr())
                .provider("inceptionlabs")
                .startTime(startTime);

        try {
            if (!tools.isEmpty()) {
                log.warn("[inception] 网页接口不支持函数调用，已忽略 {} 个工具定义", tools.size());
            }
            String payloadJson = Json.toJson(buildPayload(prompt));

            /**
             * Inception Stream Listener
             * @return 返回值
            */
            transport().chat(payloadJson, new InceptionTransport.InceptionStreamListener() {
                @Override
                public void onText(String delta) {
                    if (firstTokenAt[0] == 0L) {
                        firstTokenAt[0] = System.currentTimeMillis();
                    }
                    if (!stream) {
                        bufferedText.append(delta);
                        return;
                    }
                    consumer.accept(ChatResponse.builder()
                            .state(ChatResponse.State.STREAMING)
                            .content(delta)
                            .build());
                }

                @Override
                public void onReasoning(String delta) {
                    if (firstTokenAt[0] == 0L) {
                        firstTokenAt[0] = System.currentTimeMillis();
                    }
                    if (!stream) {
                        bufferedReasoning.append(delta);
                        return;
                    }
                    consumer.accept(ChatResponse.builder()
                            .state(ChatResponse.State.STREAMING)
                            .reasoningContent(delta)
                            .build());
                }

                @Override
                public void onUsage(InceptionTransport.InceptionUsage usage) {
                    usageBuilder.inputTokens(usage.inputTokens())
                            .outputTokens(usage.outputTokens())
                            .totalTokens(usage.totalTokens());
                }
            });

            long duration = System.currentTimeMillis() - startTime;
            usageBuilder.durationMillis(duration);
            usageBuilder.firstTokenLatencyMillis(firstTokenAt[0] > 0L ? firstTokenAt[0] - startTime : duration);

            if (!stream) {
                String fullText = bufferedText.toString();
                if (!fullText.isEmpty()) {
                    consumer.accept(ChatResponse.builder()
                            .state(ChatResponse.State.STREAMING)
                            .content(fullText)
                            .fullContent(fullText)
                            .reasoningContent(bufferedReasoning.length() == 0
                                    ? null : bufferedReasoning.toString())
                            .build());
                }
            }

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .fullContent(bufferedText.length() == 0 ? null : bufferedText.toString())
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();
        } catch (Exception e) {
            log.error("[inception] 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 构建上游请求体
     *
     * @param prompt 本轮用户输入
     * @return 请求体
     */
    private Map<String, Object> buildPayload(String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasoningEffort", reasoningEffort());
        payload.put("webSearchEnabled", smartSearch);
        payload.put("voiceMode", false);
        payload.put("timezone", ZoneId.systemDefault().getId());
        payload.put("language", Locale.getDefault().toLanguageTag());
        payload.put("id", conversationId());
        payload.put("messages", buildMessages(prompt));
        payload.put("trigger", "submit-message");
        if (extraBody != null && !extraBody.isEmpty()) {
            payload.putAll(extraBody);
        }
        return payload;
    }

    /**
     * 构建上游消息列表
     *
     * @param prompt 本轮输入
     * @return AI SDK v5 UI 消息列表
     */
    private List<Map<String, Object>> buildMessages(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String actualSystem = system;
        if (skillManager != null) {
            actualSystem = SkillPrompt.inject(system, skillManager);
        }
        if (StringUtils.isNotBlank(skillPromptBlock)) {
            actualSystem = StringUtils.isBlank(actualSystem)
                    ? skillPromptBlock
                    : actualSystem + "\n\n" + skillPromptBlock;
        }
        if (StringUtils.isNotBlank(actualSystem)) {
            messages.add(uiMessage("system", actualSystem));
        }

        List<ChatMessage> source = externalHistory != null ? externalHistory : history;
        for (ChatMessage message : source) {
            if (message == null) {
                continue;
            }
            String role = StringUtils.isBlank(message.getRole()) ? "user" : message.getRole().trim();
            String content = message.getContent() == null ? "" : message.getContent();
            if ("tool".equalsIgnoreCase(role)) {
                String callId = message.getToolCallId();
                content = "[工具结果" + (StringUtils.isBlank(callId) ? "" : " " + callId) + "] " + content;
                role = "user";
            }
            if (StringUtils.isBlank(content)) {
                continue;
            }
            messages.add(uiMessage(role, content));
        }
        if (StringUtils.isNotBlank(prompt)) {
            messages.add(uiMessage("user", prompt));
        }
        return messages;
    }

    /**
     * 构造一条 AI SDK v5 UI 消息
     *
     * @param role 角色
     * @param text 文本内容
     * @return 消息
     */
    private Map<String, Object> uiMessage(String role, String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", randomId());
        message.put("role", role);
        message.put("parts", List.of(part));
        return message;
    }

    /**
     * 思考强度
     *
     * @return low / medium / high
     */
    private String reasoningEffort() {
        if (StringUtils.isNotBlank(thinkingEffort)) {
            return thinkingEffort.trim().toLowerCase(Locale.ROOT);
        }
        return thinking ? "high" : "medium";
    }

    /**
     * 稳定的上游对话 ID
     *
     * @return 16 位字母数字
     */
    private String conversationId() {
        if (conversationId != null) {
            return conversationId;
        }
        if (StringUtils.isNotBlank(sessionId)) {
            String normalized = sessionId.replaceAll("[^A-Za-z0-9]", "");
            if (normalized.length() >= 8) {
                conversationId = normalized.length() <= 16 ? normalized : normalized.substring(0, 16);
                return conversationId;
            }
        }
        conversationId = randomId();
        return conversationId;
    }

    /**
     * 生成 16 位随机 ID
     *
     * @return 字母数字串
     */
    private static String randomId() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder(16);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 16; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    /**
     * 获取（必要时创建）传输通道
     *
     * @return 传输通道
     */
    private InceptionTransport transport() {
        InceptionTransport current = transport;
        if (current != null) {
            return current;
        }
        synchronized (transportLock) {
            if (transport != null) {
                return transport;
            }
            String baseUrl = normalizeBaseUrl();
            String mode = control(HEADER_TRANSPORT, "inception.transport", "INCEPTION_TRANSPORT");
            boolean httpMode = "http".equalsIgnoreCase(mode);

            if (httpMode) {
                transport = new InceptionHttpTransport(baseUrl, resolveCookie(), userAgent(),
                        timeoutMillis(), setting.getProxy());
                log.debug("[inception] 使用纯 HTTP 通道: {}", baseUrl);
            } else {
                boolean headless = !"false".equalsIgnoreCase(
                        control(HEADER_HEADLESS, "inception.headless", "INCEPTION_HEADLESS"));
                String chrome = control(HEADER_CHROME, "inception.chrome", "INCEPTION_CHROME");
                boolean rotateUa = Boolean.parseBoolean(
                        control("x-cdp-rotate-ua", "chua.browser.rotateUserAgent", "CHUA_ROTATE_UA"));
                transport = new InceptionBrowserTransport(baseUrl, timeoutMillis(), chrome, headless,
                        setting.getProxy(), userAgent(), rotateUa);
                log.debug("[inception] 使用浏览器通道: {} (headless={}, rotateUa={})",
                        baseUrl, headless, rotateUa);
            }
            return transport;
        }
    }

    /**
     * 读取控制项：请求头 &gt; 系统属性 &gt; 环境变量
     *
     * @param header 请求头名称
     * @param property 系统属性名
     * @param env 环境变量名
     * @return 值，未配置返回 null
     */
    private String control(String header, String property, String env) {
        String value = headerValue(extraHeaders, header);
        if (StringUtils.isBlank(value)) {
            value = headerValue(setting.getExtraHeaders(), header);
        }
        if (StringUtils.isBlank(value)) {
            value = System.getProperty(property);
        }
        if (StringUtils.isBlank(value)) {
            value = System.getenv(env);
        }
        return value;
    }

    /**
     * 解析 Cookie：优先 extraHeaders.Cookie，其次 appKey
     *
     * @return Cookie 字符串
     */
    private String resolveCookie() {
        String cookie = headerValue(extraHeaders, "Cookie");
        if (StringUtils.isBlank(cookie)) {
            cookie = headerValue(setting.getExtraHeaders(), "Cookie");
        }
        if (StringUtils.isBlank(cookie)) {
            cookie = setting.getAppKey();
        }
        if (StringUtils.isBlank(cookie)) {
            return null;
        }
        String normalized = cookie.trim();
        if (normalized.regionMatches(true, 0, "Cookie:", 0, 7)) {
            normalized = normalized.substring(7).trim();
        }
        if (normalized.length() > 1 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 读取 User-Agent
     *
     * @return User-Agent，未配置返回 null
     */
    private String userAgent() {
        String value = headerValue(extraHeaders, "User-Agent");
        if (StringUtils.isBlank(value)) {
            value = headerValue(setting.getExtraHeaders(), "User-Agent");
        }
        return value;
    }

    /**
     * 从请求头映射中按名称读取（不区分大小写）
     *
     * @param headers 请求头映射
     * @param name    头名称
     * @return 头值，未设置返回 null
     */
    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && StringUtils.isNotBlank(entry.getValue())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 规范化上游根地址
     *
     * @return 根地址（无尾斜杠，且去掉多写的 /api）
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (StringUtils.isBlank(url)) {
            url = DEFAULT_URL;
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/api")) {
            url = url.substring(0, url.length() - 4);
        }
        return url;
    }

    /**
     * 当前模型标识
     *
     * @return 模型标识
     */
    private String modelOr() {
        return StringUtils.isBlank(model) ? DEFAULT_MODEL : model;
    }

    /**
     * 单轮超时
     *
     * @return 超时毫秒数
     */
    private long timeoutMillis() {
        Long configured = setting.getTimeoutMillis();
        return configured != null && configured > 0L ? configured : DEFAULT_TIMEOUT_MILLIS;
    }

    @Override
    public List<ModelDefinition> models() {
        return List.of(ModelDefinition.builder()
                .id(DEFAULT_MODEL)
                .name(DEFAULT_MODEL_NAME)
                .provider("inceptionlabs")
                .build());
    }

    @Override
    public ChatClientSetting getSetting() {
        return setting;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getBaseUrl() {
        return normalizeBaseUrl();
    }

    /**
     * 释放传输通道（关闭浏览器进程）
     */
    @Override
    public void close() {
        synchronized (transportLock) {
            if (transport != null) {
                transport.close();
                transport = null;
            }
        }
    }}