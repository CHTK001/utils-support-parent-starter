package com.chua.jitllm.support.llama3.translator;

import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.deeplearning.support.engine.DetectionConfigurable;
import com.chua.deeplearning.support.translator.ConversationTranslator;
import com.chua.deeplearning.support.translator.ITranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * llama-server 独立进程（sidecar）对话翻译器。
 *
 * <p>与 {@link Llama3ChatTranslator}（de.kherud JNI 进程内加载 GGUF）不同，本翻译器
 * 不在 JVM 内加载模型，而是调用外部 {@code llama-server}（llama.cpp ≥ v0.8.0/b11960）
 * 的 OpenAI 兼容接口 {@code POST /v1/chat/completions}。chat template 由 llama-server
 * 按 GGUF 元数据自动应用，本类只负责请求转发、多轮消息透传（{@link ConversationTranslator}）、采样参数注入与 {@code &lt;think&gt;}/{@code &lt;/think&gt;} 思考段剥离。</p>
 *
 * <p>适用于 llama.cpp 内置绑定版本过旧、无法进程内加载新架构模型（如 LFM3）的场景。</p>
 *
 * <p>部署顺序（sidecar 由外部脚本管理，Java 侧不自动拉起）：</p>
 * <ol>
 *   <li>准备 GGUF：手动放到注册路径（如 {@code ../llama3/LFM3-1.7B-Q4_K_M.gguf}），
 *       或在 Java 侧调用 {@code ModelRegistry.ensureDownloaded("lfm3-1.7b")} 自动下载</li>
 *   <li>启动 sidecar：{@code llama-server -m <gguf路径> --host 127.0.0.1 --port 9931 --ctx-size 8192 --threads N}</li>
 *   <li>Java 调用：{@code ChatClient.create("gpu-llama3", "EMPTY", "EMPTY").model("lfm3-1.7b").chatSync("...")}</li>
 * </ol>
 *
 * <p>支持运行时参数注入（{@link DetectionConfigurable#configure(Map)} / Map 构造器）：</p>
 * <ul>
 *   <li>{@code baseUrl} - String：llama-server 地址，默认 {@code http://127.0.0.1:9931}；
 *       也可用系统属性 {@code llama.server.url} 覆盖</li>
 *   <li>{@code temperature} - Double：采样温度（LFM3 推荐 0.1）</li>
 *   <li>{@code topK} - Integer：Top-K 采样（LFM3 推荐 50）</li>
 *   <li>{@code topP} - Double：Top-P 采样</li>
 *   <li>{@code nPredict} - Integer：最大输出 token 数（等价 max_tokens）</li>
 *   <li>{@code timeoutMillis} - Long：单次推理读超时（CPU 推理较慢，默认 300000）</li>
 * </ul>
 *
 * <p>{@code device / gpuLayers / ctxSize / threads} 等 sidecar 启动参数不在此注入，
 * 需在启动 {@code llama-server} 时指定。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class LlamaServerChatTranslator implements ITranslator<String, String>, ConversationTranslator, DetectionConfigurable, AutoCloseable {

    /**
     * 系统属性：覆盖 llama-server 地址
     */
    public static final String PROP_BASE_URL = "llama.server.url";
    /**
     * 默认 llama-server 地址（OpenAI 兼容端口）
     */
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:9931";
    /**
     * 默认单次推理读超时（毫秒）：CPU 上 1.7B Q4 生成数百 token 可能需要数十秒
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 300_000L;
    /**
     * LFM3 等 qwen3 风格模型的思考开标记
     */
    private static final String THINK_OPEN = String.valueOf((char) 60) + "think" + (char) 62;
    /**
     * 思考闭标记
     */
    private static final String THINK_CLOSE = String.valueOf((char) 60) + "/think" + (char) 62;
    /**
     * JSON
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 模型标识（同时作为 OpenAI 请求的 model 字段；llama-server 单模型部署时仅作标识）
     */
    private final String modelId;

    /**
     * llama-server 地址（可被 configure / 系统属性覆盖）
     */
    private volatile String baseUrl = DEFAULT_BASE_URL;
    /**
     * 采样温度
     */
    private volatile Double temperature;
    /**
     * Top-K
     */
    private volatile Integer topK;
    /**
     * Top-P
     */
    private volatile Double topP;
    /**
     * 最大输出 token 数
     */
    private volatile Integer maxTokens;
    /**
     * 单次推理读超时（毫秒）
     */
    private volatile long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    /**
     * 框架注入的模型文件路径（sidecar 模式下 Java 不加载权重，仅记录供排障/启动脚本参考）
     */
    private volatile String modelPath;

    /**
     * 默认构造器，使用 lfm3-1.7b 模型。
     */
    public LlamaServerChatTranslator() {
        this("lfm3-1.7b");
    }

    /**
     * 构造器。
     *
     * @param modelId 模型标识
     */
    public LlamaServerChatTranslator(String modelId) {
        this.modelId = modelId;
    }

    /**
     * 带运行参数构造器（供 ModelRegistry 按参数实例化）。
     *
     * @param options 运行参数
     */
    public LlamaServerChatTranslator(Map<String, Object> options) {
        this();
        configure(options);
    }

    @Override
    public String name() {
        return modelId;
    }

    /**
     * 注入运行参数（实例化前后均可生效，HTTP 无状态、逐请求应用）。
     *
     * @param options 参数键值对，键约定见类注释
     */
    @Override
    public void configure(Map<String, Object> options) {
        if (CollectionUtils.isEmpty(options)) {
            return;
        }
        Object v = options.get("baseUrl");
        if (v != null && !v.toString().isBlank()) {
            this.baseUrl = v.toString().trim();
        }
        v = options.get("temperature");
        if (v instanceof Number n) {
            this.temperature = n.doubleValue();
        }
        v = options.get("topK");
        if (v instanceof Number n) {
            this.topK = n.intValue();
        }
        v = options.get("topP");
        if (v instanceof Number n) {
            this.topP = n.doubleValue();
        }
        v = options.get("nPredict");
        if (v instanceof Number n) {
            this.maxTokens = n.intValue();
        }
        v = options.get("timeoutMillis");
        if (v instanceof Number n && n.longValue() > 0) {
            this.timeoutMillis = n.longValue();
        }
        log.info("[LlamaServer:{}] 参数注入完成: {}", modelId, options);
    }

    /**
     * 框架反射注入模型路径（{@code setModelPath(String)}）。
     *
     * @param modelPath 注册表中解析出的模型文件路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
        log.debug("[LlamaServer:{}] 模型文件路径（供 sidecar 启动参考）: {}", modelId, modelPath);
    }

    @Override
    public String translate(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return translate(List.of(ChatMessage.builder().role("user").content(input).build()));
    }

    /**
     * 多轮对话翻译：完整消息列表（system/历史/本轮输入）透传给 llama-server，
     * chat template 由服务端按 GGUF 元数据应用。
     *
     * @param messages 对话消息列表，按时间正序
     * @return 回答文本
     */
    @Override
    public String translate(List<ChatMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return "";
        }
        String url = resolveBaseUrl() + "/v1/chat/completions";
        try {
            byte[] body = buildRequestBody(messages);
            HttpURLConnection conn = openConnection(url, body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            String response = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("[LlamaServer:" + modelId + "] llama-server 响应异常 HTTP " + code
                        + ": " + abbreviate(response));
            }
            return extractContent(response);
        } catch (java.net.ConnectException e) {
            throw new IllegalStateException("[LlamaServer:" + modelId + "] 无法连接 llama-server(" + url
                    + ")，请先启动 sidecar 进程并确认端口", e);
        } catch (IOException e) {
            throw new IllegalStateException("[LlamaServer:" + modelId + "] 调用 llama-server 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // HTTP 无状态，无需释放资源
    }

    /**
     * 构造 OpenAI 兼容对话请求体。
     *
     * @param conversation 对话消息列表
     * @return JSON 字节
     * @throws IOException 序列化失败
     */
    private byte[] buildRequestBody(List<ChatMessage> conversation) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", modelId);
        root.put("stream", false);
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage chatMessage : conversation) {
            if (chatMessage == null || chatMessage.getContent() == null || chatMessage.getContent().isBlank()) {
                continue;
            }
            ObjectNode message = messages.addObject();
            message.put("role", chatMessage.getRole() == null || chatMessage.getRole().isBlank()
                    ? "user" : chatMessage.getRole());
            message.put("content", chatMessage.getContent());
        }
        if (temperature != null) {
            root.put("temperature", temperature);
        }
        if (topK != null) {
            root.put("top_k", topK);
        }
        if (topP != null) {
            root.put("top_p", topP);
        }
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        return MAPPER.writeValueAsBytes(root);
    }

    /**
     * 建立 POST 连接。
     *
     * @param url        目标地址
     * @param bodyLength 请求体长度
     * @return 连接
     * @throws IOException 打开失败
     */
    private HttpURLConnection openConnection(String url, int bodyLength) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setFixedLengthStreamingMode(bodyLength);
        return conn;
    }

    /**
     * 从 OpenAI 兼容响应中提取回答文本，并剥离思考段。
     *
     * @param response 响应 JSON
     * @return 回答文本
     * @throws IOException 解析失败
     */
    private String extractContent(String response) throws IOException {
        JsonNode root = MAPPER.readTree(response);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String text = content.isTextual() ? content.asText() : root.path("content").asText("");
        return stripThinking(text);
    }

    /**
     * 剥离 qwen3 风格思考段。
     *
     * <p>成对 {@code &lt;think&gt;}...{@code &lt;/think&gt;} 直接删除；仅剩未闭合开标记时保留其后内容
     * （其后为空则回退保留其前内容）。</p>
     *
     * @param text 原始输出
     * @return 剥离后的回答
     */
    private static String stripThinking(String text) {
        if (StringUtils.isEmpty(text)) {
            return "";
        }
        String out = text.replaceAll("(?s)" + java.util.regex.Pattern.quote(THINK_OPEN)
                + ".*?" + java.util.regex.Pattern.quote(THINK_CLOSE), "");
        int open = out.indexOf(THINK_OPEN);
        if (open >= 0) {
            String before = out.substring(0, open);
            String after = out.substring(open + THINK_OPEN.length());
            out = !after.isBlank() ? after : before;
        }
        return out.trim();
    }

    /**
     * 解析 llama-server 地址：系统属性 {@code llama.server.url} 优先，其次 configure 注入值。
     *
     * @return 去除结尾斜杠的地址
     */
    private String resolveBaseUrl() {
        String prop = System.getProperty(PROP_BASE_URL);
        String base = prop != null && !prop.isBlank() ? prop.trim()
                : (baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * 读取响应流为 UTF-8 文本。
     *
     * @param in 响应流（可为空）
     * @return 文本
     * @throws IOException 读取失败
     */
    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 截断过长文本用于异常信息。
     *
     * @param text 原文本
     * @return 最多 500 字符
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
