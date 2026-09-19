package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * 对话客户端模型适配器类。
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
        List<ChatMessage> history = new ArrayList<>();
        String prompt = "";
        String systemPrompt = null;

        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.SYSTEM) {
                systemPrompt = msg.getTextContent();
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            ChatMessage cm = new ChatMessage(
                    msg.getRole() == MsgRole.USER ? "user" : "assistant",
                    text);
            if (msg.getRole() == MsgRole.USER) {
                prompt = text;
            }
            history.add(cm);
        }

        if (prompt.isEmpty() && !history.isEmpty()) {
            prompt = history.get(history.size() - 1).getContent();
        }

        String sessionId = "agentscope-" + modelName + "-" + UUID.randomUUID().toString().substring(0, 8);
        chatClient.newChat();
        chatClient.session(sessionId);

        for (ChatMessage cm : history) {
            if ("user".equals(cm.getRole())) {
                chatClient.addUserHistory(cm.getContent());
            } else {
                chatClient.addAssistantHistory(cm.getContent());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            try {
                ChatResponse toolResponse = callWithTools(prompt, history, tools, systemPrompt);
                return Flux.just(toolResponse);
            } catch (Exception e) {
                log.warn("[ChatClientModelAdapter] Tool calling failed, fallback to text prompt: {}", e.getMessage());
            }
        }

        String toolsPrompt = buildToolsPrompt(tools);
        if (!toolsPrompt.isEmpty()) {
            prompt = toolsPrompt + "\n\n" + prompt;
        }

        String responseText;
        try {
            responseText = chatClient.chatSync(prompt, 120_000);
        } catch (Exception e) {
            responseText = chatClient.chatSync(prompt);
        }

        ChatResponse response = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(responseText).build()))
                .finishReason("stop")
                .build();
        return Flux.just(response);
    }

    /**
     * callWithTools。
     *
     * @param prompt 提示词，不允许为 null
     * @param history 方法入参 history
     * @param tools 方法入参 tools
     * @param systemPrompt system提示词，不允许为 null
     * @return Chat响应 对象
     */
    private ChatResponse callWithTools(String prompt, List<ChatMessage> history, List<ToolSchema> tools, String systemPrompt) {
        String modelId = chatClient.getModel();
        if (modelId == null || modelId.isBlank()) {
            modelId = "gpt-4o";
        }

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(modelId)
                .maxTokens(4096);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            paramsBuilder.addSystemMessage(systemPrompt);
        }

        for (ChatMessage cm : history) {
            if ("user".equals(cm.getRole())) {
                paramsBuilder.addUserMessage(cm.getContent());
            } else {
                paramsBuilder.addMessage(com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.builder()
                        .content(cm.getContent())
                        .build());
            }
        }
        paramsBuilder.addUserMessage(prompt);

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

        log.debug("[ChatClientModelAdapter] Sending {} tools to model {}", toolDefs.size(), modelId);
        ChatCompletion completion = getOpenAiClient().chat().completions().create(paramsBuilder.build());

        var message = completion.choices().getFirst().message();

        if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (ChatCompletionMessageToolCall toolCall : message.toolCalls().get()) {
                if (!toolCall.isFunction()) {
                    continue;
                }
                var fnCall = toolCall.asFunction().function();
                String callId = toolCall.asFunction().id();
                String toolName = fnCall.name();
                String argumentsJson = fnCall.arguments();
                Map<String, Object> input = parseJson(argumentsJson);
                blocks.add(ToolUseBlock.builder()
                        .id(callId)
                        .name(toolName)
                        .input(input)
                        .content(argumentsJson)
                        .build());
            }
            return ChatResponse.builder()
                    .id(completion.id())
                    .content(blocks)
                    .finishReason("tool_calls")
                    .build();
        }

        String content = message.content().orElse("");
        return ChatResponse.builder()
                .id(completion.id())
                .content(List.of(TextBlock.builder().text(content).build()))
                .finishReason("stop")
                .build();
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
     * 解析Json。
     *
     * @param json 方法入参 json
     * @return 结果映射，无数据时为空映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("[ChatClientModelAdapter] Failed to parse tool arguments: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 转为Json值映射。
     *
     * @param params 参数，不允许为 null
     * @return 结果映射，无数据时为空映射
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
}
