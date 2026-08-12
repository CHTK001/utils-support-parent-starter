package com.chua.google.support;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Google Gemini 大模型对话客户端
 *
 * <p>基于 Google Gemini API 的 {@link ChatClient} 实现，通过 HTTP 协议
 * 调用 Gemini 系列模型的对话接口，支持 Gemini 1.5 Pro/Flash 等模型。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"google", "gemini"})
public class GoogleChatClient implements ChatClient {

    /**
     * Gemini 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta";

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
     * 构造 Google Gemini 对话客户端
     *
     * @param setting 客户端配置
     */
    public GoogleChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.system = setting.getSystem();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(proxySelector(setting.getProxy()))
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
        history.add(ChatMessage.builder().role("model").content(content).build());
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
        String actualApiKey = setting.getAppKey();

        try {
            long startTime = System.currentTimeMillis();
            // 发送开始事件
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            // 构建 Gemini 请求体
            StringBuilder contentsJson = new StringBuilder();
            contentsJson.append("[");
            List<ChatMessage> messages = externalHistory != null ? externalHistory : history;
            for (ChatMessage msg : messages) {
                contentsJson.append("{\"role\":\"").append(msg.getRole())
                        .append("\",\"parts\":[{\"text\":\"").append(escapeJson(msg.getContent())).append("\"}]},");
            }
            contentsJson.append("{\"role\":\"user\",\"parts\":[{\"text\":\"")
                    .append(escapeJson(prompt)).append("\"}]}");
            contentsJson.append("]");

            String actualSystem = system;
            if (skillManager != null) {
                actualSystem = SkillPrompt.inject(system, skillManager);
            }
            String systemStr = "";
            if (actualSystem != null && !actualSystem.isEmpty()) {
                systemStr = ",\"systemInstruction\":{\"parts\":[{\"text\":\"" + escapeJson(actualSystem) + "\"}]}";
            }

            StringBuilder extraFlags = new StringBuilder();
            if (thinking) {
                int budget = "low".equals(thinkingEffort) ? 1 : "medium".equals(thinkingEffort) ? 2 : 3;
                extraFlags.append(",\"thinkingConfig\":{\"thinkingBudget\":").append(budget).append("}");
            }
            if (smartSearch) { extraFlags.append(",\"tools\":[{\"googleSearch\":{}}]"); }

            String requestBody = "{\"contents\":" + contentsJson
                    + systemStr
                    + ",\"generationConfig\":{\"temperature\":" + (temperature != null ? temperature : 0.3)
                    + ",\"maxOutputTokens\":" + (maxTokens != null ? maxTokens : 2048) + "}"
                    + extraFlags.toString() + "}";

            String actualModel = model != null ? model : "gemini-1.5-pro";
            // 使用 streamGenerateContent SSE 端点实现流式输出
            String url = actualBaseUrl + "/models/" + actualModel
                    + ":streamGenerateContent?alt=sse&key=" + actualApiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // 使用 InputStream 读取 SSE 事件流
            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                    .model(actualModel)
                    .provider("google")
                    .startTime(startTime);

            if (response.statusCode() == 200) {
                StringBuilder fullContent = new StringBuilder();
                StringBuilder reasoningContent = new StringBuilder();
                // 按行读取 SSE 数据
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // SSE 数据行以 "data: " 为前缀
                        if (!line.startsWith("data: ")) {
                            continue;
                        }
                        String jsonStr = line.substring(6).trim();
                        // 跳过空行或结束标记
                        if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) {
                            continue;
                        }
                        try {
                            Map<String, Object> root = Json.fromJson(jsonStr);

                            // 提取用量元数据（通常在最后一个 chunk 中）
                            // Gemini 2.5 思考模型中可能包含 thoughtsTokenCount
                            Map<String, Object> usageMeta =
                                    (Map<String, Object>) root.get("usageMetadata");
                            if (usageMeta != null) {
                                usageBuilder
                                        .inputTokens(toInt(usageMeta.get("promptTokenCount")))
                                        .outputTokens(toInt(usageMeta.get("candidatesTokenCount")))
                                        .totalTokens(toInt(usageMeta.get("totalTokenCount")))
                                        .reasoningTokens(toInt(usageMeta.get("thoughtsTokenCount")));
                            }

                            // 提取 candidates 中的文本片段
                            List<Map<String, Object>> candidates =
                                    (List<Map<String, Object>>) root.get("candidates");
                            if (candidates == null || candidates.isEmpty()) {
                                continue;
                            }
                            Map<String, Object> first = candidates.get(0);
                            String finishReason = (String) first.get("finishReason");

                            Map<String, Object> content =
                                    (Map<String, Object>) first.get("content");
                            if (content != null) {
                                // 遍历所有 parts，逐一提取文本
                                // Gemini 2.5 思考模型中可能包含多个 part：thinking + response
                                List<Map<String, Object>> parts =
                                        (List<Map<String, Object>>) content.get("parts");
                                if (parts != null && !parts.isEmpty()) {
                                    String chunkText = null;
                                    String chunkReasoning = null;
                                    for (Map<String, Object> part : parts) {
                                        String text = (String) part.get("text");
                                        if (text == null) {
                                            continue;
                                        }
                                        // 检查 part 是否包含思考标记（部分 Gemini 版本或兼容 API 使用）
                                        if (isThinkingPart(part)) {
                                            reasoningContent.append(text);
                                            chunkReasoning = text;
                                        } else {
                                            fullContent.append(text);
                                            chunkText = text;
                                        }
                                    }
                                    // 发送流式内容片段（思考内容 + 可见内容）
                                    if (chunkText != null || chunkReasoning != null) {
                                        consumer.accept(ChatResponse.builder()
                                                .state(ChatResponse.State.STREAMING)
                                                .content(chunkText)
                                                .reasoningContent(chunkReasoning)
                                                .build());
                                    }
                                }
                            }

                            // 检测结束标记 — 任何 finishReason 都表示这是最后一个 chunk
                            if (finishReason != null) {
                                break;
                            }
                        } catch (Exception e) {
                            log.debug("解析 Gemini SSE chunk 失败: {}", e.getMessage());
                        }
                    }
                }

                // 发送结束事件，携带用量信息
                usageBuilder.durationMillis(System.currentTimeMillis() - startTime);
                String reasoningText = reasoningContent.length() > 0 ? reasoningContent.toString() : null;
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .content(reasoningText != null
                                ? reasoningText + "\n---\n" + fullContent.toString()
                                : fullContent.toString())
                        .fullContent(fullContent.toString())
                        .reasoningContent(reasoningText)
                        .usage(usageBuilder.build())
                        .build());
                onComplete.run();

            } else {
                // 读取错误响应体
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.ERROR)
                        .errorMessage("Gemini API 返回错误: " + response.statusCode() + " - " + errorBody)
                        .build());
                onError.accept(new RuntimeException("Gemini API 返回错误: " + response.statusCode()));
            }

        } catch (Exception e) {
            log.error("Google Gemini 对话请求失败: {}", e.getMessage(), e);
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
     * <p>若未配置地址则使用默认的 Gemini API 地址。
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

    /**
     * 判断 Gemini API 响应中的 part 是否为思考/推理内容
     *
     * <p>标准 Gemini streamGenerateContent API 中，thinking 和 response 都放在
     * {@code parts[0].text} 中，没有结构上的区分。此方法为以下场景预留：
     * <ul>
     *   <li>部分兼容 OpenAI 格式的代理在 Gemini 响应中添加 {@code reasoning_content} 字段</li>
     *   <li>Gemini Interactions API（较新版本）使用 {@code thought_summary} 区分思考内容</li>
     *   <li>自定义封装层在 part 中添加 {@code thought: true} 标记</li>
     * </ul>
     *
     * @param part 单个 part 的 JSON 解析结果
     * @return 如果该 part 包含思考内容则返回 true
     */
    private static boolean isThinkingPart(Map<String, Object> part) {
        // 检查 reasoning_content 字段（兼容 OpenAI 格式的代理）
        if (part.get("reasoning_content") != null) {
            return true;
        }
        // 检查 thought_summary 字段（Gemini Interactions API）
        if (part.get("thought_summary") != null) {
            return true;
        }
        // 检查 thought 布尔标记
        if (Boolean.TRUE.equals(part.get("thought"))) {
            return true;
        }
        return false;
    }

    /**
     * 解析代理地址字符串。
     *
     * @param proxyStr 代理地址，支持 http://、socks5:// 格式，可为空
     * @return Proxy 对象，未配置时返回 no-proxy
     */
    private static java.net.ProxySelector proxySelector(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return null;
        }
        java.net.Proxy.Type proxyType;
        String hostPort;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            proxyType = java.net.Proxy.Type.SOCKS;
            hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
        } else if (proxyStr.startsWith("http://")) {
            proxyType = java.net.Proxy.Type.HTTP;
            hostPort = proxyStr.substring(7);
        } else if (proxyStr.startsWith("https://")) {
            proxyType = java.net.Proxy.Type.HTTP;
            hostPort = proxyStr.substring(8);
        } else {
            proxyType = java.net.Proxy.Type.HTTP;
            hostPort = proxyStr;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        final java.net.Proxy proxy = new java.net.Proxy(proxyType, new InetSocketAddress(host, port));
        return new java.net.ProxySelector() {
            @Override
            public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                return java.util.List.of(proxy);
            }

            @Override
            public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
            }
        };
    }

}
