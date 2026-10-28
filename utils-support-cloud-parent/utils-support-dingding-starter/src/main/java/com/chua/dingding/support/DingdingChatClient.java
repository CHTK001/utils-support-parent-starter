package com.chua.dingding.support;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 钉钉 AI 大模型对话客户端
 *
 * <p>基于钉钉 AI API 的 {@link ChatClient} 实现，通过钉钉开放平台的 AI 对话接口
 * 调用钉钉 AI 助理能力。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"dingding"})
public class DingdingChatClient implements ChatClient {

    /**
     * 钉钉 AI 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.dingtalk.com/v1.0/ai/conversations";

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
     * 当前最大 令牌 数
     */
    private Integer maxTokens;

    /**
     * 当前系统提示词
     */
    private String system;

    /**
     * 当前会话 标识
     */
    private String sessionId;

    /**
     * 对话历史消息列表
     */
    private final List<ChatMessage> history = Wraps.list();

    /**
     * 外部传入的完整历史记录
     */
    private List<ChatMessage> externalHistory;

    /**
     * 图片附件 URL 列表
     */
    private final List<String> imageUrls = Wraps.list();

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
     * 构造钉钉 AI 对话客户端
     *
     * @param setting 客户端配置
     */
    public DingdingChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
    }

    /**
     * 模型
    */
    @Override
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Temperature
    */
    @Override
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    /**
     * 最大值令牌
    */
    @Override
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    /**
     * 系统
    */
    @Override
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    /**
     * Thinking
    */
    @Override
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    /**
     * thinkingeffort
    */
    @Override
    public ChatClient thinkingEffort(String effort) {
        this.thinkingEffort = effort;
        return this;
    }

    /**
     * Smart搜索
    */
    @Override
    public ChatClient smartSearch(boolean smartSearch) {
        this.smartSearch = smartSearch;
        return this;
    }

    /**
     * Skill
    */
    @Override
    public ChatClient skill(SkillManager skillManager) {
        this.skillManager = skillManager;
        return this;
    }

    /**
     * 添加镜像
    */
    @Override
    public ChatClient addImage(String imageUrl) {
        this.imageUrls.add(imageUrl);
        return this;
    }

    /**
     * 添加用户历史
    */
    @Override
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    /**
     * 添加assistant历史
    */
    @Override
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    /**
     * 历史
    */
    @Override
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    /**
     * 会话
    */
    @Override
    public ChatClient session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * 添加Attachment
    */
    @Override
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持文件附件");
    }

    /**
     * 添加attachmenturl
    */
    @Override
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        throw new UnsupportedOperationException("该服务商不支持远程文件附件");
    }

/**
 * 新对话
*/
    @Override
public ChatClient newChat() {
        this.history.clear();
        this.imageUrls.clear();
        this.externalHistory = null;
        return this;
    }

    /**
     * 对话同步
    */
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

    /**
     * 对话
    */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException("对话请求失败: " + e.getMessage(), e);
        });
    }

    /**
     * 对话
     * @param prompt 提示符
     * @param consumer consumer
     * @param onComplete on完成
     * @param onError on错误
     */
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

            // 构建钉钉 AI 请求体
            StringBuilder messagesJson = new StringBuilder();
            messagesJson.append("[");
            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }
            if (actualSystem != null && !actualSystem.isEmpty()) {
                messagesJson.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(actualSystem))
                        .append("\"},");
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

            String requestBody = "{\"model\":\"" + (model != null ? model : "dingtalk-ai")
                    + "\",\"messages\":" + messagesJson
                    + ",\"temperature\":" + (temperature != null ? temperature : 0.3)
                    + ",\"max_tokens\":" + (maxTokens != null ? maxTokens : 2048)
                    + extraFlags.toString() + "}";

            ClientResponse response = newRequestBuilder(actualBaseUrl)
                    .header("Authorization", "Bearer " + actualApiKey)
                    .json()
                    .readTimeout(90000)
                    .body(requestBody)
                    .post();
            String body = response.getBodyString();

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(model != null ? model : "dingtalk-ai")
                    .provider("dingding")
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
            } catch (Exception e) {
                log.debug("[dingding] 解析用量统计失败，已忽略: {}", e.getMessage());
            }

            if (response.getStatusCode() == 200) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(body)
                        .fullContent(body)
                        .usage(usageBuilder.build())
                        .build());
            } else {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage("钉钉 AI API 返回错误: " + response.getStatusCode() + " - " + body)
                        .build());
            }
            onComplete.run();

        } catch (Exception e) {
            log.error("钉钉 AI 对话请求失败: {}", e.getMessage(), e);
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
     * <p>若未配置地址则使用默认的钉钉 AI API 地址。
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

    /**
     * 转为int
     *
     * @param val val
     * @return 转为int的结果
     */
    private static Integer toInt(Object val) {
        if (val instanceof Number n) { return n.intValue(); }
        return null;
    }

    /**
     * 创建带公共配置的 HTTP 请求构建器。
     *
     * @param url 完整请求 URL
     * @return 请求构建器
     */
    private HttpClientBuilder newRequestBuilder(String url) {
        HttpClientBuilder builder = HttpClientFactory.of(url)
                .connectTimeout(30000);
        String proxyStr = setting.getProxy();
        if (proxyStr != null && !proxyStr.isBlank()) {
            String hostPort = proxyStr;
            int sep = hostPort.indexOf("://");
            if (sep >= 0) {
                hostPort = hostPort.substring(sep + 3);
            }
            String[] parts = hostPort.split(":");
            if (parts.length == 2) {
                builder.proxy(parts[0], Integer.parseInt(parts[1]));
            }
        }
        return builder;
    }


    /**
     * 获取客户端配置。
     *
     * @return 配置
     */
    @Override
    public ChatClientSetting getSetting() {
        return setting;
    }
}
