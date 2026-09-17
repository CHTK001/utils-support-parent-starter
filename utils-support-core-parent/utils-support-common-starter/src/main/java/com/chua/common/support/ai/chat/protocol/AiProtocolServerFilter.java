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
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@SuppressWarnings("unchecked")
public class AiProtocolServerFilter extends UrlMappingServerFilter {

    /**
    * 对话分隔符：连接 system 与 user 提示词
    */
    private static final String PROMPT_SEPARATOR = "\n";

    /**
    * 默认模型名
    */
    private static final String DEFAULT_MODEL = "unknown";

    /**
    * 默认 Claude 模型名
    */
    private static final String DEFAULT_CLAUDE_MODEL = "claude-3-haiku-20240307";

    /**
    * OpenAI 默认模型名（路由解析兜底）
    */
    private static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";

    /**
    * 系统角色标识
    */
    private static final String ROLE_SYSTEM = "system";

    /**
    * 用户角色标识
    */
    private static final String ROLE_USER = "user";

    /**
    * 助手角色标识
    */
    private static final String ROLE_ASSISTANT = "assistant";

    /**
    * 请求体中的模型字段名
    */
    private static final String KEY_MODEL = "model";

    /**
    * 请求体中的流式标识字段名
    */
    private static final String KEY_STREAM = "stream";

    /**
    * 请求体中的消息列表字段名
    */
    private static final String KEY_MESSAGES = "messages";

    /**
    * 请求体中的角色字段名
    */
    private static final String KEY_ROLE = "role";

    /**
    * 请求体/响应体中的内容字段名
    */
    private static final String KEY_CONTENT = "content";

    /**
    * 请求体中的提示词字段名
    */
    private static final String KEY_PROMPT = "prompt";

    /**
    * 响应体中的标识字段名
    */
    private static final String KEY_ID = "id";

    /**
    * 响应体中的对象类型字段名
    */
    private static final String KEY_OBJECT = "object";

    /**
    * 响应体中的创建时间字段名
    */
    private static final String KEY_CREATED = "created";

    /**
    * 响应体中的选择列表字段名
    */
    private static final String KEY_CHOICES = "choices";

    /**
    * 响应体中的索引字段名
    */
    private static final String KEY_INDEX = "index";

    /**
    * 响应体中的消息字段名
    */
    private static final String KEY_MESSAGE = "message";

    /**
    * 响应体中的结束原因字段名
    */
    private static final String KEY_FINISH_REASON = "finish_reason";

    /**
    * 响应体中的用量字段名
    */
    private static final String KEY_USAGE = "usage";

    /**
    * 响应体中的文本字段名
    */
    private static final String KEY_TEXT = "text";

    /**
    * 响应体中的状态字段名
    */
    private static final String KEY_STATUS = "status";

    /**
    * 响应体中的类型字段名
    */
    private static final String KEY_TYPE = "type";

    /**
    * 响应体中的输出字段名
    */
    private static final String KEY_OUTPUT = "output";

    /**
    * 响应体中的数据列表字段名
    */
    private static final String KEY_DATA = "data";

    /**
    * 响应体中的错误字段名
    */
    private static final String KEY_ERROR = "error";

    /**
    * 响应体中的错误码字段名
    */
    private static final String KEY_CODE = "code";

    /**
    * 响应体中的错误信息字段名
    */
    private static final String KEY_MESSAGE_ERROR = "message";

    /**
    * 响应体中的错误状态字段名
    */
    private static final String KEY_ERROR_STATUS = "status";

    /**
    * OpenAI 聊天补全对象类型
    */
    private static final String OBJECT_CHAT_COMPLETION = "chat.completion";

    /**
    * OpenAI 聊天补全流式块对象类型
    */
    private static final String OBJECT_CHAT_COMPLETION_CHUNK = "chat.completion.chunk";

    /**
    * OpenAI 文本补全对象类型
    */
    private static final String OBJECT_TEXT_COMPLETION = "text_completion";

    /**
    * OpenAI 模型列表对象类型
    */
    private static final String OBJECT_LIST = "list";

    /**
    * OpenAI 模型对象类型
    */
    private static final String OBJECT_MODEL = "model";

    /**
    * CCS 响应对象类型
    */
    private static final String OBJECT_RESPONSE = "response";

    /**
    * Claude 消息对象类型
    */
    private static final String OBJECT_MESSAGE = "message";

    /**
    * Claude 文本内容块类型
    */
    private static final String CONTENT_BLOCK_TEXT = "text";

    /**
    * CCS 输出文本内容类型
    */
    private static final String CONTENT_OUTPUT_TEXT = "output_text";

    /**
    * CCS 文本增量内容类型
    */
    private static final String CONTENT_TEXT_DELTA = "text_delta";

    /**
    * 结束原因：stop
    */
    private static final String FINISH_STOP = "stop";

    /**
    * 结束原因：end_turn
    */
    private static final String FINISH_END_TURN = "end_turn";

    /**
    * 状态：进行中
    */
    private static final String STATUS_IN_PROGRESS = "in_progress";

    /**
    * 状态：已完成
    */
    private static final String STATUS_COMPLETED = "completed";

    /**
    * Gemini 结束原因：STOP
    */
    private static final String GEMINI_FINISH_STOP = "STOP";

    /**
    * Gemini 路径前缀
    */
    private static final String GEMINI_PATH_PREFIX = "/v1beta/models/";

