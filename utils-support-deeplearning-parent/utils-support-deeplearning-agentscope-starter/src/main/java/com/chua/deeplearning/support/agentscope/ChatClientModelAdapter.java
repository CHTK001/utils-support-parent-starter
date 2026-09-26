package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对话客户端模型适配器类。
 *
 * <p>将项目通用 {@link ChatClient} 桥接为 Agentscope {@link Model}，
 * 无工具路径桥接 ChatClient 逐字流式，有工具路径走 OpenAI 流式 function calling。
 *
 * @author CH
 * @since 4.0.0
 */
public class ChatClientModelAdapter implements Model {

    private static final Logger log = LoggerFactory.getLogger(ChatClientModelAdapter.class); // 日志

    private final ChatClient chatClient; // 对话客户端
    private final String modelName; // 模型名称
    /**
     * 复用的 OpenAI 客户端实例。
     * 懒加载并同步创建，避免每次调用模型时新建连接，降低网络开销。
     */
    private volatile OpenAIClient openAiClient;

    /**
     * 对话客户端模型适配器。
     * @param chatClient 对话客户端
     * @param modelName 模型名称
     */
    public ChatClientModelAdapter(ChatClient chatClient, String modelName) {
        this.chatClient = chatClient;
        this.modelName = modelName != null ? modelName : "chat-client";
    }

