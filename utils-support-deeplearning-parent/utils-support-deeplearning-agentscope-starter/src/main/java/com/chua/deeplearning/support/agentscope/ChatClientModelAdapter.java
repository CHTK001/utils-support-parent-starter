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
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
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

public class ChatClientModelAdapter implements Model {

    private static final Logger log = LoggerFactory.getLogger(ChatClientModelAdapter.class);

    private final ChatClient chatClient;
    private final String modelName;

    public ChatClientModelAdapter(ChatClient chatClient, String modelName) {
        this.chatClient = chatClient;
        this.modelName = modelName != null ? modelName : "chat-client";
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

    private ChatResponse callWithTools(String prompt, List<ChatMessage> history, List<ToolSchema> tools, String systemPrompt) {
        String apiKey = chatClient.getApiKey();
        String baseUrl = chatClient.getBaseUrl();
        String modelId = chatClient.getModel();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("无法获取 API Key，请确保 ChatClient 已正确配置");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (modelId == null || modelId.isBlank()) {
            modelId = "gpt-4o";
        }

        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(120))
                .build();

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
                paramsBuilder.addAssistantMessage(cm.getContent());
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
            toolDefs.add(ChatCompletionTool.ofFunction(
                    ChatCompletionFunctionTool.builder()
                            .function(fnBuilder.build())
                            .build()));
        }
        paramsBuilder.tools(toolDefs);
        paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO));

        System.out.println("[Adapter] Sending " + toolDefs.size() + " tools to model " + modelId);
        ChatCompletion completion = openAiClient.chat().completions().create(paramsBuilder.build());

        var message = completion.choices().get(0).message();
        System.out.println("[Adapter] finishReason=" + completion.choices().get(0).finishReason()
                + " hasToolCalls=" + message.toolCalls().isPresent());

        if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
            List<ContentBlock> blocks = new ArrayList<>();
            System.out.println("[Adapter] Tool calls count: " + message.toolCalls().get().size());
            for (ChatCompletionMessageToolCall toolCall : message.toolCalls().get()) {
                var fnCall = toolCall.asFunction();
                System.out.println("[Adapter] Calling tool: " + fnCall.function().name() + " args=" + fnCall.function().arguments());
                String callId = fnCall.id();
                String toolName = fnCall.function().name();
                String argumentsJson = fnCall.function().arguments();
                Map<String, Object> input = parseJson(argumentsJson);
                blocks.add(new ToolUseBlock(callId, toolName, input));
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

    // Uses ChatClient.getApiKey() — no reflection needed

    // Uses ChatClient.getBaseUrl() — no reflection needed

    // Uses ChatClient.getModel() — no reflection needed

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

    public void close() {
        try {
            chatClient.close();
        } catch (Exception ignored) {
            log.debug("ChatClient close exception: modelName={}", modelName, ignored);
        }
    }
}




