package com.chua.huawei.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatMessage;
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
 * 华为盘古大模型对话客户端
 *
 * <p>基于华为云盘古大模型 API 的 {@link ChatClient} 实现，通过 HTTP 协议
 * 调用华为云 ModelArts 盘古大模型的对话接口。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"huawei", "pangu"})
public class HuaweiChatClient implements ChatClient {

    /**
     * 盘古大模型默认 API 地址
     */
    private static final String DEFAULT_URL = "https://pangu.cn-north-4.myhuaweicloud.com/v1";

    /**
     * 获取 Token 的 IAM 地址
     */
    private static final String IAM_URL = "https://iam.cn-north-4.myhuaweicloud.com/v3/auth/tokens";

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
     * 缓存的 IAM Token
     */
    private String iamToken;

    /**
     * 构造华为盘古大模型对话客户端
     *
     * @param setting 客户端配置
     */
    public HuaweiChatClient(ChatClientSetting setting) {
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
        String actualBaseUrl = normalizeBaseUrl();
        String actualApiKey = setting.getAppKey();

        try {
            long startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            // 获取 IAM Token
            String token = getIamToken();

            // 构建盘古大模型请求体
            StringBuilder messagesJson = new StringBuilder();
            messagesJson.append("[");
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                messagesJson.append("{\"role\":\"").append(msg.getRole())
                        .append("\",\"content\":\"").append(escapeJson(msg.getContent())).append("\"},");
            }
            messagesJson.append("{\"role\":\"user\",\"content\":\"")
                    .append(escapeJson(prompt)).append("\"}");
            messagesJson.append("]");

            String requestBody = "{\"model\":\"" + (model != null ? model : "pangu-ultra")
                    + "\",\"messages\":" + messagesJson
                    + ",\"temperature\":" + (temperature != null ? temperature : 0.3)
                    + ",\"max_tokens\":" + (maxTokens != null ? maxTokens : 2048) + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(actualBaseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Auth-Token", token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "pangu-ultra")
                    .provider("huawei")
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
                        .errorMessage("盘古大模型 API 返回错误: " + response.statusCode() + " - " + body)
                        .build());
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("华为盘古大模型对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 获取华为云 IAM Token
     *
     * <p>使用 AK/SK 或用户名密码从华为云 IAM 服务获取认证 Token。
     *
     * @return IAM Token 字符串
     * @throws Exception 请求失败时抛出异常
     */
    private String getIamToken() throws Exception {
        if (iamToken != null) {
            return iamToken;
        }
        String authBody = "{\"auth\":{\"identity\":{\"methods\":[\"password\"],"
                + "\"password\":{\"user\":{\"name\":\"" + setting.getAppKey()
                + "\",\"password\":\"" + setting.getAppSecret()
                + "\",\"domain\":{\"name\":\"" + setting.getAppKey() + "\"}}}},"
                + "\"scope\":{\"project\":{\"name\":\"cn-north-4\"}}}}";
        HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(IAM_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(authBody))
                .build();
        HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        iamToken = tokenResponse.headers().firstValue("X-Subject-Token").orElse(null);
        return iamToken;
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>若未配置地址则使用默认的盘古大模型 API 地址。
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