    /**
     * 获取打开Ai客户端。
     *
     * @return 打开AI客户端 对象
     */
    private OpenAIClient getOpenAiClient() {
        if (openAiClient == null) {
            synchronized (this) {
                if (openAiClient == null) {
                    String apiKey = chatClient.getApiKey();
                    String baseUrl = chatClient.getBaseUrl();
                    if (apiKey == null || apiKey.isBlank()) {
                        apiKey = "";
                    }
                    if (baseUrl == null || baseUrl.isBlank()) {
                        baseUrl = "https://api.openai.com/v1";
                    }
                    openAiClient = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .timeout(Duration.ofSeconds(120))
                            .build();
                }
            }
        }
        return openAiClient;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 1. 解析 system / 历史 / 最新用户输入（最新输入不再重复加入历史，避免重复提问）
        String systemPrompt = null;
        List<ChatMessage> history = new ArrayList<>();
        String prompt = "";
        for (int mi = 0; mi < messages.size(); mi++) {
            Msg msg = messages.get(mi);
            MsgRole role = msg.getRole();
            boolean isLast = mi == messages.size() - 1;
            if (role == MsgRole.SYSTEM) {
                systemPrompt = msg.getTextContent();
                continue;
            }
            if (role == MsgRole.TOOL) {
                String toolText = toolResultsAsText(msg);
                if (!toolText.isBlank()) {
                    history.add(ChatMessage.builder().role("user").content(toolText).build());
                }
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) {
                text = toolUsesAsText(msg);
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (role == MsgRole.USER && isLast) {
                prompt = text;
                continue;
            }
            history.add(ChatMessage.builder()
                    .role(role == MsgRole.USER ? "user" : "assistant")
                    .content(text)
                    .build());
        }

        if (prompt.isEmpty() && !history.isEmpty()) {
            prompt = history.remove(history.size() - 1).getContent();
        }

        // 2. 有原生工具 → OpenAI 流式 function calling；构造失败则降级文本流式
        if (tools != null && !tools.isEmpty()) {
            try {
                return streamWithTools(messages, tools, systemPrompt, options)
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            } catch (Exception e) {
                log.warn("[ChatClientModelAdapter] 原生工具流式构造失败，降级文本流式: {}", e.getMessage());
            }
        }

        // 3. 无工具（或降级）→ 桥接 ChatClient 逐字流式
        return streamText(history, prompt, tools, systemPrompt)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 桥接 ChatClient 逐字流式（无原生工具路径）。
     *
     * @param history      历史消息（不含最新输入）
     * @param prompt       最新用户输入
     * @param tools        工具定义（仅用于文本降级提示，允许为 null）
     * @param systemPrompt 系统提示词（角色人设 / Harness sysPrompt），允许为 null
     * @return 流式 ChatResponse Flux
     */
    private Flux<ChatResponse> streamText(List<ChatMessage> history, String prompt,
                                          List<ToolSchema> tools, String systemPrompt) {
        String toolsPrompt = buildToolsPrompt(tools);
        final String finalPrompt = toolsPrompt.isEmpty() ? prompt : (toolsPrompt + "\n\n" + prompt);
        final List<ChatMessage> hist = new ArrayList<>(history);
        final String sysPrompt = systemPrompt;

        return Flux.create(sink -> {
            try {
                String sessionId = "agentscope-" + modelName + "-" + UUID.randomUUID().toString().substring(0, 8);
                chatClient.newChat();
                chatClient.session(sessionId);
                // 系统提示词在新会话内、发起对话前设置，确保角色人设随请求送达
                if (sysPrompt != null && !sysPrompt.isBlank()) {
                    chatClient.system(sysPrompt);
                }
                for (ChatMessage cm : hist) {
                    if ("user".equals(cm.getRole())) {
                        chatClient.addUserHistory(cm.getContent());
                    } else {
                        chatClient.addAssistantHistory(cm.getContent());
                    }
                }

                chatClient.chat(finalPrompt, resp -> {
                    if (resp == null) {
                        return;
                    }
                    switch (resp.getState()) {
                        case START -> { }
                        case STREAMING -> {
                            // 推理/思考增量实时下发（与正文同一条流，先思考后正文），
                            // 否则推理模型会长时间静默只转圈，直到整段返回
                            String reasoning = resp.getReasoningContent();
                            if (reasoning != null && !reasoning.isEmpty()) {
                                sink.next(thinkingDelta(reasoning));
                            }
                            String delta = resp.getContent();
                            if (delta != null && !delta.isEmpty()) {
                                sink.next(textDelta(delta));
                            }
                        }
                        case STOP -> {
                            sink.next(ChatResponse.builder()
                                    .content(List.of())
                                    .finishReason("stop")
                                    .usage(toAgentscopeUsage(resp.getUsage()))
                                    .build());
                            sink.complete();
                        }
                        case ERROR -> sink.error(new RuntimeException(
                                resp.getErrorMessage() != null ? resp.getErrorMessage() : "stream error"));
                    }
                });
            } catch (Exception e) {
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * OpenAI 流式 function calling：逐 token 转发文本，末尾聚合工具调用与用量。
     *
     * @param messages     AgentScope 原始消息
     * @param tools        工具定义
     * @param systemPrompt system 提示词
     * @param options      生成选项（maxTokens / 思考 effort 等）
     * @return 流式 ChatResponse Flux
     */
    private Flux<ChatResponse> streamWithTools(List<Msg> messages, List<ToolSchema> tools,
                                               String systemPrompt, GenerateOptions options) {
        ChatCompletionCreateParams params = buildToolParams(messages, tools, systemPrompt, options);
        return Flux.create(sink -> {
            try (com.openai.core.http.StreamResponse<ChatCompletionChunk> stream =
                         getOpenAiClient().chat().completions().createStreaming(params)) {
                Map<Integer, ToolCallAcc> accumulators = new LinkedHashMap<>();
                final io.agentscope.core.model.ChatUsage[] usage = {null};

                stream.stream().forEach(chunk -> {
                    chunk.usage().ifPresent(u -> usage[0] = toAgentscopeUsage(u));
                    for (var choice : chunk.choices()) {
                        var delta = choice.delta();
                        if (delta == null) {
                            continue;
                        }
                        // 推理增量（第三方 OpenAI 兼容接口多放在非标准字段 reasoning_content），
                        // 实时下发，避免思考阶段长时间无任何输出
                        String reasoning = extractReasoning(delta);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            sink.next(thinkingDelta(reasoning));
                        }
                        delta.content().ifPresent(c -> {
                            if (!c.isEmpty()) {
                                sink.next(textDelta(c));
                            }
                        });
                        delta.toolCalls().ifPresent(list -> {
                            for (var tc : list) {
                                int idx = (int) tc.index();
                                ToolCallAcc acc = accumulators.computeIfAbsent(idx, k -> new ToolCallAcc());
                                tc.id().ifPresent(id -> acc.id = id);
                                tc.function().ifPresent(fn -> {
                                    fn.name().ifPresent(n -> acc.name += n);
                                    fn.arguments().ifPresent(ar -> acc.args += ar);
                                });
                            }
                        });
                    }
                });

                if (!accumulators.isEmpty()) {
                    List<ContentBlock> blocks = new ArrayList<>();
                    for (ToolCallAcc acc : accumulators.values()) {
                        String callId = acc.id != null ? acc.id : ("call_" + UUID.randomUUID());
                        blocks.add(ToolUseBlock.builder()
                                .id(callId)
                                .name(acc.name)
                                .input(parseJson(acc.args))
                                .content(acc.args)
                                .build());
                    }
                    sink.next(ChatResponse.builder()
                            .content(blocks)
                            .finishReason("tool_calls")
                            .usage(usage[0])
                            .build());
                } else {
                    sink.next(ChatResponse.builder()
                            .content(List.of())
                            .finishReason("stop")
                            .usage(usage[0])
                            .build());
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 构建流式 function calling 请求参数（含工具定义与 include_usage）。
     *
     * @param messages     AgentScope 原始消息
     * @param tools        工具定义
     * @param systemPrompt system 提示词
     * @param options      生成选项（maxTokens / 思考 effort / thinkingBudget）
     * @return 请求参数
     */
    private ChatCompletionCreateParams buildToolParams(List<Msg> messages, List<ToolSchema> tools,
                                                       String systemPrompt, GenerateOptions options) {
        String modelId = chatClient.getModel();
        if (modelId == null || modelId.isBlank()) {
            modelId = "gpt-4o";
        }

        // maxTokens 取生成选项，未配置再回退默认 4096（不再写死）
        Integer maxTokens = options != null ? options.getMaxTokens() : null;
        if (maxTokens == null && options != null) {
            maxTokens = options.getMaxCompletionTokens();
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 4096;
        }

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(modelId)
                .maxTokens(maxTokens)
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());

        // 思考开关：reasoningEffort 或 thinkingBudget>0 时，按 OpenAI reasoning_effort 字段开启，
        // 否则支持显式思考的模型在 function calling 路径不会产出推理内容
        String effort = options != null ? options.getReasoningEffort() : null;
        Integer thinkingBudget = options != null ? options.getThinkingBudget() : null;
        if ((effort == null || effort.isBlank()) && thinkingBudget != null && thinkingBudget > 0) {
            effort = "medium";
        }
        if (effort != null && !effort.isBlank()) {
            paramsBuilder.reasoningEffort(switch (effort.toLowerCase()) {
                case "low", "minimal" -> ReasoningEffort.LOW;
                case "medium" -> ReasoningEffort.MEDIUM;
                default -> ReasoningEffort.HIGH;
            });
        }

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            paramsBuilder.addSystemMessage(systemPrompt);
        }

        Set<String> pending = new LinkedHashSet<>();
        int turns = 0;
        for (Msg msg : messages) {
            MsgRole role = msg.getRole();
            if (role == null || role == MsgRole.SYSTEM) {
                continue;
            }
            if (role == MsgRole.TOOL) {
                turns += appendToolResults(paramsBuilder, msg, pending);
                continue;
            }
            if (role == MsgRole.ASSISTANT) {
                ChatCompletionAssistantMessageParam assistant = buildAssistantParam(msg, pending);
                if (assistant != null) {
                    paramsBuilder.addMessage(assistant);
                    turns++;
                }
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                paramsBuilder.addUserMessage(text);
                turns++;
            }
        }

        List<ChatCompletionTool> toolDefs = new ArrayList<>();
        for (ToolSchema tool : tools) {
            FunctionDefinition.Builder fnBuilder = FunctionDefinition.builder()
                    .name(tool.getName());
            if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
                fnBuilder.description(tool.getDescription());
            }
            if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                fnBuilder.parameters(FunctionParameters.builder()
                        .additionalProperties(toJsonValueMap(tool.getParameters()))
                        .build());
            }
            toolDefs.add(ChatCompletionTool.ofFunction(com.openai.models.chat.completions.ChatCompletionFunctionTool.builder()
                    .function(fnBuilder.build())
                    .build()));
        }
        paramsBuilder.tools(toolDefs);
        paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO));

        if (turns == 0) {
            throw new IllegalStateException("没有可用于工具调用的对话轮次");
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException("存在未应答的工具调用: " + pending);
        }

        log.debug("[ChatClientModelAdapter] Streaming {} tools to model {}", toolDefs.size(), modelId);
        return paramsBuilder.build();
    }

    /**
     * 追加工具执行结果，以 role=tool 消息按调用标识与前置 assistant 配对。
     *
     * @param paramsBuilder OpenAI请求构造参数器，不允许为 null
     * @param msg TOOL角色消息，不允许为 null
     * @param pending 待应答调用标识集合，命中后移除，不允许为 null
     * @return 实际追加的工具结果消息条数
     */
    private int appendToolResults(ChatCompletionCreateParams.Builder paramsBuilder, Msg msg, Set<String> pending) {
        int appended = 0;
        for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
            String callId = result.getId();
            if (callId == null || callId.isBlank()) {
                log.warn("[ChatClientModelAdapter] 工具结果缺少调用标识，已丢弃: tool={}", result.getName());
                continue;
            }
            if (!pending.remove(callId)) {
                log.warn("[ChatClientModelAdapter] 工具结果无匹配的调用标识，已丢弃: callId={}, tool={}", callId, result.getName());
                continue;
            }
            paramsBuilder.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(callId)
                    .content(blocksToText(result.getOutput()))
                    .build());
            appended++;
        }
        return appended;
    }

    /**
     * 构建assistant消息参数，文本与工具调用块至少保留一项。
     *
     * @param msg assistant消息，不允许为 null
     * @param pending 登记本次发起的调用标识，不允许为 null
     * @return assistant消息参数对象，无可发送内容时为 null
     */
    private ChatCompletionAssistantMessageParam buildAssistantParam(Msg msg, Set<String> pending) {
        ChatCompletionAssistantMessageParam.Builder builder = ChatCompletionAssistantMessageParam.builder();
        String text = msg.getTextContent();
        boolean hasText = text != null && !text.isBlank();
        int calls = 0;
        for (ToolUseBlock call : msg.getContentBlocks(ToolUseBlock.class)) {
            String callId = call.getId();
            if (callId == null || callId.isBlank()) {
                log.warn("[ChatClientModelAgent] 工具调用缺少标识，已丢弃: tool={}", call.getName());
                continue;
            }
            builder.addToolCall(ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                            .id(callId)
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(call.getName())
                                    .arguments(toolArguments(call))
                                    .build())
                            .build()));
            pending.add(callId);
            calls++;
        }
        if (!hasText && calls == 0) {
            return null;
        }
        if (hasText) {
            builder.content(text);
        }
        return builder.build();
    }

