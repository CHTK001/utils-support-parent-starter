package com.chua.trae.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chua.trae.support.auth.AuthManager;
import com.chua.trae.support.http.TraeHttpClient;
import com.chua.trae.support.model.ChatException;
import com.chua.trae.support.model.ChatMessage;
import com.chua.trae.support.model.ChatRequest;
import com.chua.trae.support.model.ChatRequest.FunctionCall;
import com.chua.trae.support.model.ChatRequest.ToolCall;
import com.chua.trae.support.model.ChatRequest.ToolDefinition;
import com.chua.trae.support.model.ChatResponse;
import com.chua.trae.support.model.ModelConfig;
import com.chua.trae.support.model.ModelConfig.FallbackConfig;
import com.chua.trae.support.model.ModelConfig.ModelEntry;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
   * Trae 本地 API 聊天客户端，封装 SSE 流式调用、工具调用、模型分档降级、令牌 自动刷新与 HTTP 代理。
 * 本类为有状态客户端，持有 {@link ScheduledExecutorService}，使用完需调用 {@link #close()} 释放资源。
 *
 * <p>使用示例：
 * <pre>
   * 对话客户端 客户端 = 对话客户端.构建器()
 *     .edition("cn")
   * .数据dir(系统.获取财产("用户.Home") + "/app数据/Roaming/Trae CN")
   * .模型配置(配置)
   * .构建();
   * 对话响应 resp = 客户端.对话(请求).连接();
 * </pre>
 *
 * @see <a href="https://github.com/square/okhttp/tree/main/okhttp-sse">OkHttp SSE</a>
 * @see <a href="https://github.com/ZedeX/trae-local-api">Trae Local API</a>
 * @author CH
 * @since 4.0.0.42
 */
public class ChatClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class); // 日志
    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /** Trae 聊天后端端点路径，对应 POST {api主机}{路径} */
    private static final String CHAT_ENDPOINT = "/api/agent/v3/llm_utils_chat";
    /** 默认模型标识，当请求未指定且配置无 降级 时使用 */
    private static final String DEFAULT_MODEL = "glm-5.2";
    /** 令牌 即将过期阈值（秒），剩余时间低于该值时触发刷新 */
    private static final int EXPIRY_THRESHOLD_SECONDS = 120;

    /** 认证管理器，负责从 storage.json 读取 令牌 及过期检测，不可为 空 */
    private final AuthManager authManager;
    /** Trae HTTP 客户端，封装 OkHttp 实例、请求头构造与代理配置，不可为 空 */
    private final TraeHttpClient httpClient;
    /** 模型分档配置，可为 空（此时不启用分档降级） */
    private final ModelConfig modelConfig;
    /** 最大重试次数，针对 429/4011 限流，默认 3 */
    private final int maxRetries;
    /** 默认模型标识，当请求未指定 模型 时使用，不可为 空 */
    private final String defaultModel;
    /** 重试调度器，守护线程，调用 关闭() 时关闭 */
    private final ScheduledExecutorService retryScheduler;

    /**
      * 创建客户端 构建器。
     *
     * @return 配置好的 构建器，可链式调用各配置方法后调用 {@link Builder#build()} 生成客户端
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 同步聊天，收集完整 SSE 流后返回响应。
     *
     * @param request 聊天请求，消息 不可为 空 或空
     * @return 包含完整文本/推理/工具调用的响应 期货
     * @throws NullPointerException 当 请求 为 空 时
     * @throws IllegalArgumentException 当 请求.消息 为 空 或空时
     */
    public CompletableFuture<ChatResponse> chat(ChatRequest request) {
        validateRequest(request);
        return streamCollect(request, null, null);
    }

    /**
     * 流式聊天，通过回调逐块接收文本/推理/工具调用内容。
     * 本方法立即返回，SSE 流在后台线程中处理。
     *
     * @param request 聊天请求，消息 不可为 空 或空
     * @param onTextChunk 文本块回调，每收到一段文本内容时触发，可为 空
     * @param onReasoningChunk 推理块回调，每收到一段推理内容时触发，可为 空
     * @param onComplete 完成回调，流正常结束时触发，可为 空
     * @param onError 错误回调，流发生异常时触发，不可为 空
     * @throws NullPointerException 当 请求 为 空 或 on错误 为 空 时
     * @throws IllegalArgumentException 当 请求.消息 为 空 或空时
     */
    public void chatStream(ChatRequest request,
                           Consumer<String> onTextChunk,
                           Consumer<String> onReasoningChunk,
                           Consumer<ChatResponse> onComplete,
                           Consumer<ChatException> onError) {
        validateRequest(request);
        Objects.requireNonNull(onError, "onError must not be null");
        try {
            ensureAuth();
            ChatRequest effective = withModel(request, resolveModel(request));
            StreamCollector collector = new StreamCollector(effective, onTextChunk, onReasoningChunk, onComplete, onError);
            openSse(effective, collector);
        } catch (ChatException e) {
            onError.accept(e);
        }
    }

    /**
     * 工具调用聊天，支持请求中携带 tools 定义，响应中解析 tool_calls。
     * 与 {@link #chat(ChatRequest)} 使用同一 SSE 管线，但语义上用于携带工具定义的对话。
     *
     * @param request 聊天请求，消息 不可为 空 或空，tools 可为 空
     * @return 包含工具调用结果的响应 期货
     * @throws NullPointerException 当 请求 为 空 时
     * @throws IllegalArgumentException 当 请求.消息 为 空 或空时
     */
    public CompletableFuture<ChatResponse> chatWithTools(ChatRequest request) {
        validateRequest(request);
        return streamCollect(request, null, null);
    }

    /**
     * 关闭客户端，释放重试调度器线程。
     * 本方法幂等，多次调用无副作用。
     */
    @Override
    public void close() {
        retryScheduler.shutdownNow();
    }

    /**
     * 校验请求参数合法性。
     *
     * @param request 待校验的聊天请求，不可为 空
     * @throws NullPointerException 当 请求 为 空 时
     * @throws IllegalArgumentException 当 请求.消息 为 空 或空时
     */
    private static void validateRequest(ChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("messages must not be null or empty");
        }
    }

    private CompletableFuture<ChatResponse> streamCollect(ChatRequest request,
                                                          Consumer<String> onText,
                                                          Consumer<String> onReasoning) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        try {
            ensureAuth();
            ChatRequest effective = withModel(request, resolveModel(request));
            StreamCollector collector = new StreamCollector(effective, onText, onReasoning, null, null);
            collector.future = future;
            openSse(effective, collector);
        } catch (ChatException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
      * 确保 令牌 有效，过期或即将过期时重新加载。
     *
     * @throws ChatException 当认证加载失败或 令牌 已过期时
     */
    private void ensureAuth() throws ChatException {
        try {
            AuthManager.AuthSnapshot auth = authManager.getAuth();
            if (AuthManager.isExpired(auth)) {
                log.info("[auth] token expired, invalidating cache");
                authManager.invalidate();
                auth = authManager.getAuth();
            }
            if (isExpiringSoon(auth)) {
                log.info("[auth] token expiring soon, refreshing");
                authManager.invalidate();
                auth = authManager.getAuth();
            }
        } catch (AuthManager.AuthException e) {
            throw new ChatException("Auth error: " + e.getMessage(), e);
        }
    }

    /**
      * 判断 令牌 是否即将过期（剩余时间低于阈值）。
     *
     * @param auth 认证快照，不可为 空
     * @return true 表示剩余时间低于 {@link #EXPIRY_THRESHOLD_SECONDS} 秒
     */
    private static boolean isExpiringSoon(AuthManager.AuthSnapshot auth) {
        if (auth.expiredAt() == null) {
            return false;
        }
        try {
            Duration remaining = Duration.between(
                java.time.Instant.now(), java.time.Instant.parse(auth.expiredAt()));
            return remaining.getSeconds() < EXPIRY_THRESHOLD_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
      * 解析请求模型，优先级：请求.模型 > 模型配置.降级模型 > 默认_模型。
     *
     * @param request 聊天请求，不可为 空
     * @return 解析后的模型标识，不可为 空
     */
    private String resolveModel(ChatRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        if (model != null) {
            return model;
        }
        FallbackConfig fc = modelConfig != null ? modelConfig.fallback() : null;
        if (fc != null && fc.fallbackModel() != null) {
            return fc.fallbackModel();
        }
        return DEFAULT_MODEL;
    }

    /**
      * 克隆请求并注入指定模型，强制 流=true。
     *
     * @param request 原始请求，不可为 空
     * @param model 目标模型标识，不可为 空
     * @return 注入模型后的新请求对象
     */
    private ChatRequest withModel(ChatRequest request, String model) {
        ChatRequest effective = ChatRequest.builder()
            .model(model)
            .messages(request.messages())
            .temperature(request.temperature())
            .maxTokens(request.maxTokens())
            .topP(request.topP())
            .tools(request.tools())
            .toolChoice(request.toolChoice())
            .extra(request.extra())
            .build();
        effective.stream(true);
        return effective;
    }

    /**
      * 打开 SSE 流，将 OkHttp 事件源 绑定到监听器。
     *
     * @param request 已注入模型的请求，不可为 空
     * @param listener SSE 事件监听器，不可为 空
     */
    private void openSse(ChatRequest request, EventSourceListener listener) {
        try {
            AuthManager.AuthSnapshot auth = authManager.getAuth();
            Map<String, String> headers = httpClient.buildHeaders(auth);
            String body = MAPPER.writeValueAsString(buildRequestBody(request));
            okhttp3.Request httpReq = httpClient.buildRequest(
                httpClient.apiHost() + CHAT_ENDPOINT,
                headers, body, true);
            okhttp3.OkHttpClient sseClient = httpClient.rawClient()
                .newBuilder()
                .readTimeout(Duration.ofMinutes(10))
                .build();
            EventSources.createFactory(sseClient).newEventSource(httpReq, listener);
        } catch (Exception e) {
            if (listener instanceof StreamCollector sc && sc.future != null) {
                sc.future.completeExceptionally(e);
            }
            throw new RuntimeException(e);
        }
    }

    /**
      * 构造 Trae 后端请求体，将 打开AI 风格请求转换为 Trae 协议。
     *
     * @param request 已注入模型的请求，不可为 空
     * @return 请求体 映射，可直接 JSON 序列化
     */
    private Map<String, Object> buildRequestBody(ChatRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        if (request.messages() != null) {
            for (ChatMessage msg : request.messages()) {
                ObjectNode m = messages.addObject();
                m.put("role", msg.role());
                switch (msg) {
                    case ChatRequest.UserMessage um -> {
                        if (um.content() != null) {
                            ArrayNode c = m.putArray("content");
                            ObjectNode t = c.addObject();
                            t.put("type", "text");
                            t.put("text", um.content());
                        }
                        if (um.toolCalls() != null) {
                            ArrayNode tc = m.putArray("tool_calls");
                            um.toolCalls().forEach(x -> addToolCallNode(tc, x));
                        }
                    }
                    case ChatRequest.AssistantMessage am -> {
                        if (am.content() != null) {
                            ArrayNode c = m.putArray("content");
                            ObjectNode t = c.addObject();
                            t.put("type", "text");
                            t.put("text", am.content());
                        }
                        if (am.toolCalls() != null) {
                            ArrayNode tc = m.putArray("tool_calls");
                            am.toolCalls().forEach(x -> addToolCallNode(tc, x));
                        }
                    }
                    case ChatRequest.ToolMessage tm -> {
                        m.put("tool_call_id", tm.toolCallId());
                        m.put("content", tm.content());
                    }
                    default -> m.put("content", "");
                }
            }
        }
        String model = request.model() != null ? request.model() : DEFAULT_MODEL;
        root.put("function", "chat_v3");
        root.put("stream", true);
        root.put("model", model);
        root.put("config_name", model);
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.tools() != null) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", tool.type());
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.function().name());
                if (tool.function().description() != null) {
                    fn.put("description", tool.function().description());
                }
                if (tool.function().parameters() != null) {
                    fn.set("parameters", MAPPER.valueToTree(tool.function().parameters()));
                }
            }
            if (request.toolChoice() != null) {
                root.put("tool_choice", request.toolChoice());
            }
        }
        return MAPPER.convertValue(root, Map.class);
    }

    /**
      * 向 JSON array节点 追加一个工具调用节点。
     *
     * @param array 目标数组节点，不可为 空
     * @param call 工具调用对象，不可为 空
     */
    private void addToolCallNode(ArrayNode array, ToolCall call) {
        ObjectNode c = array.addObject();
        c.put("id", call.id());
        c.put("type", "function");
        ObjectNode fn = c.putObject("function");
        fn.put("name", call.function() != null ? call.function().name() : "");
        fn.put("arguments", call.arguments() != null
            ? MAPPER.valueToTree(call.arguments()).toString()
            : (call.function() != null ? call.function().arguments() : "{}"));
    }

    /**
     * 调度一次重试，延迟时间随次数指数增长。
     *
     * @param request 原始请求，不可为 空
     * @param listener SSE 监听器，不可为 空
     * @param attempt 当前重试次数，从 1 开始
     */
    private void scheduleRetry(ChatRequest request, EventSourceListener listener, int attempt) {
        long delay = Math.min(1 << attempt, 30) * 1000L;
        log.info("[retry] rate limited, retrying in {}ms (attempt {})", delay, attempt);
        retryScheduler.schedule(() -> {
            try {
                openSse(request, listener);
            } catch (Exception e) {
                log.error("[retry] failed: {}", e.getMessage(), e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 解析指定模型的降级候选列表。
      * 优先级：mappings 精确匹配 > 分档降级（同档/低档）> 降级模型。
     *
     * @param model 模型标识，不可为 空
     * @return 候选模型列表，无候选时返回空列表
     */
    private List<String> fallbackModelsFor(String model) {
        if (modelConfig == null) {
            return List.of();
        }
        FallbackConfig fc = modelConfig.fallback();
        if (fc == null) {
            return List.of();
        }
        if (fc.mappings() != null && fc.mappings().containsKey(model)) {
            return fc.mappings().get(model);
        }
        if (fc.tieredFallback() && modelConfig.models() != null) {
            ModelEntry entry = modelConfig.models().get(model);
            if (entry != null && entry.tier() != null) {
                List<String> lower = modelConfig.models().entrySet().stream()
                    .filter(e -> e.getValue().tier() != null && e.getValue().tier() < entry.tier())
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
                if (!lower.isEmpty()) {
                    return lower;
                }
                List<String> same = modelConfig.models().entrySet().stream()
                    .filter(e -> e.getValue().tier() != null && e.getValue().tier().equals(entry.tier()))
                    .map(Map.Entry::getKey)
                    .filter(m -> !m.equals(model))
                    .sorted()
                    .toList();
                if (!same.isEmpty()) {
                    return same;
                }
            }
        }
        if (fc.fallbackModel() != null) {
            return List.of(fc.fallbackModel());
        }
        return List.of();
    }

    /**
     * SSE 事件收集器，逐事件解析文本/推理/工具调用/排队/错误，缓冲完整响应。
     * 内部类，绑定到单次请求。
     * @author CH
     * @since 4.0.0
     */
    private class StreamCollector extends EventSourceListener {
        /** 当前请求（已注入模型），不可为 空 */
        final ChatRequest request;
        /** 文本块回调，可为 空 */
        final Consumer<String> onText;
        /** 推理块回调，可为 空 */
        final Consumer<String> onReasoning;
        /** 完成回调，可为 空 */
        final Consumer<ChatResponse> onComplete;
        /** 错误回调，不可为 空 */
        final Consumer<ChatException> onError;
        /** 同步 期货，流collect 场景赋值，对话流 场景为 空 */
        volatile CompletableFuture<ChatResponse> future;
        /** 文本缓冲区 */
        final StringBuilder textBuffer = new StringBuilder();
        /** 推理缓冲区 */
        final StringBuilder reasoningBuffer = new StringBuilder();
        /** 工具调用列表 */
        final List<ToolCall> toolCalls = new ArrayList<>();
        /** 重试计数器 */
        final AtomicInteger retries = new AtomicInteger();
        /** 结束原因，done 事件解析后赋值 */
        volatile String finishReason;
        /** 令牌 用量，令牌_usage 事件解析后赋值 */
        volatile ChatResponse.Usage usage;
        /** 流是否已失败，失败后不再触发 完成 */
        volatile boolean failed;

        StreamCollector(ChatRequest request, Consumer<String> onText, Consumer<String> onReasoning,
                        Consumer<ChatResponse> onComplete, Consumer<ChatException> onError) {
            this.request = request;
            this.onText = onText;
            this.onReasoning = onReasoning;
            this.onComplete = onComplete;
            this.onError = onError;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
 // 类型 是 SSE 事件名：输出 / 令牌_usage / done / extra_信息 / metadata / 时间_cost
            switch (type) {
                case "output" -> {
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        String text = node.path("response").asText("");
                        String reasoning = node.path("reasoning_content").asText("");
                        JsonNode tcNode = node.path("tool_calls");
                        if (!text.isEmpty()) {
                            textBuffer.append(text);
                            if (onText != null) {
                                onText.accept(text);
                            }
                        }
                        if (!reasoning.isEmpty()) {
                            reasoningBuffer.append(reasoning);
                            if (onReasoning != null) {
                                onReasoning.accept(reasoning);
                            }
                        }
                        if (!tcNode.isMissingNode() && tcNode.isArray() && tcNode.size() > 0) {
                            parseToolCalls(tcNode);
                        }
                    } catch (Exception e) {
                        log.warn("[stream] failed to parse output event: {}", e.getMessage());
                    }
                }
                case "token_usage" -> {
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        int prompt = node.path("prompt_tokens").asInt(0);
                        int completion = node.path("completion_tokens").asInt(0);
                        int total = node.path("total_tokens").asInt(prompt + completion);
                        usage = new ChatResponse.Usage(prompt, completion, total);
                    } catch (Exception e) {
                        log.warn("[stream] failed to parse token_usage event: {}", e.getMessage());
                    }
                }
                case "done" -> handleDone(data);
                case "timing_cost" -> {
 // 检测排队：队列_时间 过大时触发模型降级
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        int queueMs = node.path("queue_timing").asInt(0);
                        int threshold = modelConfig != null && modelConfig.fallback() != null
                            ? modelConfig.fallback().queueThreshold() : 300;
                        if (queueMs > threshold * 1000) {
                            handleQueue(node);
                        }
                    } catch (Exception ignored) {
                        log.debug("[stream] failed to parse timing_cost: {}", ignored.getMessage());
                    }
                }
                case "metadata", "extra_info" -> {
                    // 元数据/计时事件，忽略不处理
                }
                default -> log.debug("[stream] unhandled SSE event type: {}", type);
            }
        }

        @Override
        public void onClosed(EventSource eventSource) {
            if (!failed && future != null && !future.isDone()) {
                completeWithBuffered();
            }
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            int code = response != null ? response.code() : 0;
            if ((code == 429 || code == 4011) && retries.get() < maxRetries) {
                int attempt = retries.incrementAndGet();
                scheduleRetry(request, this, attempt);
                return;
            }
            String msg = t != null ? t.getMessage() : "HTTP " + code;
            log.error("[stream] failure: {}", msg);
            failed = true;
            ChatException ex = new ChatException(code, msg, t);
            if (future != null) {
                future.completeExceptionally(ex);
            }
            if (onError != null) {
                onError.accept(ex);
            }
        }

        /**
          * 处理 done 事件，解析 饰面_ReasonML 并触发完成。
         *
         * @param data SSE 数据 负载，可为 空 或空
         */
        private void handleDone(String data) {
            try {
                if (data != null && !data.isEmpty()) {
                    JsonNode node = MAPPER.readTree(data);
                    finishReason = node.path("finish_reason").asText("stop");
                } else {
                    finishReason = "stop";
                }
            } catch (Exception e) {
                log.warn("[stream] failed to parse done event: {}", e.getMessage());
                finishReason = "stop";
            }
            completeWithBuffered();
        }

        /**
          * 将缓冲内容组装为响应并触发 完成。
          * 幂等，期货 已完成时不重复触发。
         */
        private void completeWithBuffered() {
            if (future != null && !future.isDone()) {
                ChatResponse resp = buildResponse();
                future.complete(resp);
                if (onComplete != null) {
                    onComplete.accept(resp);
                }
            }
        }

        /**
         * 构建完整响应对象。
         *
         * @return 包含缓冲文本/推理/工具调用/用量的 对话响应
         */
        private ChatResponse buildResponse() {
            ChatResponse resp = new ChatResponse();
            resp.id("cmpl-" + System.currentTimeMillis());
            resp.object("chat.completion");
            resp.created(System.currentTimeMillis());
            resp.model(request.model());
            List<ChatResponse.Choice> choices = new ArrayList<>();
            ChatResponse.Choice choice = new ChatResponse.Choice();
            choice.index(0);
            choice.finishReason(finishReason);
            ChatResponse.ResponseMessage msg = new ChatResponse.ResponseMessage();
            msg.role("assistant");
            msg.content(textBuffer.length() > 0 ? textBuffer.toString() : "");
            msg.reasoningContent(reasoningBuffer.length() > 0 ? reasoningBuffer.toString() : "");
            if (!toolCalls.isEmpty()) {
                msg.toolCalls(toolCalls);
            }
            choice.message(msg);
            choices.add(choice);
            resp.choices(choices);
            if (usage != null) {
                resp.usage(usage);
            }
            return resp;
        }

        /**
          * 解析 输出 事件中的 tool_calls 数组，追加到工具调用列表。
         *
         * @param tcNode tool_calls JSON 数组节点，不可为 空
         */
        private void parseToolCalls(JsonNode tcNode) {
            for (JsonNode tc : tcNode) {
                ToolCall call = new ToolCall();
                call.id(tc.path("id").asText("call-" + System.currentTimeMillis()));
                call.type("function");
                FunctionCall fn = new FunctionCall();
                JsonNode fnNode = tc.path("function");
                fn.name(fnNode.has("name") ? fnNode.path("name").asText("") : tc.path("name").asText(""));
                String args = fnNode.has("arguments") ? fnNode.path("arguments").asText("{}") : tc.path("arguments").asText("{}");
                fn.arguments(args);
                call.function(fn);
                toolCalls.add(call);
            }
        }

        /**
          * 处理 队列 事件，触发模型降级。
         * 从降级候选中取第一个，重新打开 SSE。
         *
         * @param node 队列 事件 JSON 节点
         */
        private void handleQueue(JsonNode node) {
            List<String> fallbacks = fallbackModelsFor(request.model());
            if (fallbacks.isEmpty() || future == null || future.isDone()) {
                log.warn("[queue] model {} is queued, no fallback available", request.model());
                return;
            }
            String next = fallbacks.get(0);
            log.info("[queue] model {} is queued, falling back to {}", request.model(), next);
            ChatRequest nextReq = withModel(request, next);
            StreamCollector nextCollector = new StreamCollector(nextReq, onText, onReasoning, onComplete, onError);
            nextCollector.future = future;
            openSse(nextReq, nextCollector);
        }

        /**
          * 处理 SSE 内 错误 事件，限流码触发重试，其他错误终止流。
         *
         * @param node 错误 事件 JSON 节点
         */
        private void handleEventError(JsonNode node) {
            String code = node.path("code").asText("");
            if (("4011".equals(code) || "429".equals(code)) && retries.get() < maxRetries) {
                int attempt = retries.incrementAndGet();
                scheduleRetry(request, this, attempt);
                return;
            }
            String msg = node.path("message").asText("stream error");
            failed = true;
            ChatException ex = new ChatException(0, msg);
            if (future != null) {
                future.completeExceptionally(ex);
            }
            if (onError != null) {
                onError.accept(ex);
            }
        }
    }

    /**
      * 客户端 构建器，支持链式配置。
     * 必须通过 {@link ChatClient#builder()} 获取实例。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {
        /** Trae API 主机地址，默认 CN 版 */
        private String apiHost = "https://trae-api-cn.mchost.guru";
        /** Trae 版本：cn（国内）或 sg（国际），默认 cn */
        private String edition = "cn";
        /** Trae 数据目录，storage.json 所在路径，可为 空（使用 manual令牌 时） */
        private String dataDir;
        /** 手动 令牌，JWT 格式，优先于 数据dir 读取 */
        private String manualToken;
        /** App 标识，覆盖默认值 */
        private String appId;
        /** HTTP 代理主机，可为 空（不启用代理） */
        private String httpProxy;
        /** HTTP 代理端口，默认 7890 */
        private int httpProxyPort = 7890;
        /** 最大重试次数，默认 3 */
        private int maxRetries = 3;
        /** 模型分档配置，可为 空 */
        private ModelConfig modelConfig;
        /** 默认模型标识，默认 glm-5.2 */
        private String defaultModel = DEFAULT_MODEL;
        /** 认证管理器，可直接注入以复用已有实例 */
        private AuthManager authManager;
        /** HTTP 客户端，可直接注入以复用已有实例 */
        private TraeHttpClient httpClient;

        /**
         * 设置 Trae API 主机。
         *
         * @param apiHost 主机地址，不可为 空
         * @return 当前 构建器
         */
        public Builder apiHost(String apiHost) {
            this.apiHost = apiHost;
            return this;
        }

        /**
         * 设置 Trae 版本。
         *
         * @param edition 版本标识：cn 或 sg，不可为 空
         * @return 当前 构建器
         */
        public Builder edition(String edition) {
            this.edition = edition;
            return this;
        }

        /**
         * 设置 Trae 数据目录。
         *
         * @param dataDir storage.json 所在目录，可为 空
         * @return 当前 构建器
         */
        public Builder dataDir(String dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        /**
          * 设置手动 令牌。
         *
         * @param manualToken JWT 格式 令牌，可为 空
         * @return 当前 构建器
         */
        public Builder manualToken(String manualToken) {
            this.manualToken = manualToken;
            return this;
        }

        /**
          * 设置 App 标识。
         *
         * @param appId 应用标识，可为 空
         * @return 当前 构建器
         */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        /**
         * 设置 HTTP 代理。
         *
         * @param host 代理主机
         * @param port 代理端口
         * @return 当前 构建器
         */
        public Builder httpProxy(String host, int port) {
            this.httpProxy = host;
            this.httpProxyPort = port;
            return this;
        }

        /**
         * 设置最大重试次数。
         *
         * @param maxRetries 重试上限，必须 >= 0
         * @return 当前 构建器
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * 设置模型分档配置。
         *
         * @param modelConfig 配置对象，可为 空
         * @return 当前 构建器
         */
        public Builder modelConfig(ModelConfig modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * 设置默认模型。
         *
         * @param defaultModel 模型标识，不可为 空
         * @return 当前 构建器
         */
        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * 注入已有认证管理器。
         *
         * @param authManager 认证实例，可为 空（使用默认构造）
         * @return 当前 构建器
         */
        public Builder authManager(AuthManager authManager) {
            this.authManager = authManager;
            return this;
        }

        /**
         * 注入已有 HTTP 客户端。
         *
         * @param httpClient HTTP 实例，可为 空（使用默认构造）
         * @return 当前 构建器
         */
        public Builder httpClient(TraeHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * 构建客户端实例。
         *
         * @return 配置完成的 对话客户端
         * @throws IllegalStateException 当 数据dir 与 manual令牌 均为 空 时
         */
        public ChatClient build() {
            if (dataDir == null && manualToken == null) {
                throw new IllegalStateException("either dataDir or manualToken must be provided");
            }
            if (authManager == null) {
                authManager = new AuthManager(edition, dataDir, manualToken, apiHost);
            }
            if (httpClient == null) {
                TraeHttpClient.Builder hBuilder = new TraeHttpClient.Builder()
                    .authManager(authManager)
                    .apiHost(apiHost);
                if (appId != null) {
                    hBuilder.appId(appId);
                }
                if (httpProxy != null) {
                    hBuilder.httpProxy(httpProxy, httpProxyPort);
                }
                httpClient = hBuilder.build();
            }
            return new ChatClient(authManager, httpClient, modelConfig, maxRetries, defaultModel);
        }
    }

    private ChatClient(AuthManager authManager, TraeHttpClient httpClient,
                       ModelConfig modelConfig, int maxRetries, String defaultModel) {
        this.authManager = authManager;
        this.httpClient = httpClient;
        this.modelConfig = modelConfig;
        this.maxRetries = maxRetries;
        this.defaultModel = defaultModel;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-retry");
            t.setDaemon(true);
            return t;
        });
    }
}