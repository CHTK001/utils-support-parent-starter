package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.Attachment;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 豆包（Doubao）大模型对话客户端
 *
 * <p>基于豆包网页版逆向 SSE 接口（{@code /samantha/chat/completion}）的
 * {@link ChatClient} 实现，支持 Cookie 鉴权（appkey 即完整 Cookie 字符串）。
 *
 * <p>支持以下能力：
 * <ul>
 *   <li>流式对话（SSE）</li>
 *   <li>同步对话</li>
 *   <li>多轮对话上下文</li>
 *   <li>三种思考模式：快速（doubao）、思考（doubao-think）、专家（doubao-expert）</li>
 *   <li>HTTP 代理</li>
 * </ul>
 *
 * <p>通过 SPI 机制注册以下别名：
 * <ul>
 *   <li>doubao — 豆包快速模式</li>
 *   <li>doubao-think — 豆包思考模式</li>
 *   <li>doubao-expert — 豆包专家模式</li>
 *   <li>doubao-pro — 豆包快速模式别名</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   // appkey 为完整 Cookie 字符串，例如：
 *   // sessionid=xxx; ttwid=xxx; passport_csrf_token=xxx
 *   ChatClient.create("doubao", "sessionid=xxx; ttwid=xxx; passport_csrf_token=xxx")
 *       .model("doubao")
 *       .system("你是一名助手")
 *       .chatSync("你好");
 * }</pre>
 *
 * @author CH
 * @since 2026/08/10
 */
@Slf4j
@Spi({"doubao", "doubao-think", "doubao-expert", "doubao-pro"})
public class DoubaoChatClient implements ChatClient {

    /**
     * 豆包默认聊天端点
     */
    private static final String DEFAULT_URL = "https://www.doubao.com/samantha/chat/completion";

    /**
     * 思考模式 bot_id（doubao-think）
     */
    private static final String BOT_ID_THINK = "7391857307811151898";

    /**
     * 快速/默认模式 bot_id（doubao）
     */
    private static final String BOT_ID_DEFAULT = "7391857307811151898";

    /**
     * 专家模式 bot_id（doubao-expert）
     */
    private static final String BOT_ID_EXPERT = "7391857307811151898";

    /**
     * SSE 事件分隔符
     */
    private static final String SSE_DATA_PREFIX = "data:";

    /**
     * HTTP 连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 15000;

    /**
     * HTTP 读取超时（毫秒）
     */
    private static final int READ_TIMEOUT_MS = 90000;

    /**
     * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
     * 当前使用的模型名称（doubao / doubao-think / doubao-expert / doubao-pro）
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
     * 构造豆包对话客户端
     *
     * @param setting 客户端配置
     */
    public DoubaoChatClient(ChatClientSetting setting) {
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
        String cookie = setting.getAppKey();
        if (cookie == null || cookie.isBlank()) {
            onError.accept(new IllegalArgumentException("appkey（Cookie）不能为空"));
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage("appkey（Cookie）不能为空")
                    .build());
            return;
        }

        String localSectionId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        String actualModel = model != null ? model : "doubao";
        String botId = resolveBotId(actualModel);

        long startTime = System.currentTimeMillis();
        AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                .model(actualModel)
                .provider("doubao")
                .startTime(startTime);

        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            URL url = new URL(actualBaseUrl);
            String proxyStr = setting.getProxy();
            if (proxyStr != null && !proxyStr.isBlank()) {
                Proxy proxy = resolveProxy(proxyStr);
                if (proxy != null) {
                    connection = (HttpURLConnection) url.openConnection(proxy);
                } else {
                    connection = (HttpURLConnection) url.openConnection();
                }
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Cookie", cookie);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Referer", "https://www.doubao.com/");
            connection.setRequestProperty("Origin", "https://www.doubao.com");