    /**
     * 构建Tools提示词。
     *
     * @param tools 方法入参 tools
     * @return 结果字符串
     */
    private String buildToolsPrompt(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你可以使用以下工具：\n");
        for (int i = 0; i < tools.size(); i++) {
            ToolSchema tool = tools.get(i);
            sb.append(i + 1).append(". ").append(tool.getName());
            if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
                sb.append(": ").append(tool.getDescription());
            }
            sb.append("\n");
        }
        sb.append("\n请按工具要求执行。");
        return sb.toString();
    }

    /**
     * 将内容块列表拼接为纯文本。
     *
     * @param blocks 内容块列表，允许为 null
     * @return 结果字符串，无文本块时返回占位说明
     */
    private static String blocksToText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        if (blocks != null) {
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.isEmpty() ? "工具无输出" : sb.toString();
    }

    /**
     * 取工具调用的参数Json字符串。
     *
     * @param call 工具调用块，不允许为 null
     * @return 参数Json字符串，缺失时为空对象字面量
     */
    private static String toolArguments(ToolUseBlock call) {
        String content = call.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        Map<String, Object> input = call.getInput();
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input);
        } catch (Exception e) {
            log.warn("[ChatClientModelAdapter] Failed to serialize tool arguments: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 将TOOL消息的工具结果展开为文本降级路径可读的描述。
     *
     * @param msg TOOL角色消息，不允许为 null
     * @return 结果字符串，无工具结果块时为空字符串
     */
    private static String toolResultsAsText(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("工具 ").append(result.getName()).append(" 返回：").append(blocksToText(result.getOutput()));
        }
        return sb.toString();
    }

    /**
     * 将仅含工具调用的assistant消息展开为文本降级路径可读的描述。
     *
     * @param msg assistant消息，不允许为 null
     * @return 结果字符串，无工具调用块时为空字符串
     */
    private static String toolUsesAsText(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ToolUseBlock call : msg.getContentBlocks(ToolUseBlock.class)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("调用工具 ").append(call.getName()).append(" 参数：").append(toolArguments(call));
        }
        return sb.toString();
    }

    /**
     * 解析Json。
     *
     * @param json 方法入参 json
     * @return 结果映射，无数据时为空映射
     */
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(json, Map.class);
            return result;
        } catch (Exception e) {
            log.warn("[ChatClientModelAdapter] Failed to parse tool arguments: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 转为Json值映射。
     *
     * @param params 参数，不允许为 null
     * @return 结果映射，无数据为空映射
     */
    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> params) {
        Map<String, JsonValue> result = new HashMap<>();
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), JsonValue.from(entry.getValue()));
                }
            }
        }
        return result;
    }

    /**
     * 构建文本增量 ChatResponse。
     *
     * @param delta 文本片段
     * @return ChatResponse
     */
    private static ChatResponse textDelta(String delta) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(delta).build()))
                .build();
    }

    /**
     * 构建思考（推理）增量 ChatResponse。
     *
     * @param delta 思考片段
     * @return ChatResponse
     */
    private static ChatResponse thinkingDelta(String delta) {
        return ChatResponse.builder()
                .content(List.of(ThinkingBlock.builder().thinking(delta).build()))
                .build();
    }

    /**
     * 从 OpenAI 流式 chunk 的 delta 中提取推理内容。
     *
     * <p>标准 OpenAI Java SDK 未对第三方推理字段建模，聚合/中转接口（如 DeepSeek 系）
     * 通常以非标准字段 {@code reasoning_content}（或 reasoning / reasoning_text）下发，
     * 位于 {@code delta._additionalProperties()}。优先按已知键取值，再回退到任意
     * 键名包含 "reason" 的字段。</p>
     *
     * @param delta 本次 chunk 的 choice.delta
     * @return 推理文本，不存在时返回 null
     */
    private static String extractReasoning(ChatCompletionChunk.Choice.Delta delta) {
        Map<String, JsonValue> extra = delta._additionalProperties();
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        String[] knownKeys = {"reasoning_content", "reasoning", "reasoning_text", "thinking"};
        for (String key : knownKeys) {
            JsonValue v = extra.get(key);
            if (v != null) {
                try {
                    String s = v.convert(String.class);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                } catch (Exception ignored) {
                    // 单个字段转换失败则继续尝试其它键
                }
            }
        }
        for (Map.Entry<String, JsonValue> e : extra.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().contains("reason")) {
                try {
                    String s = e.getValue().convert(String.class);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                } catch (Exception ignored) {
                    // 忽略无法转换的字段
                }
            }
        }
        return null;
    }

    /**
     * OpenAI CompletionUsage 转 agentscope ChatUsage。
     *
     * @param usage OpenAI 用量
     * @return agentscope 用量
     */
    private static io.agentscope.core.model.ChatUsage toAgentscopeUsage(com.openai.models.completions.CompletionUsage usage) {
        return io.agentscope.core.model.ChatUsage.builder()
                .inputTokens((int) usage.promptTokens())
                .outputTokens((int) usage.completionTokens())
                .build();
    }

    /**
     * 项目 AiUsage 转 agentscope ChatUsage。
     *
     * @param usage 项目用量
     * @return agentscope 用量
     */
    private static io.agentscope.core.model.ChatUsage toAgentscopeUsage(com.chua.common.support.ai.AiUsage usage) {
        if (usage == null) {
            return null;
        }
        return io.agentscope.core.model.ChatUsage.builder()
                .inputTokens(usage.getInputTokens() != null ? usage.getInputTokens() : 0)
                .outputTokens(usage.getOutputTokens() != null ? usage.getOutputTokens() : 0)
                .cachedTokens(usage.getCacheTokens() != null ? usage.getCacheTokens() : 0)
                .build();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 关闭。
     */
    public void close() {
        try {
            chatClient.close();
        } catch (Exception ignored) {
            log.debug("ChatClient close exception: modelName={}", modelName, ignored);
        }
    }

    /**
     * 流式工具调用累加器
     */
    private static final class ToolCallAcc {
        /** 工具调用 id */
        private String id;
        /** 工具名称（增量拼接） */
        private String name = "";
        /** 工具参数 JSON（增量拼接） */
        private String args = "";
    }
}
