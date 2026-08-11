package com.chua.zhipu.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
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
 * 智谱 GLM 大模型对话客户端
 *
 * <p>基于智谱 AI 开放平台 GLM API 的 {@link ChatClient} 实现，通过 HTTP 协议
 * 调用智谱 GLM-4 系列模型的对话接口。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"zhipu", "glm"})
public class ZhipuChatClient implements ChatClient {

    /**
     * 智谱 GLM 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4";

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
     * 构造智谱 GLM 对话客户端
     *
     * @param setting 客户端配置
     */
    public ZhipuChatClient(ChatClientSetting setting) {
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
        String actualBaseUrl = normalizeBaseUrl();
        String actualApiKey = setting.getAppKey();

        try {
            long startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            // 构建智谱 GLM 请求体
            StringBuilder messagesJson = new StringBuilder();
            messagesJson.append("[");
            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }
            if (StringUtils.isNotEmpty(actualSystem)) {
                messagesJson.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(actualSystem)).append("\"},");
            }
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                messagesJson.append("{\"role\":\"").append(msg.getRole())
                        .append("\",\"content\":\"").append(escapeJson(msg.getContent())).append("\"},");
            }
            messagesJson.append("{\"role\":\"user\",\"content\":\"")
                    .append(escapeJson(prompt)).append("\"}");
            messagesJson.append("]");

            StringBuilder extraFlags = new StringBuilder();
            if (thinking) { extraFlags.append(",\"thinking\":true"); }
            if (smartSearch) { extraFlags.append(",\"enable_search\":true"); }

            String requestBody = "{\"model\":\"" + (model != null ? model : "glm-4")
                    + "\",\"messages\":" + messagesJson
                    + ",\"temperature\":" + (temperature != null ? temperature : 0.3)
                    + ",\"max_tokens\":" + (maxTokens != null ? maxTokens : 2048)
                    + extraFlags.toString() + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(actualBaseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + actualApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "glm-4")
                    .provider("zhipu")
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
                        .errorMessage("智谱 GLM API 返回错误: " + response.statusCode() + " - " + body)
                        .build());
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("智谱 GLM 对话请求失败: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>若未配置地址则使用默认的智谱 GLM API 地址。
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
