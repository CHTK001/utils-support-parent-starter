package com.chua.baidu.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 百度文心一言大模型对话客户端
 *
 * <p>基于百度千帆大模型平台 API 的 {@link ChatClient} 实现，通过 HTTP 协议
 * 调用文心一言（ERNIE-Bot）的对话接口。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"baidu"})
public class BaiduChatClient implements ChatClient {

    /**
     * 文心一言默认 API 地址
     */
    private static final String DEFAULT_URL = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat";

    /**
     * 获取 Access Token 的地址
     */
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

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
     * 缓存的 Access Token
     */
    private String accessToken;

    /**
     * 构造百度文心一言对话客户端
     *
     * @param setting 客户端配置
     */
    public BaiduChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
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
        chat(prompt, response -> {
            if (response.getState() == ChatResponse.State.STREAMING
                    && response.getContent() != null) {
                result.append(response.getContent());
            }
        });
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
        try {
            long startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            // 获取 Access Token
            String token = getAccessToken();

            // 构建文心一言请求体
            StringBuilder messagesJson = new StringBuilder();
            messagesJson.append("[");
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                messagesJson.append("{\"role\":\"").append(msg.getRole())
                        .append("\",\"content\":\"").append(escapeJson(msg.getContent())).append("\"},");
            }
            messagesJson.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(prompt)).append("\"}");
            messagesJson.append("]");

            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }
            String systemStr = "";
            if (actualSystem != null && !actualSystem.isEmpty()) {
                systemStr = ",\"system\":\"" + escapeJson(actualSystem) + "\"";
            }

            StringBuilder extraFlags = new StringBuilder();
            if (thinking) { extraFlags.append(",\"thinking\":true"); }
            if (smartSearch) { extraFlags.append(",\"enable_search\":true"); }

            String requestBody = "{\"messages\":" + messagesJson
                    + systemStr
                    + ",\"temperature\":" + (temperature != null ? temperature : 0.3)
                    + ",\"max_tokens\":" + (maxTokens != null ? maxTokens : 2048)
                    + extraFlags.toString() + "}";

            String actualModel = model != null ? model : "ernie-3.5-8k";
            String url = normalizeBaseUrl() + "/" + actualModel + "?access_token=" + token;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "ernie-3.5-8k")
                    .provider("baidu")
                    .startTime(startTime)
                    .durationMillis(System.currentTimeMillis() - startTime);
            try {
                Map<String, Object> root = Json.fromJson(body);
                Map<String, Object> usage = (Map<String, Object>) root.get("usage");
                if (usage != null) {
                    usageBuilder.inputTokens(toInt(usage.get("prompt_tokens")))
                            .outputTokens(toInt(usage.get("completion_tokens")))
                            .totalTokens(toInt(usage.get("total_tokens")));
                }
            } catch (Exception ignored) {
            }

            if (response.statusCode() == 200) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(body)
                        .fullContent(body)
                        .usage(usageBuilder.build())
                        .build());
            } else {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage("文心一言 API 返回错误: " + response.statusCode() + " - " + body)
                        .build());
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("百度文心一言对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 获取百度千帆 Access Token
     *
     * <p>使用 API Key 和 Secret Key 从百度 OAuth 服务获取访问令牌。
     *
     * @return Access Token 字符串
     * @throws Exception 请求失败时抛出异常
     */
    private String getAccessToken() throws Exception {
        if (accessToken != null) {
            return accessToken;
        }
        String apiKey = setting.getAppKey();
        String secretKey = setting.getAppSecret();
        String tokenReqBody = "grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + secretKey;
        HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(tokenReqBody))
                .build();
        HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        // 简单从响应中提取 access_token
        String tokenBody = tokenResponse.body();
        String tokenKey = "\"access_token\":\"";
        int start = tokenBody.indexOf(tokenKey);
        if (start > 0) {
            int end = tokenBody.indexOf("\"", start + tokenKey.length());
            accessToken = tokenBody.substring(start + tokenKey.length(), end);
        }
        return accessToken;
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>若未配置地址则使用默认的文心一言 API 地址。
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
     * 转义 JSON 字符串中的特殊字符
     *
     * @param input 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Integer toInt(Object val) {
        if (val instanceof Number n) { return n.intValue(); }
        return null;
    }

}
