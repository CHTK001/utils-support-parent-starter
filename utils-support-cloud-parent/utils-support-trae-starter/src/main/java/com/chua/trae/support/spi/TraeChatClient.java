package com.chua.trae.support.spi;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatClientType;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.generation.ImageGenerationResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.trae.support.ModelConfigLoader;
import com.chua.trae.support.auth.AuthManager;
import com.chua.trae.support.http.TraeHttpClient;
import com.chua.trae.support.model.ChatRequest;
import com.chua.trae.support.model.ModelConfig;
import com.chua.trae.support.model.ModelConfig.ModelEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Trae 对话客户端 —— 把 Trae IDE 的本地登录态（{@code storage.json} / 手动 JWT）
 * 包装成统一的 {@link ChatClient} SPI 实现。
 *
 * <h2>能力</h2>
 * <ol>
 *   <li><b>对话</b>：走 {@code /api/agent/v3/llm_utils_chat} 的 SSE 流，
 *       由 {@code com.chua.trae.support.ChatClient} 负责协议解析、模型分档降级与限流重试；</li>
 *   <li><b>文生图</b>：走 {@code /api/ide/v1/tool_text_to_image} 同步接口，
 *       再用 {@code /api/ide/v1/get_resource_url} 换签名下载地址（协议事实源自
 *       {@code dsh-connect-trae} 的 {@code image-gen-trae.ts} 活体探测结论）；</li>
 *   <li><b>模型清单</b>：直接读模块内置的 {@code model-config.json}，不触达上游、不消耗积分。</li>
 * </ol>
 *
 * <h2>登录态来源（按优先级）</h2>
 * <ol>
 *   <li>{@link ChatClientSetting#getAppKey()} 中填写的 JWT（{@code eyJ} 开头）；</li>
 *   <li>环境变量 {@code TRAE_DATA_DIR} 指向的 Trae 数据根目录；</li>
 *   <li>Windows 默认安装位置：{@code %APPDATA%\Trae CN}（国内版）、{@code %APPDATA%\Trae}（国际版）。</li>
 * </ol>
 *
 * <p>模型清单在缺少登录态时仍可读取；只有真正发起对话 / 生图时才会因缺少登录态报错，
 * 且错误信息明确指向「怎么补登录态」，便于渠道管理页直接展示。</p>
 *
 * <h2>SPI 别名</h2>
 * <p>{@code trae}（国内版，默认）、{@code trae-cn}、{@code trae-global}（国际版网关）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi({"trae", "trae-cn", "trae-global"})
public class TraeChatClient implements ChatClient {

    /**
     * Trae 国内版对话网关
     */
    private static final String CN_HOST = "https://trae-api-cn.mchost.guru";

    /**
     * Trae 国际版对话网关
     */
    private static final String GLOBAL_HOST = "https://coresg-normal.trae.ai";

    /**
     * 缺省模型（与模块内置模型配置的旗舰档一致）
     */
    private static final String DEFAULT_MODEL = "glm-5.2";

    /**
     * 文生图（同步）端点
     */
    private static final String TEXT_TO_IMAGE_PATH = "/api/ide/v1/tool_text_to_image";

    /**
     * 图片 uri 换签名下载地址端点
     */
    private static final String RESOURCE_URL_PATH = "/api/ide/v1/get_resource_url";

    /**
     * 服务端缺省出图尺寸（实测 uri 后缀即为 1920x1920）
     */
    private static final String DEFAULT_IMAGE_SIZE = "1920x1920";

    /**
     * Ark 侧接受的图片总像素下限（512x512、1024x1024 会被拒绝）
     */
    private static final int IMAGE_MIN_PIXELS = 3_686_400;

    /**
     * Ark 侧接受的图片总像素上限
     */
    private static final int IMAGE_MAX_PIXELS = 16_777_216;

    /**
     * JWT 前缀，用于区分「手动令牌」与普通密钥
     */
    private static final String JWT_PREFIX = "eyJ";

    /**
     * Trae 数据根目录环境变量（便于测试与非默认安装位）
     */
    private static final String DATA_DIR_ENV = "TRAE_DATA_DIR";

    /**
     * JSON 解析器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 宽高比 → 出图尺寸（总像素必须落在 Ark 的合法区间内，否则上游直接拒绝）
     */
    private static final Map<String, String> RATIO_SIZES = Map.of(
            "1:1", "1920x1920",
            "16:9", "2560x1440",
            "9:16", "1440x2560",
            "4:3", "2304x1728",
            "3:4", "1728x2304",
            "3:2", "2400x1600",
            "2:3", "1600x2400");

    /**
     * 客户端配置
     */
    private final ChatClientSetting setting;

    /**
     * SPI 别名（trae / trae-cn / trae-global）
     */
    private final String provider;

    /**
     * 版本：cn（国内）/ sg（国际）
     */
    private final String edition;

    /**
     * 对话网关地址
     */
    private final String apiHost;

    /**
     * 当前模型
     */
    private String model;

    /**
     * 系统提示词
     */
    private String system;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * 最大输出 Token
     */
    private Integer maxTokens;

    /**
     * 用户历史（仅保留文本）
     */
    private final List<ChatMessage> userHistory = new ArrayList<>();

    /**
     * 助手历史（仅保留文本）
     */
    private final List<ChatMessage> assistantHistory = new ArrayList<>();

    /**
     * 外部注入的完整历史
     */
    private List<ChatMessage> externalHistory;

    /**
     * 底层 Trae 客户端（懒加载）
     */
    private volatile com.chua.trae.support.ChatClient delegate;

    /**
     * 认证管理器（懒加载，生图复用）
     */
    private volatile AuthManager authManager;

    /**
     * HTTP 客户端（懒加载，生图复用）
     */
    private volatile TraeHttpClient httpClient;

    /**
     * 创建 Trae 对话客户端。
     *
     * <p>构造阶段只解析配置，不读取登录态、不建连；登录态在首次对话 / 生图时才解析，
     * 因此渠道管理页拉取模型清单不会因为「本机没登录」而失败。</p>
     *
     * @param setting 客户端配置，可为空（按默认 CN 网关 + 默认模型工作）
     */
    public TraeChatClient(ChatClientSetting setting) {
        this.setting = setting == null ? ChatClientSetting.builder().provider("trae").build() : setting;
        this.provider = StringUtils.isEmpty(this.setting.getProvider()) ? "trae" : this.setting.getProvider();
        this.edition = this.provider.toLowerCase().contains("global") ? "sg" : "cn";
        this.apiHost = resolveApiHost(this.setting.getBaseUrl());
        this.model = StringUtils.isEmpty(this.setting.getModel()) ? DEFAULT_MODEL : this.setting.getModel();
        this.temperature = this.setting.getTemperature();
        this.maxTokens = this.setting.getMaxTokens();
        this.system = this.setting.getSystem();
    }

    /**
     * 同步对话，收集完整 SSE 流后返回文本。
     *
     * @param prompt 用户输入
     * @return 完整回答文本
     */
    @Override
    public String chatSync(String prompt) {
        com.chua.trae.support.model.ChatResponse response;
        try {
            response = delegate().chat(buildRequest(prompt)).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Trae 对话失败：" + cause.getMessage(), cause);
        }
        return textOf(response);
    }

    /**
     * 流式对话，按 SSE 片段回调。
     *
     * @param prompt     用户输入
     * @param consumer   流式响应回调
     * @param onComplete 完成回调
     * @param onError    错误回调
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        try {
            delegate().chatStream(buildRequest(prompt),
                    text -> consumer.accept(ChatResponse.builder()
                            .state(ChatResponse.State.STREAMING)
                            .content(text)
                            .build()),
                    reasoning -> {
                        // 推理片段不单独推送：统一在完成事件里返回完整文本，避免前端拼接错位
                    },
                    response -> {
                        consumer.accept(ChatResponse.builder()
                                .state(ChatResponse.State.STOP)
                                .content(textOf(response))
                                .build());
                        onComplete.run();
                    },
                    error -> {
                        consumer.accept(ChatResponse.builder()
                                .state(ChatResponse.State.ERROR)
                                .errorMessage(error.getMessage())
                                .build());
                        onError.accept(error);
                    });
        } catch (Throwable e) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 内置模型清单（来自模块 resource 的 model-config.json）。
     *
     * @return 模型定义列表
     */
    @Override
    public List<ModelDefinition> models() {
        Optional<ModelConfig> optional = ModelConfigLoader.loadDefault();
        if (optional.isEmpty() || optional.get().models() == null) {
            return List.of();
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (Map.Entry<String, ModelEntry> entry : optional.get().models().entrySet()) {
            ModelEntry value = entry.getValue();
            List<String> capabilities = new ArrayList<>(3);
            capabilities.add("chat");
            if (value == null) {
                result.add(ModelDefinition.builder()
                        .id(entry.getKey()).name(entry.getKey()).provider(provider)
                        .capabilities(capabilities)
                        .build());
                continue;
            }
            if (Boolean.TRUE.equals(value.reasoning())) {
                capabilities.add("reasoning");
            }
            if (Boolean.TRUE.equals(value.toolcallCompatible())) {
                capabilities.add("tool-call");
            }
            if (Boolean.TRUE.equals(value.multimodal())) {
                capabilities.add("vision");
            }
            result.add(ModelDefinition.builder()
                    .id(entry.getKey())
                    .name(entry.getKey())
                    .provider(provider)
                    .description(value.category())
                    .capabilities(capabilities)
                    .build());
        }
        return result;
    }

    /**
     * 文生图。
     *
     * <p>协议分两步：先 {@code tool_text_to_image} 拿 {@code uri}（TOS 对象 key），
     * 再 {@code get_resource_url} 换签名下载地址；签名地址可直接当图片 URL 使用。
     * 每次调用都会真实计费，{@code n} 大于 1 时按次数循环（单张 ≈ 8.8 积分）。</p>
     *
     * @param prompt  图像描述
     * @param ratio   宽高比（如 {@code 1:1}、{@code 16:9}），缺省 1:1
     * @param n       生成数量，至少 1
     * @param width   输出宽度（与 height 同时给定时优先于 ratio）
     * @param height  输出高度
     * @param quality 质量参数，Trae 不使用
     * @param refImageKey 参考图，Trae 文生图不使用
     * @return 图像生成结果
     */
    @Override
    public ImageGenerationResult generateImage(String prompt, String ratio, int n,
                                               int width, int height, String quality,
                                               String refImageKey) {
        if (StringUtils.isEmpty(prompt)) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        String imageSize = resolveImageSize(ratio, width, height);
        int count = Math.max(1, n);
        List<ImageGenerationResult.GeneratedImage> images = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String uri = requestImage(prompt, imageSize);
            String signedUrl = signedResourceUrl(uri);
            int[] size = parseSize(imageSize);
            images.add(new ImageGenerationResult.GeneratedImage(
                    uri, signedUrl, signedUrl, signedUrl, size[0], size[1], "jpeg"));
        }
        return new ImageGenerationResult(images, prompt);
    }

    /**
     * 当前模型。
     *
     * @return 模型标识
     */
    @Override
    public String getModel() {
        return model;
    }

    /**
     * 客户端配置。
     *
     * @return 配置对象
     */
    @Override
    public ChatClientSetting getSetting() {
        return setting;
    }

    /**
     * Trae 使用服务商原生协议（非 OpenAI 兼容）。
     *
     * @return {@link ChatClientType#PROVIDER_NATIVE}
     */
    @Override
    public ChatClientType type() {
        return ChatClientType.PROVIDER_NATIVE;
    }

    /**
     * 切换模型。
     *
     * @param model 模型标识
     * @return 当前客户端
     */
    @Override
    public ChatClient model(String model) {
        if (!StringUtils.isEmpty(model)) {
            this.model = model;
        }
        return this;
    }

    /**
     * 设置系统提示词。
     *
     * @param system 系统提示词
     * @return 当前客户端
     */
    @Override
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    /**
     * 设置温度。
     *
     * @param temperature 温度值
     * @return 当前客户端
     */
    @Override
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    /**
     * 设置最大输出 Token。
     *
     * @param maxTokens 上限
     * @return 当前客户端
     */
    @Override
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    /**
     * 追加用户历史。
     *
     * @param content 内容
     * @return 当前客户端
     */
    @Override
    public ChatClient addUserHistory(String content) {
        if (!StringUtils.isEmpty(content)) {
            userHistory.add(ChatMessage.builder().role("user").content(content).build());
        }
        return this;
    }

    /**
     * 追加助手历史。
     *
     * @param content 内容
     * @return 当前客户端
     */
    @Override
    public ChatClient addAssistantHistory(String content) {
        if (!StringUtils.isEmpty(content)) {
            assistantHistory.add(ChatMessage.builder().role("assistant").content(content).build());
        }
        return this;
    }

    /**
     * 覆盖完整历史。
     *
     * @param messages 历史消息
     * @return 当前客户端
     */
    @Override
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    /**
     * 开启新会话（清空本地历史）。
     *
     * @return 当前客户端
     */
    @Override
    public ChatClient newChat() {
        userHistory.clear();
        assistantHistory.clear();
        externalHistory = null;
        return this;
    }

    /**
     * 释放底层客户端（SSE 重试调度器与 HTTP 连接池）。
     */
    @Override
    public void close() {
        com.chua.trae.support.ChatClient client = this.delegate;
        if (client != null) {
            client.close();
        }
        TraeHttpClient http = this.httpClient;
        if (http != null) {
            http.rawClient().dispatcher().executorService().shutdown();
            http.rawClient().connectionPool().evictAll();
        }
    }

    /**
     * 组装一次对话请求（system 并入首条用户消息，与 Trae 网关的消息契约对齐）。
     *
     * @param prompt 本次用户输入
     * @return 请求对象
     */
    private ChatRequest buildRequest(String prompt) {
        List<com.chua.trae.support.model.ChatMessage> messages = new ArrayList<>();
        if (externalHistory != null && !externalHistory.isEmpty()) {
            for (ChatMessage message : externalHistory) {
                if (message == null || StringUtils.isEmpty(message.getContent())) {
                    continue;
                }
                messages.add(wrap(message.getRole(), message.getContent()));
            }
        } else {
            for (ChatMessage message : userHistory) {
                messages.add(wrap(message.getRole(), message.getContent()));
            }
            for (ChatMessage message : assistantHistory) {
                messages.add(wrap(message.getRole(), message.getContent()));
            }
        }
        if (!StringUtils.isEmpty(system)) {
            // Trae 上游不区分 system 角色：把系统指令前置到本轮用户消息里最稳妥
            messages.add(wrap("user", "【系统指令】\n" + system));
        }
        messages.add(wrap("user", prompt));
        return ChatRequest.builder()
                .model(model)
                .messages(messages)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * 把常见消息包装成 Trae 消息对象。
     *
     * @param role    角色：user / assistant
     * @param content 文本内容
     * @return Trae 消息
     */
    private static com.chua.trae.support.model.ChatMessage wrap(String role, String content) {
        if ("assistant".equalsIgnoreCase(role)) {
            return new ChatRequest.AssistantMessage(content);
        }
        return new ChatRequest.UserMessage(content);
    }

    /**
     * 取出响应文本。
     *
     * @param response Trae 响应
     * @return 文本；无内容时返回空串
     */
    private static String textOf(com.chua.trae.support.model.ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return "";
        }
        com.chua.trae.support.model.ChatResponse.Choice choice = response.choices().getFirst();
        if (choice == null || choice.message() == null || choice.message().content() == null) {
            return "";
        }
        return choice.message().content();
    }

    /**
     * 懒加载底层 Trae 客户端。
     *
     * @return 底层客户端
     * @throws IllegalStateException 既没有手动 JWT、也没找到本机 Trae 登录态时抛出
     */
    private com.chua.trae.support.ChatClient delegate() {
        com.chua.trae.support.ChatClient client = this.delegate;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (this.delegate != null) {
                return this.delegate;
            }
            String token = resolveManualToken();
            String dataDir = resolveDataDir();
            if (token == null && dataDir == null) {
                throw new IllegalStateException("未检测到 Trae 登录态：请在渠道配置里填写 JWT token，"
                        + "或先在本机登录 Trae IDE（支持环境变量 " + DATA_DIR_ENV + " 指定数据目录）");
            }
            AuthManager auth = new AuthManager(edition, dataDir, token, apiHost);
            TraeHttpClient http = new TraeHttpClient.Builder()
                    .authManager(auth)
                    .apiHost(apiHost)
                    .build();
            ModelConfig config = ModelConfigLoader.loadDefault().orElse(null);
            this.authManager = auth;
            this.httpClient = http;
            this.delegate = com.chua.trae.support.ChatClient.builder()
                    .authManager(auth)
                    .httpClient(http)
                    .modelConfig(config)
                    .defaultModel(model)
                    .build();
            return this.delegate;
        }
    }

    /**
     * 解析网关地址：显式 baseUrl 优先，其次按 provider 选国内 / 国际网关。
     *
     * @param baseUrl 配置里的 baseUrl，可为空
     * @return 网关地址
     */
    private String resolveApiHost(String baseUrl) {
        if (!StringUtils.isEmpty(baseUrl)) {
            return baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
        }
        return "sg".equals(edition) ? GLOBAL_HOST : CN_HOST;
    }

    /**
     * 取手动 JWT 令牌。
     *
     * @return JWT；未配置或格式不符时返回 {@code null}
     */
    private String resolveManualToken() {
        String appKey = setting.getAppKey();
        return appKey != null && appKey.startsWith(JWT_PREFIX) ? appKey.trim() : null;
    }

    /**
     * 探测本机 Trae 数据根目录（storage.json 的上级目录）。
     *
     * <p>注意 {@link AuthManager} 的 {@code dataDir} 语义是「应用数据根目录」，
     * 它内部会再拼 {@code User/globalStorage/storage.json}，因此这里只返回根目录，
     * 不能直接给 globalStorage 路径（否则会拼成两段 User）。</p>
     *
     * @return 数据根目录；找不到时返回 {@code null}
     */
    private String resolveDataDir() {
        List<Path> candidates = new ArrayList<>(3);
        String env = System.getenv(DATA_DIR_ENV);
        if (!StringUtils.isEmpty(env)) {
            candidates.add(Path.of(env));
        }
        String appData = System.getenv("APPDATA");
        if (!StringUtils.isEmpty(appData)) {
            candidates.add(Path.of(appData, "Trae CN"));
            candidates.add(Path.of(appData, "Trae"));
        }
        String home = System.getProperty("user.home");
        if (!StringUtils.isEmpty(home)) {
            candidates.add(Path.of(home, "AppData", "Roaming", "Trae CN"));
            candidates.add(Path.of(home, "AppData", "Roaming", "Trae"));
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("User").resolve("globalStorage").resolve("storage.json"))) {
                return candidate.toString();
            }
        }
        return null;
    }

    /**
     * 计算出图尺寸字符串（{@code WxH}）。
     *
     * @param ratio  宽高比，可为空
     * @param width  宽度，可为 0
     * @param height 高度，可为 0
     * @return 合法尺寸
     * @throws IllegalArgumentException 像素总量不在 Ark 允许区间时抛出
     */
    private static String resolveImageSize(String ratio, int width, int height) {
        String size;
        if (width > 0 && height > 0) {
            size = width + "x" + height;
        } else if (ratio != null && RATIO_SIZES.containsKey(ratio.trim())) {
            size = RATIO_SIZES.get(ratio.trim());
        } else {
            size = DEFAULT_IMAGE_SIZE;
        }
        int[] parsed = parseSize(size);
        int pixels = parsed[0] * parsed[1];
        if (pixels < IMAGE_MIN_PIXELS || pixels > IMAGE_MAX_PIXELS) {
            throw new IllegalArgumentException("Trae 出图尺寸不被接受：" + size
                    + "（总像素需在 [" + IMAGE_MIN_PIXELS + "," + IMAGE_MAX_PIXELS + "]），改用 1920x1920 或 2k/3k/4k");
        }
        return size;
    }

    /**
     * 解析 {@code WxH} 尺寸。
     *
     * @param size 尺寸字符串
     * @return {宽, 高}
     */
    private static int[] parseSize(String size) {
        int index = size.indexOf('x');
        if (index <= 0) {
            return new int[]{1920, 1920};
        }
        try {
            return new int[]{Integer.parseInt(size.substring(0, index)),
                    Integer.parseInt(size.substring(index + 1))};
        } catch (NumberFormatException e) {
            return new int[]{1920, 1920};
        }
    }

    /**
     * 调文生图接口，返回图片 uri（TOS 对象 key）。
     *
     * @param prompt    提示词
     * @param imageSize 出图尺寸
     * @return 图片 uri
     */
    private String requestImage(String prompt, String imageSize) {
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("prompt", prompt);
        body.put("image_size", imageSize);
        JsonNode json = postJson(TEXT_TO_IMAGE_PATH, body);
        int code = json.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("Trae 文生图失败 code=" + code + ": " + json.path("message").asText(""));
        }
        JsonNode uriNode = json.path("uri");
        if (uriNode.isMissingNode() || uriNode.asText("").isEmpty()) {
            // 兼容字段池：上游若改回嵌套形态，按已知字段名再扫一层
            for (String field : List.of("tos_uri", "image_url", "display_url")) {
                if (!json.path(field).asText("").isEmpty()) {
                    uriNode = json.path(field);
                    break;
                }
            }
        }
        String uri = uriNode.asText("");
        if (uri.isEmpty()) {
            throw new IllegalStateException("Trae 文生图应答里没有图片地址（message="
                    + json.path("message").asText("") + "）");
        }
        return uri;
    }

    /**
     * 把图片 uri 换成可直接访问的签名地址。
     *
     * @param uri 图片 uri
     * @return 签名下载地址
     */
    private String signedResourceUrl(String uri) {
        Map<String, Object> body = new LinkedHashMap<>(1);
        body.put("uri_list", List.of(uri));
        JsonNode json = postJson(RESOURCE_URL_PATH, body);
        String signed = json.path("url_map").path(uri).asText("");
        if (signed.isEmpty()) {
            throw new IllegalStateException("图片已生成但取不到下载地址（url_map 缺 key）：" + uri);
        }
        return signed;
    }

    /**
     * 发送带 Trae 认证头的 JSON POST 请求。
     *
     * @param path 端点路径
     * @param body 请求体
     * @return 解析后的 JSON 应答
     */
    private JsonNode postJson(String path, Map<String, Object> body) {
        TraeHttpClient http = requireHttpClient();
        try {
            AuthManager.AuthSnapshot auth = authManager.getAuth();
            Map<String, String> headers = http.buildHeaders(auth);
            Request request = http.buildRequest(http.apiHost() + path, headers, MAPPER.writeValueAsString(body), false);
            try (Response response = http.rawClient().newCall(request).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Trae 接口 " + path + " 返回 HTTP " + response.code()
                            + ": " + abbreviate(text));
                }
                return MAPPER.readTree(text);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Trae 接口 " + path + " 调用失败：" + e.getMessage(), e);
        } catch (AuthManager.AuthException e) {
            throw new IllegalStateException("Trae 登录态不可用：" + e.getMessage(), e);
        }
    }

    /**
     * 获取 HTTP 客户端（必要时先初始化登录态）。
     *
     * @return HTTP 客户端
     */
    private TraeHttpClient requireHttpClient() {
        if (httpClient == null) {
            delegate();
        }
        return httpClient;
    }

    /**
     * 截断超长响应体，避免把上游 HTML 错误页整段塞进日志 / 前端。
     *
     * @param text 响应文本
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').replace('\r', ' ');
        return single.length() > 200 ? single.substring(0, 200) + "..." : single;
    }
}