    /**
    * Gemini 错误状态：非法参数
    */
    private static final String GEMINI_STATUS_INVALID_ARGUMENT = "INVALID_ARGUMENT";

    /**
    * Gemini 错误状态：内部错误
    */
    private static final String GEMINI_STATUS_INTERNAL = "INTERNAL";

    /**
    * SSE 事件：ready
    */
    private static final String SSE_EVENT_READY = "ready";

    /**
    * SSE 结束标记
    */
    private static final String SSE_DONE = "[DONE]";

    /**
    * OpenAI Chat 补全标识前缀
    */
    private static final String PREFIX_CHAT_COMPLETION = "chatcmpl-";

    /**
    * OpenAI 文本补全标识前缀
    */
    private static final String PREFIX_COMPLETION = "cmpl-";

    /**
    * 消息标识前缀
    */
    private static final String PREFIX_MESSAGE = "msg_";

    /**
    * CCS 响应标识前缀
    */
    private static final String PREFIX_RESPONSE = "resp_";

    /**
    * OpenAI 路由路径：Chat Completions
    */
    private static final String ROUTE_CHAT_COMPLETIONS = "/v1/chat/completions";

    /**
    * OpenAI 路由路径：Completions
    */
    private static final String ROUTE_COMPLETIONS = "/v1/completions";

    /**
    * OpenAI 路由路径：Models
    */
    private static final String ROUTE_MODELS = "/v1/models";

    /**
    * Claude 路由路径：Messages
    */
    private static final String ROUTE_MESSAGES = "/v1/messages";

    /**
    * CCS 路由路径：Responses（版本化）
    */
    private static final String ROUTE_RESPONSES_V1 = "/v1/responses";

    /**
    * CCS 路由路径：Responses
    */
    private static final String ROUTE_RESPONSES = "/responses";

    /**
    * Gemini 路由路径（Ant 风格通配匹配）
    */
    private static final String ROUTE_GEMINI = "/v1beta/models/**";

    /**
    * Gemini 路由动作：流式生成内容
    */
    private static final String GEMINI_ACTION_STREAM_GENERATE_CONTENT = "streamGenerateContent";

    /**
    * JSON 内容类型
    */
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

    /**
    * 网关日志前缀
    */
    private static final String LOG_PREFIX = "[AiProtocolServerFilter] ";

    /**
    * 协议日志前缀
    */
    private static final String LOG_PROTOCOL_PREFIX = "[AiProtocol] ";

    /**
    * OpenAI 模型列表默认条目标识
    */
    private static final String MODELS_FALLBACK_ID = "default";

    /**
    * 模型归属方
    */
    private static final String OWNED_BY_SYSTEM = "system";

    /**
    * CCS 分块输出单块大小
    */
    private static final int CCS_CHUNK_SIZE = 80;

    /**
    * 生成的标识截取长度
    */
    private static final int ID_TRUNCATE_LENGTH = 12;

    /**
    * HTTP 状态码：400 参数错误
    */
    private static final int HTTP_BAD_REQUEST = 400;

    /**
    * HTTP 状态码：500 服务器内部错误
    */
    private static final int HTTP_INTERNAL_ERROR = 500;

    /**
    * 过滤器注册顺序
    */
    private static final int FILTER_ORDER = 150;

    /**
    * 底层 AI 客户端
    */
    private final ChatClient chatClient;

    /**
    * 关联的 AiTokenServerFilter（当 ChatClient 配置了 tokenProvider 时自动创建）
    */
    private AiTokenServerFilter tokenFilter;

    /**
    * 构造 AiProtocolServerFilter，使用默认对象上下文。
    *
    * @param chatClient 底层 AI 客户端
    */
    public AiProtocolServerFilter(ChatClient chatClient) {
        this(chatClient, new DefaultObjectContext());
    }

    /**
    * 构造 AiProtocolServerFilter，注册全部 AI 协议路由并初始化 token 过滤器。
    *
    * @param chatClient    底层 AI 客户端
    * @param objectContext 对象上下文
    */
    public AiProtocolServerFilter(ChatClient chatClient, ObjectContext objectContext) {
        super(objectContext);
        this.chatClient = chatClient;
        registerRoutes();
        initTokenFilter(chatClient);
    }

