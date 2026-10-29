package com.chua.duckduckgo.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.generation.ImageGenerationResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DuckDuckGo Duck.ai 对话客户端
 *
 * <p>把 {@code https://duck.ai} 的网页对话包装成统一的 {@link ChatClient}，
 * 不需要 API Key，也不依赖任何官方 SDK。</p>
 *
 * <h2>协议</h2>
 * <p>站点接口全部同源：</p>
 * <ol>
 *   <li>{@code GET /duckchat/v1/status}（{@code x-vqd-accept: 1}）返回
 *       {@code x-vqd-hash-1}，内容是一段 base64 的挑战脚本；</li>
 *   <li>脚本必须在页面里执行：它读取 {@code navigator.userAgent}、测量 DOM 尺寸；</li>
 *   <li>客户端对脚本返回的 client 原文做 SHA-256 + Base64，连同 server 哈希与 meta
 *       组成新的 {@code x-vqd-hash-1}；</li>
 *   <li>{@code POST /duckchat/v1/chat} 返回 SSE，逐段读取 {@code message} 字段。</li>
 * </ol>
 *
 * <h2>通道</h2>
 * <p>挑战脚本依赖真实浏览器环境（UA、DOM 布局、WebCrypto），因此固定走
 * {@link DuckBrowserTransport}。一个客户端对应一个浏览器进程，多次对话复用页面，
 * {@link #close()} 后释放。</p>
 *
 * <h2>system 提示词</h2>
 * <p>上游只接受 {@code user} / {@code assistant} 角色，{@code system} 会被写成消息列表
 * 的第一条 {@code user} 消息，再拼接历史与本次提问。因此它在一段新会话的开头效果最好；
 * 在已有较长历史的会话中途修改 system，前面轮次会稀释指令，必要时先调用
 * {@link #newChat()} 再设置。</p>
 *
 * <h2>注意</h2>
 * <ul>
 *   <li>{@code temperature}、{@code maxTokens}、{@code topP}、{@code stop}、{@code seed}
 *       上游不支持，设置后不会影响输出；</li>
 *   <li>Duck.ai 对出口 IP 有风控，HTTP 418 通常是被限流，客户端会重试 3 次；</li>
 *   <li>模型列表随站点更新，可用 {@link #models()} 查看内置的常用模型。</li>
 * </ul>
 *
 * <h2>文生图</h2>
 * <p>支持 {@link #generateImage(String, String, int, int, int, String, String)}：
 * 走同一条 {@code /duckchat/v1/chat} SSE 流，请求体带
 * {@code canDelegateImageGeneration=true} 与 {@code toolChoice.GenerateImage=true}，
 * 解析器捕获 {@code generated-image} / {@code partial-image} 事件，返回
 * {@link ImageGenerationResult}。{@code ratio} 会换算成宽高提示；上游对生成数量、
 * 频率有限制（超限会返回 {@code image_generation_limit} 错误）。</p>
 *
 * @author CHUA
 */
@Slf4j
@Spi({"duck", "duckduckgo"})
public class DuckChatClient implements ChatClient {

    /**
     * 默认模型
     */
    private static final String DEFAULT_MODEL = DuckBrowserTransport.DEFAULT_MODEL;

    /**
     * 默认站点
     */
    private static final String DEFAULT_URL = "https://duck.ai";

    /**
     * 默认超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 180_000L;

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
     * 是否流式
     */
    private boolean stream = true;

    /**
     * 浏览器通道
     */
    private DuckBrowserTransport transport;

    /**
     * 通道锁
     */
    private final Object transportLock = new Object();

    /**
     * 构造客户端
     *
     * @param setting 配置
     */
    public DuckChatClient(ChatClientSetting setting) {
        this.setting = setting == null ? ChatClientSetting.builder().build() : setting;
        this.model = this.setting.getModel();
        this.system = this.setting.getSystem();
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
            chat(prompt, response -> {
                if (response.getState() == ChatResponse.State.STOP) {
                    usage[0] = response.getUsage();
                    return;
                }
                if (response.getState() == ChatResponse.State.STREAMING && response.getContent() != null) {
                    text.append(response.getContent());
                }
            });
            return ChatSyncResponse.builder().text(text.toString()).usage(usage[0]).build();
        } finally {
            this.stream = saved;
        }
    }

    @Override
    public void chat(String prompt, java.util.function.Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException("对话请求失败: " + e.getMessage(), e);
        });
    }

    @Override
    public void chat(String prompt, java.util.function.Consumer<ChatResponse> consumer,
                     Runnable onComplete, java.util.function.Consumer<Throwable> onError) {
        long startTime = System.currentTimeMillis();
        consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());

        StringBuilder fullText = new StringBuilder();
        StringBuilder buffered = new StringBuilder();
        long[] firstTokenAt = {0L};
        AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                .model(modelOr())
                .provider("duckduckgo")
                .startTime(startTime);

        try {
            List<Map<String, Object>> messages = buildMessages(prompt);
            transport().chat(modelOr(), messages, delta -> {
                if (firstTokenAt[0] == 0L) {
                    firstTokenAt[0] = System.currentTimeMillis();
                }
                fullText.append(delta);
                if (!stream) {
                    buffered.append(delta);
                    return;
                }
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content(delta)
                        .build());
            });

            long duration = System.currentTimeMillis() - startTime;
            usageBuilder.durationMillis(duration);
            usageBuilder.firstTokenLatencyMillis(firstTokenAt[0] > 0L ? firstTokenAt[0] - startTime : duration);

            if (!stream && buffered.length() > 0) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content(buffered.toString())
                        .fullContent(buffered.toString())
                        .build());
            }

            String answer = fullText.toString();
            remember(prompt, answer);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .fullContent(answer.isEmpty() ? null : answer)
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();
        } catch (Exception e) {
            log.error("[duck] 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
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
     * 构建上游消息
     *
     * <p>Duck.ai 的消息结构有两个约束，都是实测出来的：</p>
     * <ul>
     *   <li>assistant 的 {@code content} 必须是字符串，传 parts 数组会被判 400；</li>
     *   <li>上游不接受 {@code system} 角色，因此系统提示词会作为第一条 user 消息下发。</li>
     * </ul>
     *
     * @param prompt 本轮输入
     * @return 消息列表
     */
    private List<Map<String, Object>> buildMessages(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(system)) {
            // 上游没有 system 角色，实测直接传 system 会 400；改成第一条 user 消息更稳
            messages.add(message("user", system));
        }
        List<ChatMessage> source = externalHistory != null ? externalHistory : history;
        for (ChatMessage item : source) {
            if (item == null || StringUtils.isBlank(item.getContent())) {
                continue;
            }
            messages.add(message(item.getRole(), item.getContent()));
        }
        messages.add(message("user", prompt));
        return messages;
    }

    /**
     * 构造单条消息
     *
     * @param role    角色
     * @param content 内容
     * @return 消息
     */
    private Map<String, Object> message(String role, String content) {
        String actualRole = StringUtils.isBlank(role) ? "user" : role;
        Map<String, Object> message = new LinkedHashMap<>();
        if ("assistant".equalsIgnoreCase(actualRole)) {
            message.put("role", "assistant");
            message.put("content", content);
            return message;
        }
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", content);
        message.put("role", "user");
        message.put("content", List.of(text));
        return message;
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
     * 获取浏览器通道
     *
     * @return 通道
     */
    private DuckBrowserTransport transport() {
        DuckBrowserTransport current = transport;
        if (current != null) {
            return current;
        }
        synchronized (transportLock) {
            if (transport != null) {
                return transport;
            }
            String baseUrl = normalizeBaseUrl();
            Map<String, String> headers = setting.getExtraHeaders() == null
                    ? Map.of() : setting.getExtraHeaders();
            boolean headless = !"false".equalsIgnoreCase(
                    control(headers, "x-duck-headless", "duck.headless", "DUCK_HEADLESS"));
            String chrome = control(headers, "x-duck-chrome", "duck.chrome", "DUCK_CHROME");
            String customUa = control(headers, "x-cdp-ua", "chua.browser.userAgent", "CHUA_USER_AGENT");
            if (StringUtils.isBlank(customUa)) {
                customUa = control(headers, "User-Agent", "chua.browser.userAgent", "CHUA_USER_AGENT");
            }
            boolean rotateUa = Boolean.parseBoolean(
                    control(headers, "x-cdp-rotate-ua", "chua.browser.rotateUserAgent", "CHUA_ROTATE_UA"));
            transport = new DuckBrowserTransport(baseUrl, timeoutMillis(), chrome, headless,
                    setting.getProxy(), customUa, rotateUa);
            return transport;
        }
    }

    /**
     * 读取控制项
     *
     * @param headers    额外请求头
     * @param headerName 头名
     * @param property   系统属性
     * @param env        环境变量
     * @return 值；未配置返回 null
     */
    private String control(Map<String, String> headers, String headerName, String property, String env) {
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                    return entry.getValue();
                }
            }
        }
        String value = System.getProperty(property);
        if (StringUtils.isBlank(value)) {
            value = System.getenv(env);
        }
        return value;
    }

    /**
     * 规范化站点地址
     *
     * @return 根地址
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
     * 超时时间
     *
     * @return 毫秒
     */
    private long timeoutMillis() {
        Long configured = setting.getTimeoutMillis();
        return configured != null && configured > 0L ? configured : DEFAULT_TIMEOUT_MILLIS;
    }

    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> models = new ArrayList<>();
        models.add(ModelDefinition.builder().id("gpt-6-luna").name("GPT-6 Luna").provider("duckduckgo").build());
        models.add(ModelDefinition.builder().id("gpt-5.6-terra").name("GPT-5.6 Terra").provider("duckduckgo").build());
        models.add(ModelDefinition.builder().id("claude-sonnet-4-6").name("Claude Sonnet 4.6").provider("duckduckgo").build());
        models.add(ModelDefinition.builder().id("claude-haiku-4-5").name("Claude Haiku 4.5").provider("duckduckgo").build());
        models.add(ModelDefinition.builder().id("mistral-small-2603").name("Mistral Small 2603").provider("duckduckgo").build());
        return models;
    }

    @Override
    public ImageGenerationResult generateImage(String prompt, String ratio, int n,
                                                int width, int height, String quality,
                                                String refImageKey) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("图像生成提示词不能为空");
        }
        int count = Math.max(1, n);
        int targetWidth = width > 0 ? width : suggestedWidth(ratio, height);
        int targetHeight = height > 0 ? height : suggestedHeight(ratio, targetWidth);

        List<ImageGenerationResult.GeneratedImage> images = new ArrayList<>();
        synchronized (transportLock) {
            long startTime = System.currentTimeMillis();
            try {
                List<Map<String, Object>> messages = buildMessages(prompt);
                transport().chat(modelOr(), messages, new DuckBrowserTransport.DuckStreamListener() {
                    @Override
                    public void onText(String delta) {
                        // 图像生成流里也混有解释文本；统一入口不单独暴露
                        if (delta != null && !delta.isEmpty()) {
                            log.debug("[duck] 图像生成流文本片段: {}", delta);
                        }
                    }

                    @Override
                    public void onImage(String b64Image, String imageFormat, int imageWidth, int imageHeight) {
                        if (b64Image == null || b64Image.isEmpty()) {
                            return;
                        }
                        String format = imageFormat == null || imageFormat.isBlank() ? "png" : imageFormat.trim().toLowerCase();
                        String dataUrl = "data:image/" + format + ";base64," + b64Image;
                        images.add(new ImageGenerationResult.GeneratedImage(
                                "duck-" + (images.size() + 1),
                                dataUrl, dataUrl, dataUrl,
                                imageWidth > 0 ? imageWidth : targetWidth,
                                imageHeight > 0 ? imageHeight : targetHeight,
                                format));
                    }
                }, true, targetWidth, targetHeight, count, quality, refImageKey);
            } finally {
                log.info("[duck] 图像生成完成: prompt={} 张={}, 耗时={}ms",
                        summarize(prompt), images.size(), System.currentTimeMillis() - startTime);
            }
        }
        return new ImageGenerationResult(images, prompt);
    }

    /**
     * 推断目标宽度
     *
     * @param ratio  宽高比，可为空
     * @param height 已给高度
     * @return 建议宽度
     */
    private static int suggestedWidth(String ratio, int height) {
        if (height <= 0) {
            return 1024;
        }
        int width = applyRatio(height, ratio, true);
        return width > 0 ? width : 1024;
    }

    /**
     * 推断目标高度
     *
     * @param ratio  宽高比，可为空
     * @param width  已给宽度
     * @return 建议高度
     */
    private static int suggestedHeight(String ratio, int width) {
        if (width <= 0) {
            return 1024;
        }
        int height = applyRatio(width, ratio, false);
        return height > 0 ? height : 1024;
    }

    /**
     * 按宽高比计算另一侧尺寸
     *
     * @param knownSize 已知尺寸
     * @param ratio     宽高比，形如 {@code 16:9}
     * @param forWidth  true 表示计算宽度，false 表示计算高度
     * @return 计算结果；无法解析返回 0
     */
    private static int applyRatio(int knownSize, String ratio, boolean forWidth) {
        if (ratio == null || ratio.isBlank() || knownSize <= 0) {
            return 0;
        }
        int colon = ratio.indexOf(':');
        if (colon <= 0 || colon == ratio.length() - 1) {
            return 0;
        }
        try {
            int a = Integer.parseInt(ratio.substring(0, colon).trim());
            int b = Integer.parseInt(ratio.substring(colon + 1).trim());
            if (a <= 0 || b <= 0) {
                return 0;
            }
            return forWidth ? Math.round((float) knownSize * a / b) : Math.round((float) knownSize * b / a);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 摘要化提示词
     *
     * @param prompt 原始提示词
     * @return 不超过 40 个字符的摘要
     */
    private static String summarize(String prompt) {
        String trimmed = prompt.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 37) + "...";
    }

    @Override
    public void close() {
        synchronized (transportLock) {
            if (transport != null) {
                transport.close();
                transport = null;
            }
        }
    }
}
