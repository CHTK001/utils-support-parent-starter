package com.chua.common.support.ai.chat.protocol;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.AggregateChatClient;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContext;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Consumer;

/**
 * AI 协议 ServerFilter — 在 ProtocolServer 中直接处理 OpenAI / Claude / CCS RESTful 请求。
 *
 * <p>继承 {@link UrlMappingServerFilter}，自动注册所有 AI 协议路由。
 * 内部封装 {@link ChatClient}（通常为 {@link com.chua.common.support.ai.chat.aggregate.AggregateChatClient}），
 * 完成请求解析 → AI 调用 → 响应格式化的完整链路。
 *
 * <p>使用示例（独立端口模式）：
 * <pre>{@code
 *   ChatClient client = new AggregateChatClient(jsonConfig);
 *   ProtocolServer server = ProtocolServer.create("http", setting);
 *   server.addFilter(new AiProtocolServerFilter(client));
 * }</pre>
 *
 * <p>支持协议：
 * <ul>
 *   <li>OpenAI Chat Completions — {@code POST /v1/chat/completions}</li>
 *   <li>OpenAI Completions — {@code POST /v1/completions}</li>
 *   <li>OpenAI Models — {@code GET /v1/models}</li>
 *   <li>Claude Messages — {@code POST /v1/messages}</li>
 *   <li>Claude Code Server — {@code POST /v1/responses}, {@code POST /responses}</li>
 *   <li>Gemini — {@code POST /v1beta/models/**}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SuppressWarnings({"NullAway", "unchecked"})
@NullUnmarked
public class AiProtocolServerFilter extends UrlMappingServerFilter {

    private final ChatClient chatClient;

    /** 关联的 AiTokenServerFilter（当 ChatClient 配置了 tokenProvider 时自动创建） */
    private AiTokenServerFilter tokenFilter;

    public AiProtocolServerFilter(ChatClient chatClient) {
        this(chatClient, new DefaultObjectContext());
    }

    public AiProtocolServerFilter(ChatClient chatClient, ObjectContext objectContext) {
        super(objectContext);
        this.chatClient = chatClient;
        registerRoutes();
        initTokenFilter(chatClient);
    }

    /**
     * 如果 ChatClient 是 {@link AggregateChatClient} 且设置了 {@link AiTokenProvider}，
     * 自动创建 {@link AiTokenServerFilter}。
     */
    private void initTokenFilter(ChatClient client) {
        if (!(client instanceof AggregateChatClient)) return;
        AiTokenProvider provider = ((AggregateChatClient) client).getTokenProvider();
        if (provider != null && provider.count() > 0) {
            this.tokenFilter = new AiTokenServerFilter(provider);
            log.info("[AiProtocolServerFilter] 从 AggregateChatClient 加载令牌提供者: {}", provider.getClass().getSimpleName());
        }
    }

    /**
     * 获取自动创建的 AiTokenServerFilter，调用方需添加到 ProtocolServer。
     *
     * @return AiTokenServerFilter，未配置时返回 null
     */
    public AiTokenServerFilter getTokenFilter() {
        return tokenFilter;
    }

    /**
     * 在调用 ChatClient 前设置 token 分组，使路由策略能按 token 分组过滤模型组。
     * 调用完成后自动清除线程局部变量。
     */
    private void applyTokenGroup(ServerRequest request) {
        if (!(chatClient instanceof AggregateChatClient)) return;
        Object attr = request.getAttribute(AiTokenServerFilter.ATTR_TOKEN_GROUP);
        if (attr instanceof String) {
            ((AggregateChatClient) chatClient).withTokenGroup((String) attr);
        }
    }

    /**
     * 清除 token 分组线程局部变量。
     */
    private void clearTokenGroup() {
        AggregateChatClient.clearTokenGroup();
    }

    // ==================== 路由注册 ====================

    private void registerRoutes() {
        // OpenAI
        route("/v1/chat/completions", HttpMethod.POST, this::handleChatCompletions);
        route("/v1/completions", HttpMethod.POST, this::handleCompletions);
        route("/v1/models", HttpMethod.GET, this::handleModels);

        // Claude
        route("/v1/messages", HttpMethod.POST, this::handleMessages);

        // CCS / Responses
        route("/v1/responses", HttpMethod.POST, this::handleResponses);
        route("/responses", HttpMethod.POST, this::handleResponses);

        // Gemini (path pattern 由 UrlMappingServerFilter 的 Ant 风格匹配支持)
        route("/v1beta/models/**", HttpMethod.POST, this::handleGemini);

        log.info("[AiProtocolServerFilter] AI 协议路由注册完成");
    }

    // ==================== OpenAI Chat Completions ====================

    private void handleChatCompletions(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        String model = (String) body.get("model");

        if (stream) {
            handleOpenAiStream(request, response, body, model);
            return;
        }

        String prompt = extractOpenAiPrompt(body);
        String system = extractOpenAiSystem(body);
        String fullPrompt = system != null ? system + "\n" + prompt : prompt;

        applyTokenGroup(request);
        try {
            ChatSyncResponse result = chatClient.chatSyncWithResponse(fullPrompt);
            writeJson(response, buildOpenAiChatResponse(result, model));
        } catch (Exception ex) {
            log.error("[AiProtocol] chat completions failed: {}", ex.getMessage());
            writeJson(response, buildOpenAiChatError(model, ex.getMessage()));
        } finally {
            clearTokenGroup();
        }
    }

    private void handleOpenAiStream(ServerRequest request, ServerResponse response, Map<String, Object> body, String model) throws Exception {
        response.sse();
        String prompt = extractOpenAiPrompt(body);
        String system = extractOpenAiSystem(body);
        String fullPrompt = system != null ? system + "\n" + prompt : prompt;

        // 发送 ready 事件
        response.sseEvent("ready", "ready");

        applyTokenGroup(request);
        chatClient.chat(fullPrompt, new Consumer<ChatResponse>() {
            @Override
            public void accept(ChatResponse cr) {
                if (cr.getState() == ChatResponse.State.STREAMING && cr.getContent() != null) {
                    Map<String, Object> delta = buildOpenAiStreamDelta(cr.getContent(), model);
                    response.sseEvent(null, Json.toJson(delta));
                }
            }
        }, () -> {
            response.sseEvent(null, "[DONE]");
            response.flush();
            clearTokenGroup();
        }, error -> {
            log.error("[AiProtocol] stream error: {}", error.getMessage());
            response.sseEvent("error", error.getMessage());
            response.sseEvent(null, "[DONE]");
            response.flush();
            clearTokenGroup();
        });
    }



    // ==================== OpenAI Completions ====================

    private void handleCompletions(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        String model = (String) body.get("model");
        String prompt = (String) body.get("prompt");

        applyTokenGroup(request);
        try {
            ChatSyncResponse result = chatClient.chatSyncWithResponse(prompt);
            writeJson(response, buildOpenAiCompletionResponse(result, model, prompt));
        } catch (Exception ex) {
            writeJson(response, buildOpenAiCompletionError(model, prompt, ex.getMessage()));
        } finally {
            clearTokenGroup();
        }
    }

    // ==================== Models ====================

    private void handleModels(ServerRequest request, ServerResponse response) throws Exception {
        List<Map<String, Object>> data = new ArrayList<>();
        try {
            chatClient.models().forEach(md ->
                    data.add(Map.of(
                            "id", md.getName() != null ? md.getName() : "unknown",
                            "object", "model",
                            "created", (int) (System.currentTimeMillis() / 1000),
                            "owned_by", "system"
                    ))
            );
        } catch (Exception e) {
            log.debug("[AiProtocol] models() failed: {}", e.getMessage());
        }
        if (data.isEmpty()) {
            data.add(Map.of(
                    "id", "default",
                    "object", "model",
                    "created", (int) (System.currentTimeMillis() / 1000),
                    "owned_by", "system"
            ));
        }
        writeJson(response, Map.of("object", "list", "data", data));
    }

    // ==================== Claude Messages ====================

    private void handleMessages(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        String model = (String) body.get("model");
        boolean stream = Boolean.TRUE.equals(body.get("stream"));

        if (stream) {
            handleClaudeStream(request, response, body, model);
            return;
        }

        applyTokenGroup(request);
        try {
            String prompt = extractClaudePrompt(body);
            ChatSyncResponse result = chatClient.chatSyncWithResponse(prompt);
            writeJson(response, buildClaudeResponse(result, model));
        } catch (Exception ex) {
            writeJson(response, buildClaudeError(model, ex.getMessage()));
        } finally {
            clearTokenGroup();
        }
    }

    private void handleClaudeStream(ServerRequest request, ServerResponse response, Map<String, Object> body, String model) throws Exception {
        response.sse();
        String prompt = extractClaudePrompt(body);

        response.sseEvent("message_start", Json.toJson(Map.of(
                "type", "message_start",
                "message", buildClaudeStartResponse(model)
        )));
        response.sseEvent("content_block_start", Json.toJson(Map.of(
                "type", "content_block_start",
                "index", 0,
                "content_block", Map.of("type", "text", "text", "")
        )));

        applyTokenGroup(request);
        chatClient.chat(prompt, cr -> {
            if (cr.getState() == ChatResponse.State.STREAMING && cr.getContent() != null) {
                response.sseEvent("content_block_delta", Json.toJson(Map.of(
                        "type", "content_block_delta",
                        "index", 0,
                        "delta", Map.of("type", "text_delta", "text", cr.getContent())
                )));
            }
        }, () -> {
            response.sseEvent("content_block_stop", Json.toJson(Map.of("type", "content_block_stop", "index", 0)));
            response.sseEvent("message_delta", Json.toJson(Map.of(
                    "type", "message_delta",
                    "delta", Map.of("stop_reason", "end_turn", "stop_sequence", null),
                    "usage", Map.of("output_tokens", 0)
            )));
            response.sseEvent("message_stop", Json.toJson(Map.of("type", "message_stop")));
            response.flush();
            clearTokenGroup();
        }, error -> {
            log.error("[AiProtocol] claude stream error: {}", error.getMessage());
            response.sseEvent("error", error.getMessage());
            response.flush();
            clearTokenGroup();
        });
    }



    // ==================== CCS /responses ====================

    private void handleResponses(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        String model = (String) body.get("model");
        boolean stream = Boolean.TRUE.equals(body.get("stream"));

        String prompt = extractResponsesPrompt(body);

        applyTokenGroup(request);
        try {
            ChatSyncResponse result = chatClient.chatSyncWithResponse(prompt);
            if (stream) {
                handleResponsesStream(response, result, model);
                return;
            }
            writeJson(response, buildResponsesResponse(result, model));
        } catch (Exception ex) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("text", ex.getMessage());
            if (stream) {
                handleResponsesStream(response, errorResult, model);
                return;
            }
            writeJson(response, buildResponsesResponse(errorResult, model));
        } finally {
            clearTokenGroup();
        }
    }

    private void handleResponsesStream(ServerResponse response, Object result, String model) throws Exception {
        response.sse();
        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "");
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String text = result instanceof Map ? (String) ((Map<?, ?>) result).get("text") : "";

        // 初始 SSE 事件序列
        Map<String, Object> started = buildCcsResponse(responseId, messageId, model, "", "in_progress");
        started.put("output", List.of());

        response.sseEvent("response.created", Json.toJson(Map.of(
                "type", "response.created", "response", started
        )));
        response.sseEvent("response.output_item.added", Json.toJson(Map.of(
                "type", "response.output_item.added", "output_index", 0,
                "item", buildCcsMessage(messageId, "", "in_progress")
        )));
        response.sseEvent("response.content_part.added", Json.toJson(Map.of(
                "type", "response.content_part.added",
                "item_id", messageId, "output_index", 0, "content_index", 0,
                "part", Map.of("type", "output_text", "text", "", "annotations", List.of())
        )));

        // 流式输出文本
        if (text != null && !text.isEmpty()) {
            int chunkSize = 80;
            for (int i = 0; i < text.length(); i += chunkSize) {
                int end = Math.min(i + chunkSize, text.length());
                String chunk = text.substring(i, end);
                response.sseEvent("response.output_text.delta", Json.toJson(Map.of(
                        "type", "response.output_text.delta",
                        "item_id", messageId, "output_index", 0, "content_index", 0,
                        "delta", chunk
                )));
            }
        }

        // 完成事件
        response.sseEvent("response.output_text.done", Json.toJson(Map.of(
                "type", "response.output_text.done",
                "item_id", messageId, "output_index", 0, "content_index", 0,
                "text", text != null ? text : ""
        )));
        response.sseEvent("response.content_part.done", Json.toJson(Map.of(
                "type", "response.content_part.done",
                "item_id", messageId, "output_index", 0, "content_index", 0,
                "part", Map.of("type", "output_text", "text", text != null ? text : "", "annotations", List.of())
        )));
        response.sseEvent("response.output_item.done", Json.toJson(Map.of(
                "type", "response.output_item.done", "output_index", 0,
                "item", buildCcsMessage(messageId, text != null ? text : "", "completed")
        )));
        response.sseEvent("response.completed", Json.toJson(Map.of(
                "type", "response.completed",
                "response", buildCcsResponse(responseId, messageId, model, text != null ? text : "", "completed")
        )));
        response.flush();
    }

    // ==================== Gemini ====================

    private void handleGemini(ServerRequest request, ServerResponse response) throws Exception {
        String fullPath = request.getPath();
        String suffix = "/v1beta/models/";
        String rest = fullPath.startsWith(suffix) ? fullPath.substring(suffix.length()) : fullPath;
        int colonIdx = rest.lastIndexOf(':');
        if (colonIdx < 0) {
            writeJson(response, Map.of("error", Map.of("code", 400, "message", "Invalid Gemini path: " + fullPath, "status", "INVALID_ARGUMENT")));
            return;
        }
        String model = rest.substring(0, colonIdx);
        String action = rest.substring(colonIdx + 1);
        boolean isStream = "streamGenerateContent".equals(action);

        Map<String, Object> body = parseBody(request);
        String prompt = extractGeminiPrompt(body);

        applyTokenGroup(request);
        try {
            ChatSyncResponse result = chatClient.chatSyncWithResponse(prompt);
            String text = result != null ? result.getText() : "";

            if (isStream) {
                response.sse();
                Map<String, Object> candidate = buildGeminiCandidate(model, text);
                response.sseEvent(null, Json.toJson(candidate));
                response.sseEvent(null, "[DONE]");
                response.flush();
                return;
            }

            Map<String, Object> geminiResp = new LinkedHashMap<>();
            geminiResp.put("candidates", List.of(buildGeminiCandidate(model, text)));
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("promptTokenCount", 0);
            usage.put("candidatesTokenCount", 0);
            usage.put("totalTokenCount", 0);
            geminiResp.put("usageMetadata", usage);
            writeJson(response, geminiResp);
        } catch (Exception ex) {
            writeJson(response, Map.of("error", Map.of("code", 500, "message", ex.getMessage(), "status", "INTERNAL")));
        } finally {
            clearTokenGroup();
        }
    }

    // ==================== Prompt 提取 ====================

    private String extractOpenAiPrompt(Map<String, Object> body) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        if (messages == null) return "";

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            if ("user".equals(role) && content != null) {
                sb.append(extractText(content)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String extractOpenAiSystem(Map<String, Object> body) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        if (messages == null) return null;
        for (Map<String, Object> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                return extractText(msg.get("content"));
            }
        }
        return null;
    }

    private String extractClaudePrompt(Map<String, Object> body) {
        String system = extractText(body.get("system"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        StringBuilder sb = new StringBuilder();
        if (system != null) sb.append(system).append("\n");
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                if ("user".equals(msg.get("role"))) {
                    sb.append(extractText(msg.get("content"))).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractResponsesPrompt(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        appendText(sb, body.get("instructions"));
        Object input = body.get("input");
        if (input instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    if ("message".equals(m.get("type")) || m.containsKey("role")) {
                        appendText(sb, m.get("content"));
                    }
                }
            }
        }
        appendText(sb, body.get("prompt"));
        return sb.toString().trim();
    }

    private String extractGeminiPrompt(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        Object systemInstruction = body.get("systemInstruction");
        if (systemInstruction instanceof Map) {
            String text = extractPartsText((Map<?, ?>) systemInstruction);
            if (text != null) sb.insert(0, text + "\n");
        }
        Object contents = body.get("contents");
        if (contents instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String role = (String) m.get("role");
                    String text = extractPartsText(m);
                    if (text != null) {
                        sb.append(text).append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractPartsText(Map<?, ?> obj) {
        Object parts = obj.get("parts");
        if (parts instanceof List<?> list) {
            for (Object part : list) {
                if (part instanceof Map<?, ?> m) {
                    String text = (String) m.get("text");
                    if (text != null && !text.isEmpty()) return text;
                }
            }
        }
        return null;
    }

    private void appendText(StringBuilder sb, Object value) {
        String text = extractText(value);
        if (text != null && !text.isBlank()) {
            sb.append(text.trim()).append('\n');
        }
    }

    private String extractText(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                String t = extractText(item);
                if (t != null && !t.isBlank()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(t.trim());
                }
            }
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            String t = (String) map.get("text");
            if (t != null) return t;
            Object c = map.get("content");
            if (c != null) return extractText(c);
            Object p = map.get("parts");
            if (p != null) return extractText(p);
        }
        return String.valueOf(value);
    }

    // ==================== Response Builders ====================

    private Map<String, Object> buildOpenAiChatResponse(ChatSyncResponse result, String model) {
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String text = result != null ? result.getText() : "";
        String resolvedModel = model != null ? model : "unknown";

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text != null ? text : "");

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", "chat.completion");
        body.put("created", (int) (System.currentTimeMillis() / 1000));
        body.put("model", resolvedModel);
        body.put("choices", List.of(choice));

        if (result != null && result.getUsage() != null) {
            body.put("usage", Map.of(
                    "prompt_tokens", result.getUsage().getInputTokens() != null ? result.getUsage().getInputTokens() : 0,
                    "completion_tokens", result.getUsage().getOutputTokens() != null ? result.getUsage().getOutputTokens() : 0,
                    "total_tokens", result.getUsage().getTotalTokens() != null ? result.getUsage().getTotalTokens() : 0
            ));
        }
        return body;
    }

    private Map<String, Object> buildOpenAiChatError(String model, String errorMessage) {
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", errorMessage);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", "chat.completion");
        body.put("created", (int) (System.currentTimeMillis() / 1000));
        body.put("model", model != null ? model : "unknown");
        body.put("choices", List.of(choice));
        return body;
    }

    private Map<String, Object> buildOpenAiStreamDelta(String chunk, String model) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        delta.put("object", "chat.completion.chunk");
        delta.put("created", (int) (System.currentTimeMillis() / 1000));
        delta.put("model", model != null ? model : "unknown");
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", Map.of("content", chunk));
        choice.put("finish_reason", null);
        delta.put("choices", List.of(choice));
        return delta;
    }

    private Map<String, Object> buildOpenAiCompletionResponse(ChatSyncResponse result, String model, String prompt) {
        String id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String text = result != null ? result.getText() : "";
        String resolvedModel = model != null ? model : "unknown";

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("text", text != null ? text : "");
        choice.put("index", 0);
        choice.put("finish_reason", "stop");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", "text_completion");
        body.put("created", (int) (System.currentTimeMillis() / 1000));
        body.put("model", resolvedModel);
        body.put("choices", List.of(choice));
        if (result != null && result.getUsage() != null) {
            body.put("usage", Map.of(
                    "prompt_tokens", result.getUsage().getInputTokens() != null ? result.getUsage().getInputTokens() : 0,
                    "completion_tokens", result.getUsage().getOutputTokens() != null ? result.getUsage().getOutputTokens() : 0,
                    "total_tokens", result.getUsage().getTotalTokens() != null ? result.getUsage().getTotalTokens() : 0
            ));
        }
        return body;
    }

    private Map<String, Object> buildOpenAiCompletionError(String model, String prompt, String errorMessage) {
        String id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("text", errorMessage);
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", "text_completion");
        body.put("created", (int) (System.currentTimeMillis() / 1000));
        body.put("model", model != null ? model : "unknown");
        body.put("choices", List.of(choice));
        return body;
    }

    private Map<String, Object> buildClaudeResponse(ChatSyncResponse result, String model) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String text = result != null ? result.getText() : "";
        String resolvedModel = model != null ? model : "claude-3-haiku-20240307";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", resolvedModel);
        body.put("content", List.of(Map.of("type", "text", "text", text != null ? text : "")));
        body.put("stop_reason", "end_turn");
        body.put("stop_sequence", null);
        body.put("usage", Map.of(
                "input_tokens", 0,
                "output_tokens", 0
        ));
        return body;
    }

    private Map<String, Object> buildClaudeStartResponse(String model) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String resolvedModel = model != null ? model : "claude-3-haiku-20240307";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", resolvedModel);
        body.put("content", List.of());
        body.put("stop_reason", null);
        body.put("stop_sequence", null);
        body.put("usage", Map.of("input_tokens", 0, "output_tokens", 0));
        return body;
    }

    private Map<String, Object> buildClaudeError(String model, String errorMessage) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String resolvedModel = model != null ? model : "claude-3-haiku-20240307";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", resolvedModel);
        body.put("content", List.of(Map.of("type", "text", "text", errorMessage)));
        body.put("stop_reason", "end_turn");
        body.put("stop_sequence", null);
        return body;
    }

    private Map<String, Object> buildResponsesResponse(Object result, String model) {
        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "");
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String text = result instanceof Map ? (String) ((Map<?, ?>) result).get("text") : "";
        return buildCcsResponse(responseId, messageId, model, text != null ? text : "", "completed");
    }

    private Map<String, Object> buildCcsResponse(String responseId, String messageId, String model, String text, String status) {
        String resolvedModel = model != null ? model : "unknown";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", responseId);
        body.put("object", "response");
        body.put("created_at", (int) (System.currentTimeMillis() / 1000));
        body.put("status", status);
        body.put("model", resolvedModel);
        body.put("output", List.of(buildCcsMessage(messageId, text, status)));
        body.put("output_text", text);
        body.put("parallel_tool_calls", true);
        body.put("usage", Map.of("input_tokens", 0, "output_tokens", 0, "total_tokens", 0));
        return body;
    }

    private Map<String, Object> buildCcsMessage(String messageId, String text, String status) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("status", status);
        message.put("role", "assistant");
        message.put("content", List.of(Map.of(
                "type", "output_text",
                "text", text,
                "annotations", List.of()
        )));
        return message;
    }

    private Map<String, Object> buildGeminiCandidate(String role, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", role);
        content.put("parts", List.of(Map.of("text", text != null ? text : "")));
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        candidate.put("finishReason", "STOP");
        candidate.put("index", 0);
        return candidate;
    }

    // ==================== Helpers ====================

    private Map<String, Object> parseBody(ServerRequest request) {
        String bodyStr = request.getBodyString();
        if (bodyStr == null || bodyStr.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.fromJson(bodyStr, LinkedHashMap.class);
        } catch (Exception ex) {
            log.warn("[AiProtocol] parse-body-failed: {}", ex.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeJson(ServerResponse response, Object obj) {
        response.setContentType("application/json; charset=utf-8");
        response.setBody(Json.toJson(obj));
        response.end();
    }

    @Override
    public String getFilterId() {
        return "AiProtocolServerFilter";
    }

    @Override
    public int getOrder() {
        return 150;
    }
}