            String requestBody = buildRequestBody(prompt, botId, localSectionId);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorMsg = "豆包接口返回错误码: " + responseCode;
                log.error("[doubao] {}", errorMsg);
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage(errorMsg)
                        .build());
                onError.accept(new IOException(errorMsg));
                return;
            }

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            inputStream = connection.getInputStream();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                parseSseStream(reader, consumer, usageBuilder);
            }

            usageBuilder.durationMillis(System.currentTimeMillis() - startTime);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .usage(usageBuilder.build())
                    .build());
            onComplete.run();

        } catch (Exception e) {
            log.error("豆包对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) {
                    log.debug("关闭输入流失败", e);
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 解析 SSE 流，逐事件回调
     *
     * @param reader        BufferedReader
     * @param consumer      响应回调
     * @param usageBuilder  用量构建器
     * @throws IOException IO 异常
     */
    private void parseSseStream(BufferedReader reader,
                                Consumer<ChatResponse> consumer,
                                AiUsage.AiUsageBuilder usageBuilder) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith(SSE_DATA_PREFIX)) {
                continue;
            }
            String data = line.substring(SSE_DATA_PREFIX.length()).trim();
            if (data.isEmpty()) {
                continue;
            }
            ChatResponse response = parseSseEvent(data);
            if (response != null) {
                consumer.accept(response);
                if (response.getState() == ChatResponse.State.STOP) {
                    break;
                }
            }
        }
    }

    /**
     * 解析单个 SSE 事件数据
     *
     * <p>豆包 SSE 数据格式示例：
     * <pre>{@code
     * {
     *   "event_id": 0,
     *   "content_type": 10001,
     *   "text": "你好",
     *   "block_type": 10000
     * }
     * }</pre>
     *
     * @param data 事件数据字符串
     * @return ChatResponse，无法解析时返回 null
     */
    private ChatResponse parseSseEvent(String data) {
        Map<String, Object> event = JsonMini.parse(data);
        if (event == null || event.isEmpty()) {
            return null;
        }
        Object contentTypeObj = event.get("content_type");
        if (contentTypeObj == null) {
            return null;
        }
        int contentType = ((Number) contentTypeObj).intValue();
        Object textObj = event.get("text");
        String text = textObj != null ? String.valueOf(textObj) : null;

        ChatResponse.ChatResponseBuilder builder = ChatResponse.builder();
        builder.state(ChatResponse.State.STREAMING);
        if (text != null && !text.isEmpty()) {
            switch (contentType) {
                case 10040:
                case 10041:
                    builder.reasoningContent(text);
                    break;
                default:
                    builder.content(text);
                    break;
            }
            return builder.build();
        }
        if (contentType == 10002 || contentType == 20002) {
            return ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .build();
        }
        return null;
    }

    /**
     * 构造豆包请求体 JSON
     *
     * @param prompt       用户输入
     * @param botId        机器人 ID
     * @param sectionId    会话 ID
     * @return JSON 字符串
     */
    private String buildRequestBody(String prompt, String botId, String sectionId) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"bot_id\":\"").append(botId).append("\",");
        json.append("\"conversation_id\":\"").append(sectionId).append("\",");
        json.append("\"section_id\":\"").append(sectionId).append("\",");
        json.append("\"message\":{");
        json.append("\"text\":\"").append(JsonMini.escape(prompt)).append("\",");
        json.append("\"content_type\":1");
        json.append("},");
        json.append("\"stream\":true,");
        json.append("\"incremental\":true,");
        json.append("\"need_deep_think\":").append(resolveDeepThink()).append(',');
        if (temperature != null) {
            json.append("\"temperature\":").append(temperature).append(',');
        }
        if (maxTokens != null) {
            json.append("\"max_tokens\":").append(maxTokens).append(',');
        }
        if (system != null && !system.isEmpty()) {
            json.append("\"system_prompt\":\"").append(JsonMini.escape(system)).append("\",");
        }
        json.append("\"history\":");
        json.append(buildHistoryJson());
        json.append('}');
        return json.toString();
    }

    /**
     * 构造对话历史 JSON 数组
     *
     * @return JSON 数组字符串
     */
    private String buildHistoryJson() {
        StringBuilder json = new StringBuilder("[");
        List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
        boolean first = true;
        for (ChatMessage msg : messages) {
            if (msg.getContent() == null || msg.getContent().isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            json.append("\"role\":\"").append(msg.getRole()).append("\",");
            json.append("\"text\":\"").append(JsonMini.escape(msg.getContent())).append("\"");
            json.append('}');
        }
        json.append(']');
        return json.toString();
    }

    /**
     * 根据模型名称解析 bot_id
     *
     * @param modelName 模型名称
     * @return bot_id
     */
    private static String resolveBotId(String modelName) {
        if (modelName == null) {
            return BOT_ID_DEFAULT;
        }
        if (modelName.contains("expert")) {
            return BOT_ID_EXPERT;
        }
        if (modelName.contains("think")) {
            return BOT_ID_THINK;
        }
        return BOT_ID_DEFAULT;
    }

    /**
     * 根据模型名称解析思考模式参数
     *
     * <p>0 — 快速模式，1 — 思考模式，3 — 专家模式
     *
     * @param modelName 模型名称
     * @return 思考模式值
     */
    private int resolveDeepThink() {
        if (model == null) {
            return 0;
        }
        if (model.contains("expert")) {
            return 3;
        }
        if (model.contains("think")) {
            return 1;
        }
        return 0;
    }

    /**
     * 规范化 API 基础地址
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

    @Override
    public void close() {
    }

    /**
     * 极简 JSON 解析与转义工具，避免引入额外 JSON 依赖
     */
    static final class JsonMini {

        /**
         * 转义 JSON 字符串中的特殊字符
         *
         * @param s 原始字符串
         * @return 转义后字符串
         */
        static String escape(String s) {
            if (s == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                        break;
                }
            }
            return sb.toString();
        }

        /**
         * 极简 JSON 解析（仅支持扁平对象）
         *
         * @param json JSON 字符串
         * @return 解析后的 Map，解析失败时返回 null
         */
        static Map<String, Object> parse(String json) {
            Map<String, Object> result = new HashMap<>();
            if (json == null || json.isEmpty()) {
                return result;
            }
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                return result;
            }
            int i = 1;
            int len = json.length() - 1;
            while (i < len) {
                while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == ',')) {
                    i++;
                }
                if (i >= len) {
                    break;
                }
                if (json.charAt(i) != '"') {
                    break;
                }
                int keyStart = ++i;
                while (i < len && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (i >= len) {
                    break;
                }
                String key = unescape(json.substring(keyStart, i));
                i++;
                while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == ':')) {
                    i++;
                }
                if (i >= len) {
                    break;
                }
                Object value = parseValue(json, i);
                if (value == null) {
                    break;
                }
                i = (Integer) ((Object[]) value)[1];
                result.put(key, ((Object[]) value)[0]);
            }
            return result;
        }

        /**
         * 解析 JSON 值
         *
         * @param json 完整 JSON 字符串
         * @param start 起始位置
         * @return Object[] {值, 结束位置下一字符索引}
         */
        private static Object[] parseValue(String json, int start) {
            int len = json.length();
            int i = start;
            while (i < len && json.charAt(i) == ' ') {
                i++;
            }
            if (i >= len) {
                return null;
            }
            char c = json.charAt(i);
            if (c == '"') {
                int strStart = ++i;
                StringBuilder sb = new StringBuilder();
                while (i < len && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\' && i + 1 < len) {
                        char next = json.charAt(i + 1);
                        switch (next) {
                            case '"':
                                sb.append('"');
                                break;
                            case '\\':
                                sb.append('\\');
                                break;
                            case 'n':
                                sb.append('\n');
                                break;
                            case 'r':
                                sb.append('\r');
                                break;
                            case 't':
                                sb.append('\t');
                                break;
                            case 'u':
                                if (i + 5 < len) {
                                    sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                    i += 4;
                                }
                                break;
                            default:
                                sb.append(next);
                                break;
                        }
                        i += 2;
                    } else {
                        sb.append(json.charAt(i));
                        i++;
                    }
                }
                return new Object[]{sb.toString(), i + 1};
            } else if (c == '{' || c == '[') {
                char open = c;
                char close = open == '{' ? '}' : ']';
                int depth = 1;
                int valStart = i + 1;
                i++;
                while (i < len && depth > 0) {
                    if (json.charAt(i) == open) {
                        depth++;
                    } else if (json.charAt(i) == close) {
                        depth--;
                    } else if (json.charAt(i) == '"') {
                        i++;
                        while (i < len && json.charAt(i) != '"') {
                            if (json.charAt(i) == '\\') {
                                i += 2;
                            } else {
                                i++;
                            }
                        }
                    }
                    i++;
                }
                String raw = json.substring(valStart, i - 1);
                if (open == '{') {
                    return new Object[]{parse(raw), i};
                } else {
                    return new Object[]{raw, i};
                }
            } else if (c == 't' || c == 'f') {
                if (json.startsWith("true", i)) {
                    return new Object[]{Boolean.TRUE, i + 4};
                }
                if (json.startsWith("false", i)) {
                    return new Object[]{Boolean.FALSE, i + 5};
                }
                return null;
            } else if (c == 'n') {
                if (json.startsWith("null", i)) {
                    return new Object[]{null, i + 4};
                }
                return null;
            } else {
                int valStart = i;
                while (i < len && json.charAt(i) != ',' && json.charAt(i) != '}' && json.charAt(i) != ' ') {
                    i++;
                }
                String num = json.substring(valStart, i).trim();
                if (num.contains(".")) {
                    try {
                        return new Object[]{Double.parseDouble(num), i};
                    } catch (NumberFormatException e) {
                        return new Object[]{num, i};
                    }
                } else {
                    try {
                        return new Object[]{Long.parseLong(num), i};
                    } catch (NumberFormatException e) {
                        return new Object[]{num, i};
                    }
                }
            }
        }

        /**
         * 反转义 JSON 字符串
         *
         * @param s 原始字符串（不含外层引号）
         * @return 反转义后字符串
         */
        private static String unescape(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    switch (next) {
                        case '"':
                            sb.append('"');
                            i++;
                            break;
                        case '\\':
                            sb.append('\\');
                            i++;
                            break;
                        case 'n':
                            sb.append('\n');
                            i++;
                            break;
                        case 'r':
                            sb.append('\r');
                            i++;
                            break;
                        case 't':
                            sb.append('\t');
                            i++;
                            break;
                        default:
                            sb.append(c);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