    /**
    * 如果 ChatClient 是 {@link AggregateChatClient} 且设置了 {@link AiTokenProvider}，
    * 自动创建 {@link AiTokenServerFilter}。
    *
    * @param client 底层 AI 客户端
    */
    private void initTokenFilter(ChatClient client) {
        if (!(client instanceof AggregateChatClient)) {
            return;
        }
        AiTokenProvider provider = ((AggregateChatClient) client).getTokenProvider();
        if (provider != null && provider.count() > 0) {
            this.tokenFilter = new AiTokenServerFilter(provider);
            log.info("{}从 AggregateChatClient 加载令牌提供者: {}", LOG_PREFIX, provider.getClass().getSimpleName());
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
    *
    * @param request 当前请求
    */
    private void applyTokenGroup(ServerRequest request) {
        if (!(chatClient instanceof AggregateChatClient)) {
            return;
        }
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

    /**
    * 注册全部 AI 协议 RESTful 路由。
    */
    private void registerRoutes() {
        // OpenAI
        route(ROUTE_CHAT_COMPLETIONS, HttpMethod.POST, this::handleChatCompletions);
        route(ROUTE_COMPLETIONS, HttpMethod.POST, this::handleCompletions);
        route(ROUTE_MODELS, HttpMethod.GET, this::handleModels);

        // Claude
        route(ROUTE_MESSAGES, HttpMethod.POST, this::handleMessages);

        // CCS / Responses
        route(ROUTE_RESPONSES_V1, HttpMethod.POST, this::handleResponses);
        route(ROUTE_RESPONSES, HttpMethod.POST, this::handleResponses);

        // Gemini（path pattern 由 UrlMappingServerFilter 的 Ant 风格匹配支持）
        route(ROUTE_GEMINI, HttpMethod.POST, this::handleGemini);

        log.info("{}AI 协议路由注册完成", LOG_PREFIX);
    }

    // ==================== OpenAI Chat Completions ====================

    /**
    * 处理 OpenAI Chat Completions 请求。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    private void handleChatCompletions(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        boolean stream = Boolean.TRUE.equals(body.get(KEY_STREAM));
        String model = (String) body.get(KEY_MODEL);

        if (stream) {
            handleOpenAiStream(request, response, body, model);
            return;
        }

        String prompt = extractOpenAiPrompt(body);
        String system = extractOpenAiSystem(body);
        String fullPrompt = system != null ? system + PROMPT_SEPARATOR + prompt : prompt;

        applyTokenGroup(request);
        try {
            ChatSyncResponse result = chatClient.chatSyncWithResponse(fullPrompt);
            writeJson(response, buildOpenAiChatResponse(result, model));
        } catch (Exception ex) {
            log.error("{}chat completions failed: {}", LOG_PROTOCOL_PREFIX, ex.getMessage());
            writeJson(response, buildOpenAiChatError(model, ex.getMessage()));
        } finally {
            clearTokenGroup();
        }
    }

    /**
    * 处理 OpenAI Chat Completions 流式请求（SSE）。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @param body     请求体
    * @param model    模型名
    * @throws Exception 处理异常
    */
    private void handleOpenAiStream(ServerRequest request, ServerResponse response, Map<String, Object> body, String model) throws Exception {
        response.sse();
        String prompt = extractOpenAiPrompt(body);
        String system = extractOpenAiSystem(body);
        String fullPrompt = system != null ? system + PROMPT_SEPARATOR + prompt : prompt;

        // 发送 ready 事件
        response.sseEvent(SSE_EVENT_READY, SSE_EVENT_READY);

        applyTokenGroup(request);
        chatClient.chat(fullPrompt, new Consumer<ChatResponse>() {
            @Override
            /** Accept */
            public void accept(ChatResponse cr) {
                if (cr.getState() == ChatResponse.State.STREAMING && cr.getContent() != null) {
                    Map<String, Object> delta = buildOpenAiStreamDelta(cr.getContent(), model);
                    response.sseEvent(null, Json.toJson(delta));
                }
            }
        }, () -> {
            response.sseEvent(null, SSE_DONE);
            response.flush();
            response.sseClose();
            clearTokenGroup();
        }, error -> {
            log.error("{}stream error: {}", LOG_PROTOCOL_PREFIX, error.getMessage());
            response.sseEvent(KEY_ERROR, error.getMessage());
            response.sseEvent(null, SSE_DONE);
            response.flush();
            response.sseClose();
            clearTokenGroup();
        });
    }

    // ==================== OpenAI Completions ====================

    /**
    * 处理 OpenAI Completions 请求。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    private void handleCompletions(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        String model = (String) body.get(KEY_MODEL);
        String prompt = (String) body.get(KEY_PROMPT);

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

    /**
    * 处理 OpenAI Models 列表请求。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    private void handleModels(ServerRequest request, ServerResponse response) throws Exception {
        List<Map<String, Object>> data = new ArrayList<>(); // [P3C 3.15 豁免] 模型列表遍历自框架动态 models()，数量运行期不可预估
        try {
            chatClient.models().forEach(md -> {
                data.add(Map.of(
                        KEY_ID, md.getName() != null ? md.getName() : DEFAULT_MODEL,
                        KEY_OBJECT, OBJECT_MODEL,
                        KEY_CREATED, (int) (System.currentTimeMillis() / 1000),
                        "owned_by", OWNED_BY_SYSTEM
                ));
            });
        } catch (Exception e) {
            log.debug("{}models() failed: {}", LOG_PROTOCOL_PREFIX, e.getMessage());
        }
        if (data.isEmpty()) {
            data.add(Map.of(
                    KEY_ID, MODELS_FALLBACK_ID,
                    KEY_OBJECT, OBJECT_MODEL,
                    KEY_CREATED, (int) (System.currentTimeMillis() / 1000),
                    "owned_by", OWNED_BY_SYSTEM
            ));
        }
        writeJson(response, Map.of(KEY_OBJECT, OBJECT_LIST, KEY_DATA, data));
    }

    // ==================== Claude Messages ====================

    /**
    * 处理 Claude Messages 请求。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    private void handleMessages(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        String model = (String) body.get(KEY_MODEL);
        boolean stream = Boolean.TRUE.equals(body.get(KEY_STREAM));

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

    /**
    * 处理 Claude Messages 流式请求（SSE）。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @param body     请求体
    * @param model    模型名
    * @throws Exception 处理异常
    */
    private void handleClaudeStream(ServerRequest request, ServerResponse response, Map<String, Object> body, String model) throws Exception {
        response.sse();
        String prompt = extractClaudePrompt(body);

        response.sseEvent("message_start", Json.toJson(Map.of(
                KEY_TYPE, "message_start",
                KEY_MESSAGE, buildClaudeStartResponse(model)
        )));
        response.sseEvent("content_block_start", Json.toJson(Map.of(
                KEY_TYPE, "content_block_start",
                KEY_INDEX, 0,
                "content_block", Map.of(KEY_TYPE, CONTENT_BLOCK_TEXT, KEY_TEXT, "")
        )));

        applyTokenGroup(request);
        chatClient.chat(prompt, cr -> {
            if (cr.getState() == ChatResponse.State.STREAMING && cr.getContent() != null) {
                response.sseEvent("content_block_delta", Json.toJson(Map.of(
                        KEY_TYPE, "content_block_delta",
                        KEY_INDEX, 0,
                        "delta", Map.of(KEY_TYPE, CONTENT_TEXT_DELTA, KEY_TEXT, cr.getContent())
                )));
            }
        }, () -> {
            response.sseEvent("content_block_stop", Json.toJson(Map.of(KEY_TYPE, "content_block_stop", KEY_INDEX, 0)));
            response.sseEvent("message_delta", Json.toJson(Map.of(
                    KEY_TYPE, "message_delta",
                    "delta", Map.of("stop_reason", FINISH_END_TURN, "stop_sequence", null),
                    KEY_USAGE, Map.of("output_tokens", 0)
            )));
            response.sseEvent("message_stop", Json.toJson(Map.of(KEY_TYPE, "message_stop")));
            response.flush();
            response.sseClose();
            clearTokenGroup();
        }, error -> {
            log.error("{}claude stream error: {}", LOG_PROTOCOL_PREFIX, error.getMessage());
            response.sseEvent(KEY_ERROR, error.getMessage());
            response.flush();
            response.sseClose();
            clearTokenGroup();
        });
    }

    // ==================== CCS /responses ====================

    /**
    * 处理 CCS /responses 请求。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    private void handleResponses(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> body = parseBody(request);
        String model = (String) body.get(KEY_MODEL);
        boolean stream = Boolean.TRUE.equals(body.get(KEY_STREAM));

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
            Map<String, Object> errorResult = new LinkedHashMap<>(2);
            errorResult.put(KEY_TEXT, ex.getMessage());
            if (stream) {
                handleResponsesStream(response, errorResult, model);
                return;
            }
            writeJson(response, buildResponsesResponse(errorResult, model));
        } finally {
            clearTokenGroup();
        }
    }

    /**
    * 处理 CCS 响应流式输出（SSE 事件序列）。
    *
    * @param response 响应对象
    * @param result   AI 调用结果（Map 时读取 text 字段）
    * @param model    模型名
    * @throws Exception 处理异常
    */
    private void handleResponsesStream(ServerResponse response, Object result, String model) throws Exception {
        response.sse();
        String responseId = PREFIX_RESPONSE + UUID.randomUUID().toString().replace("-", "");
        String messageId = PREFIX_MESSAGE + UUID.randomUUID().toString().replace("-", "");
        String text = "";
        if (result instanceof ChatSyncResponse chatSyncResponse) {
            text = chatSyncResponse.getText();
        } else if (result instanceof Map<?, ?> map) {
            text = (String) map.get(KEY_TEXT);
        }

        // 初始 SSE 事件序列
        Map<String, Object> started = buildCcsResponse(responseId, messageId, model, "", STATUS_IN_PROGRESS);
        started.put(KEY_OUTPUT, List.of());

        response.sseEvent("response.created", Json.toJson(Map.of(
                KEY_TYPE, "response.created", "response", started
        )));
        response.sseEvent("response.output_item.added", Json.toJson(Map.of(
                KEY_TYPE, "response.output_item.added", "output_index", 0,
                "item", buildCcsMessage(messageId, "", STATUS_IN_PROGRESS)
        )));
        response.sseEvent("response.content_part.added", Json.toJson(Map.of(
                KEY_TYPE, "response.content_part.added",
                "item_id", messageId, "output_index", 0, "content_index", 0,
                "part", Map.of(KEY_TYPE, CONTENT_OUTPUT_TEXT, KEY_TEXT, "", "annotations", List.of())
        )));

        // 流式输出文本
        if (text != null && !text.isEmpty()) {
            for (int i = 0; i < text.length(); i += CCS_CHUNK_SIZE) {
                int end = Math.min(i + CCS_CHUNK_SIZE, text.length());
                String chunk = text.substring(i, end);
                response.sseEvent("response.output_text.delta", Json.toJson(Map.of(
                        KEY_TYPE, "response.output_text.delta",
                        "item_id", messageId, "output_index", 0, "content_index", 0,
                        "delta", chunk
                )));
            }
        }

        // 完成事件
        response.sseEvent("response.output_text.done", Json.toJson(Map.of(
                KEY_TYPE, "response.output_text.done",
                "item_id", messageId, "output_index", 0, "content_index", 0,
                KEY_TEXT, text != null ? text : ""
        )));
        response.sseEvent("response.content_part.done", Json.toJson(Map.of(
                KEY_TYPE, "response.content_part.done",
                "item_id", messageId, "output_index", 0, "content_index", 0,
                "part", Map.of(KEY_TYPE, CONTENT_OUTPUT_TEXT, KEY_TEXT, text != null ? text : "", "annotations", List.of())
        )));
        response.sseEvent("response.output_item.done", Json.toJson(Map.of(
                KEY_TYPE, "response.output_item.done", "output_index", 0,
                "item", buildCcsMessage(messageId, text != null ? text : "", STATUS_COMPLETED)
        )));
        response.sseEvent("response.completed", Json.toJson(Map.of(
                KEY_TYPE, "response.completed",
                "response", buildCcsResponse(responseId, messageId, model, text != null ? text : "", STATUS_COMPLETED)
        )));
        response.flush();
        response.sseClose();
    }

    // ==================== Gemini ====================

    /**
    * 处理 Gemini 请求。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    private void handleGemini(ServerRequest request, ServerResponse response) throws Exception {
        String fullPath = request.getPath();
        String rest = fullPath.startsWith(GEMINI_PATH_PREFIX) ? fullPath.substring(GEMINI_PATH_PREFIX.length()) : fullPath;
        int colonIdx = rest.lastIndexOf(':');
        if (colonIdx < 0) {
            writeJson(response, Map.of(KEY_ERROR, Map.of(KEY_CODE, HTTP_BAD_REQUEST, KEY_MESSAGE_ERROR, "Invalid Gemini path: " + fullPath, KEY_ERROR_STATUS, GEMINI_STATUS_INVALID_ARGUMENT)));
            return;
        }
        String model = rest.substring(0, colonIdx);
        String action = rest.substring(colonIdx + 1);
        boolean isStream = GEMINI_ACTION_STREAM_GENERATE_CONTENT.equals(action);

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
                response.sseEvent(null, SSE_DONE);
                response.flush();
                response.sseClose();
                return;
            }

            Map<String, Object> geminiResp = new LinkedHashMap<>(3);
            geminiResp.put("candidates", List.of(buildGeminiCandidate(model, text)));
            Map<String, Object> usage = new LinkedHashMap<>(5);
            usage.put("promptTokenCount", 0);
            usage.put("candidatesTokenCount", 0);
            usage.put("totalTokenCount", 0);
            geminiResp.put("usageMetadata", usage);
            writeJson(response, geminiResp);
        } catch (Exception ex) {
            writeJson(response, Map.of(KEY_ERROR, Map.of(KEY_CODE, HTTP_INTERNAL_ERROR, KEY_MESSAGE_ERROR, ex.getMessage(), KEY_ERROR_STATUS, GEMINI_STATUS_INTERNAL)));
        } finally {
            clearTokenGroup();
        }
    }

    // ==================== Prompt 提取 ====================

    /**
    * 从 OpenAI Chat 请求体中提取用户消息拼接的提示词。
    *
    * @param body 请求体
    * @return 拼接后的提示词，无用户消息时返回空串
    */
    private String extractOpenAiPrompt(Map<String, Object> body) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get(KEY_MESSAGES);
        if (messages == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get(KEY_ROLE);
            Object content = msg.get(KEY_CONTENT);
            if (ROLE_USER.equals(role) && content != null) {
                sb.append(extractText(content)).append(PROMPT_SEPARATOR);
            }
        }
        return sb.toString().trim();
    }

    /**
    * 从 OpenAI Chat 请求体中提取系统提示词。
    *
    * @param body 请求体
    * @return 系统提示词，未配置时返回 null
    */
    private String extractOpenAiSystem(Map<String, Object> body) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get(KEY_MESSAGES);
        if (messages == null) {
            return null;
        }
        for (Map<String, Object> msg : messages) {
            if (ROLE_SYSTEM.equals(msg.get(KEY_ROLE))) {
                return extractText(msg.get(KEY_CONTENT));
            }
        }
        return null;
    }

    /**
    * 从 Claude Messages 请求体中提取用户消息拼接的提示词。
    *
    * @param body 请求体
    * @return 拼接后的提示词
    */
    private String extractClaudePrompt(Map<String, Object> body) {
        String system = extractText(body.get(ROLE_SYSTEM));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get(KEY_MESSAGES);
        StringBuilder sb = new StringBuilder();
        if (system != null) {
            sb.append(system).append(PROMPT_SEPARATOR);
        }
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                if (ROLE_USER.equals(msg.get(KEY_ROLE))) {
                    sb.append(extractText(msg.get(KEY_CONTENT))).append(PROMPT_SEPARATOR);
                }
            }
        }
        return sb.toString().trim();
    }

    /**
    * 从 CCS Responses 请求体中提取提示词。
    *
    * @param body 请求体
    * @return 拼接后的提示词
    */
    private String extractResponsesPrompt(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        appendText(sb, body.get("instructions"));
        Object input = body.get("input");
        if (input instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    if ("message".equals(m.get(KEY_TYPE)) || m.containsKey(KEY_ROLE)) {
                        appendText(sb, m.get(KEY_CONTENT));
                    }
                }
            }
        }
        appendText(sb, body.get(KEY_PROMPT));
        return sb.toString().trim();
    }

    /**
    * 从 Gemini 请求体中提取提示词。
    *
    * @param body 请求体
    * @return 拼接后的提示词
    */
    private String extractGeminiPrompt(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        Object systemInstruction = body.get("systemInstruction");
        if (systemInstruction instanceof Map) {
            String text = extractPartsText((Map<?, ?>) systemInstruction);
            if (text != null) {
                sb.insert(0, text + PROMPT_SEPARATOR);
            }
        }
        Object contents = body.get("contents");
        if (contents instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String text = extractPartsText(m);
                    if (text != null) {
                        sb.append(text).append(PROMPT_SEPARATOR);
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    /**
    * 从 Gemini parts 结构中提取第一个非空文本。
    *
    * @param obj Gemini 内容对象
    * @return 第一个非空文本，不存在时返回 null
    */
    private String extractPartsText(Map<?, ?> obj) {
        Object parts = obj.get("parts");
        if (parts instanceof List<?> list) {
            for (Object part : list) {
                if (part instanceof Map<?, ?> m) {
                    String text = (String) m.get(KEY_TEXT);
                    if (text != null && !text.isEmpty()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    /**
    * 将可提取文本追加到字符串构建器。
    *
    * @param sb    字符串构建器
    * @param value 待提取文本的值
    */
    private void appendText(StringBuilder sb, Object value) {
        String text = extractText(value);
        if (text != null && !text.isBlank()) {
            sb.append(text.trim()).append('\n');
        }
    }

    /**
    * 从异构结构（String / List / Map）中提取纯文本。
    *
    * @param value 待提取的值
    * @return 提取后的文本，值为 null 时返回 null
    */
    private String extractText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                String t = extractText(item);
                if (t != null && !t.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(t.trim());
                }
            }
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            String t = (String) map.get(KEY_TEXT);
            if (t != null) {
                return t;
            }
            Object c = map.get(KEY_CONTENT);
            if (c != null) {
                return extractText(c);
            }
            Object p = map.get("parts");
            if (p != null) {
                return extractText(p);
            }
        }
        return String.valueOf(value);
    }

    // ==================== Response Builders ====================

    /**
    * 构建 OpenAI Chat 补全响应体。
    *
    * @param result AI 同步调用结果
    * @param model  模型名
    * @return 响应体
    */
    private Map<String, Object> buildOpenAiChatResponse(ChatSyncResponse result, String model) {
        String id = PREFIX_CHAT_COMPLETION + UUID.randomUUID().toString().replace("-", "").substring(0, ID_TRUNCATE_LENGTH);
        String text = result != null ? result.getText() : "";
        String resolvedModel = model != null ? model : DEFAULT_MODEL;

        Map<String, Object> message = new LinkedHashMap<>(3);
        message.put(KEY_ROLE, ROLE_ASSISTANT);
        message.put(KEY_CONTENT, text != null ? text : "");

        Map<String, Object> choice = new LinkedHashMap<>(5);
        choice.put(KEY_INDEX, 0);
        choice.put(KEY_MESSAGE, message);
        choice.put(KEY_FINISH_REASON, FINISH_STOP);

        Map<String, Object> body = new LinkedHashMap<>(9);
        body.put(KEY_ID, id);
        body.put(KEY_OBJECT, OBJECT_CHAT_COMPLETION);
        body.put(KEY_CREATED, (int) (System.currentTimeMillis() / 1000));
        body.put(KEY_MODEL, resolvedModel);
        body.put(KEY_CHOICES, List.of(choice));

        if (result != null && result.getUsage() != null) {
            body.put(KEY_USAGE, Map.of(
                    "prompt_tokens", result.getUsage().getInputTokens() != null ? result.getUsage().getInputTokens() : 0,
                    "completion_tokens", result.getUsage().getOutputTokens() != null ? result.getUsage().getOutputTokens() : 0,
                    "total_tokens", result.getUsage().getTotalTokens() != null ? result.getUsage().getTotalTokens() : 0
            ));
        }
        return body;
    }

    /**
    * 构建 OpenAI Chat 补全错误响应体。
    *
    * @param model        模型名
    * @param errorMessage 错误信息
    * @return 响应体
    */
    private Map<String, Object> buildOpenAiChatError(String model, String errorMessage) {
        String id = PREFIX_CHAT_COMPLETION + UUID.randomUUID().toString().replace("-", "").substring(0, ID_TRUNCATE_LENGTH);
        Map<String, Object> message = new LinkedHashMap<>(3);
        message.put(KEY_ROLE, ROLE_ASSISTANT);
        message.put(KEY_CONTENT, errorMessage);
        Map<String, Object> choice = new LinkedHashMap<>(5);
        choice.put(KEY_INDEX, 0);
        choice.put(KEY_MESSAGE, message);
        choice.put(KEY_FINISH_REASON, FINISH_STOP);
        Map<String, Object> body = new LinkedHashMap<>(7);
        body.put(KEY_ID, id);
        body.put(KEY_OBJECT, OBJECT_CHAT_COMPLETION);
        body.put(KEY_CREATED, (int) (System.currentTimeMillis() / 1000));
        body.put(KEY_MODEL, model != null ? model : DEFAULT_MODEL);
        body.put(KEY_CHOICES, List.of(choice));
        return body;
    }

    /**
    * 构建 OpenAI Chat 流式输出增量块。
    *
    * @param chunk 文本增量
    * @param model 模型名
    * @return 增量块体
    */
    private Map<String, Object> buildOpenAiStreamDelta(String chunk, String model) {
        Map<String, Object> delta = new LinkedHashMap<>(6);
        delta.put(KEY_ID, PREFIX_CHAT_COMPLETION + UUID.randomUUID().toString().replace("-", "").substring(0, ID_TRUNCATE_LENGTH));
        delta.put(KEY_OBJECT, OBJECT_CHAT_COMPLETION_CHUNK);
        delta.put(KEY_CREATED, (int) (System.currentTimeMillis() / 1000));
        delta.put(KEY_MODEL, model != null ? model : DEFAULT_MODEL);
        Map<String, Object> choice = new LinkedHashMap<>(5);
        choice.put(KEY_INDEX, 0);
        choice.put("delta", Map.of(KEY_CONTENT, chunk));
        choice.put(KEY_FINISH_REASON, null);
        delta.put(KEY_CHOICES, List.of(choice));
        return delta;
    }

    /**
    * 构建 OpenAI 文本补全响应体。
    *
    * @param result AI 同步调用结果
    * @param model  模型名
    * @param prompt 原始提示词
    * @return 响应体
    */
    private Map<String, Object> buildOpenAiCompletionResponse(ChatSyncResponse result, String model, String prompt) {
        String id = PREFIX_COMPLETION + UUID.randomUUID().toString().replace("-", "").substring(0, ID_TRUNCATE_LENGTH);
        String text = result != null ? result.getText() : "";
        String resolvedModel = model != null ? model : DEFAULT_MODEL;

        Map<String, Object> choice = new LinkedHashMap<>(5);
        choice.put(KEY_TEXT, text != null ? text : "");
        choice.put(KEY_INDEX, 0);
        choice.put(KEY_FINISH_REASON, FINISH_STOP);

        Map<String, Object> body = new LinkedHashMap<>(9);
        body.put(KEY_ID, id);
        body.put(KEY_OBJECT, OBJECT_TEXT_COMPLETION);
        body.put(KEY_CREATED, (int) (System.currentTimeMillis() / 1000));
        body.put(KEY_MODEL, resolvedModel);
        body.put(KEY_CHOICES, List.of(choice));
        if (result != null && result.getUsage() != null) {
            body.put(KEY_USAGE, Map.of(
                    "prompt_tokens", result.getUsage().getInputTokens() != null ? result.getUsage().getInputTokens() : 0,
                    "completion_tokens", result.getUsage().getOutputTokens() != null ? result.getUsage().getOutputTokens() : 0,
                    "total_tokens", result.getUsage().getTotalTokens() != null ? result.getUsage().getTotalTokens() : 0
            ));
        }
        return body;
    }

    /**
    * 构建 OpenAI 文本补全错误响应体。
    *
    * @param model        模型名
    * @param prompt       原始提示词
    * @param errorMessage 错误信息
    * @return 响应体
    */
    private Map<String, Object> buildOpenAiCompletionError(String model, String prompt, String errorMessage) {
        String id = PREFIX_COMPLETION + UUID.randomUUID().toString().replace("-", "").substring(0, ID_TRUNCATE_LENGTH);
        Map<String, Object> choice = new LinkedHashMap<>(5);
        choice.put(KEY_TEXT, errorMessage);
choice.put(KEY_INDEX, 0);
        choice.put(KEY_FINISH_REASON, FINISH_STOP);
        Map<String, Object> body = new LinkedHashMap<>(7);
        body.put(KEY_ID, id);
        body.put(KEY_OBJECT, OBJECT_CHAT_COMPLETION);
        body.put(KEY_CREATED, (int) (System.currentTimeMillis() / 1000));
        body.put(KEY_MODEL, model != null ? model : DEFAULT_MODEL);
        body.put(KEY_CHOICES, List.of(choice));
        return body;
    }

    /**
    * 构建 Claude Messages 响应体。
    *
    * @param result AI 同步调用结果
    * @param model  模型名
    * @return 响应体
    */
    private Map<String, Object> buildClaudeResponse(ChatSyncResponse result, String model) {
        String id = PREFIX_MESSAGE + UUID.randomUUID().toString().replace("-", "");
        String text = result != null ? result.getText() : "";
        String resolvedModel = model != null ? model : DEFAULT_CLAUDE_MODEL;

        Map<String, Object> body = new LinkedHashMap<>(11);
        body.put(KEY_ID, id);
        body.put(KEY_TYPE, OBJECT_MESSAGE);
        body.put(KEY_ROLE, ROLE_ASSISTANT);
        body.put(KEY_MODEL, resolvedModel);
        body.put(KEY_CONTENT, List.of(Map.of(KEY_TYPE, CONTENT_BLOCK_TEXT, KEY_TEXT, text != null ? text : "")));
        body.put("stop_reason", FINISH_END_TURN);
        body.put("stop_sequence", null);
        body.put(KEY_USAGE, Map.of(
                "input_tokens", 0,
                "output_tokens", 0
        ));
        return body;
    }

    /**
    * 构建 Claude 流式起始消息。
    *
    * @param model 模型名
    * @return 消息体
    */
    private Map<String, Object> buildClaudeStartResponse(String model) {
        String id = PREFIX_MESSAGE + UUID.randomUUID().toString().replace("-", "");
        String resolvedModel = model != null ? model : DEFAULT_CLAUDE_MODEL;
        Map<String, Object> body = new LinkedHashMap<>(10);
        body.put(KEY_ID, id);
        body.put(KEY_TYPE, OBJECT_MESSAGE);
        body.put(KEY_ROLE, ROLE_ASSISTANT);
        body.put(KEY_MODEL, resolvedModel);
        body.put(KEY_CONTENT, List.of());
        body.put("stop_reason", null);
        body.put("stop_sequence", null);
        body.put(KEY_USAGE, Map.of("input_tokens", 0, "output_tokens", 0));
        return body;
    }

    /**
    * 构建 Claude Messages 错误响应体。
    *
    * @param model        模型名
    * @param errorMessage 错误信息
    * @return 响应体
    */
    private Map<String, Object> buildClaudeError(String model, String errorMessage) {
        String id = PREFIX_MESSAGE + UUID.randomUUID().toString().replace("-", "");
        String resolvedModel = model != null ? model : DEFAULT_CLAUDE_MODEL;
        Map<String, Object> body = new LinkedHashMap<>(9);
        body.put(KEY_ID, id);
        body.put(KEY_TYPE, OBJECT_MESSAGE);
        body.put(KEY_ROLE, ROLE_ASSISTANT);
        body.put(KEY_MODEL, resolvedModel);
        body.put(KEY_CONTENT, List.of(Map.of(KEY_TYPE, CONTENT_BLOCK_TEXT, KEY_TEXT, errorMessage)));
        body.put("stop_reason", FINISH_END_TURN);
        body.put("stop_sequence", null);
        return body;
    }

    /**
    * 构建 CCS Responses 响应体。
    *
    * @param result AI 调用结果（Map 时读取 text 字段）
    * @param model  模型名
    * @return 响应体
    */
    private Map<String, Object> buildResponsesResponse(Object result, String model) {
        String responseId = PREFIX_RESPONSE + UUID.randomUUID().toString().replace("-", "");
        String messageId = PREFIX_MESSAGE + UUID.randomUUID().toString().replace("-", "");
        String text = "";
        if (result instanceof ChatSyncResponse chatSyncResponse) {
            text = chatSyncResponse.getText();
        } else if (result instanceof Map<?, ?> map) {
            text = (String) map.get(KEY_TEXT);
        }
        return buildCcsResponse(responseId, messageId, model, text != null ? text : "", STATUS_COMPLETED);
    }

    /**
    * 构建 CCS 响应体。
    *
    * @param responseId 响应标识
    * @param messageId  消息标识
    * @param model      模型名
    * @param text       文本内容
    * @param status     状态
    * @return 响应体
    */
    private Map<String, Object> buildCcsResponse(String responseId, String messageId, String model, String text, String status) {
        String resolvedModel = model != null ? model : DEFAULT_MODEL;
        Map<String, Object> body = new LinkedHashMap<>(13);
        body.put(KEY_ID, responseId);
        body.put(KEY_OBJECT, OBJECT_RESPONSE);
        body.put("created_at", (int) (System.currentTimeMillis() / 1000));
        body.put(KEY_STATUS, status);
        body.put(KEY_MODEL, resolvedModel);
        body.put(KEY_OUTPUT, List.of(buildCcsMessage(messageId, text, status)));
        body.put("output_text", text);
        body.put("parallel_tool_calls", true);
        body.put(KEY_USAGE, Map.of("input_tokens", 0, "output_tokens", 0, "total_tokens", 0));
        return body;
    }

    /**
    * 构建 CCS 消息体。
    *
    * @param messageId 消息标识
    * @param text      文本内容
    * @param status    状态
    * @return 消息体
    */
    private Map<String, Object> buildCcsMessage(String messageId, String text, String status) {
        Map<String, Object> message = new LinkedHashMap<>(7);
        message.put(KEY_ID, messageId);
        message.put(KEY_TYPE, OBJECT_MESSAGE);
        message.put(KEY_STATUS, status);
        message.put(KEY_ROLE, ROLE_ASSISTANT);
        message.put(KEY_CONTENT, List.of(Map.of(
                KEY_TYPE, CONTENT_OUTPUT_TEXT,
                KEY_TEXT, text,
                "annotations", List.of()
        )));
        return message;
    }

    /**
    * 构建 Gemini 候选响应体。
    *
    * @param role 模型名（Gemini 使用 role 承载模型标识）
    * @param text 文本内容
    * @return 候选响应体
    */
    private Map<String, Object> buildGeminiCandidate(String role, String text) {
        Map<String, Object> content = new LinkedHashMap<>(3);
        content.put(KEY_ROLE, role);
        content.put("parts", List.of(Map.of(KEY_TEXT, text != null ? text : "")));
        Map<String, Object> candidate = new LinkedHashMap<>(5);
        candidate.put("content", content);
        candidate.put("finishReason", GEMINI_FINISH_STOP);
        candidate.put(KEY_INDEX, 0);
        return candidate;
    }

    // ==================== Helpers ====================

    /**
    * 解析请求体为 Map。
    *
    * @param request 请求对象
    * @return 请求体 Map，解析失败或为空时返回空 Map
    */
    private Map<String, Object> parseBody(ServerRequest request) {
        String bodyStr = request.getBodyString();
        if (StringUtils.isNullOrEmpty(bodyStr)) {
            return new LinkedHashMap<>(); // [P3C 3.15 豁免] 空集合归还语义，无填充，无容量需求
        }
        try {
            return Json.fromJson(bodyStr, LinkedHashMap.class);
        } catch (Exception ex) {
            log.warn("{}parse-body-failed: {}", LOG_PROTOCOL_PREFIX, ex.getMessage());
            return new LinkedHashMap<>(); // [P3C 3.15 豁免] 空集合归还语义，无填充，无容量需求
        }
    }

    /**
    * 将对象序列化为 JSON 并写入响应。
    *
    * @param response 响应对象
    * @param obj      待序列化对象
    */
    private void writeJson(ServerResponse response, Object obj) {
        response.setContentType(CONTENT_TYPE_JSON);
        response.setBody(Json.toJson(obj));
        response.end();
    }

    /**
    * 获取过滤器标识。
    *
    * @return 过滤器标识
    */
    @Override
    public String getFilterId() {
        return "AiProtocolServerFilter";
    }

    /**
    * 获取过滤器注册顺序。
    *
    * @return 注册顺序
    */
    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
