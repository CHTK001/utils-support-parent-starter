package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 将项目通用 {@link ChatClient} 适配为 AgentScope 的 {@link Model} 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ChatClientModelAdapter implements Model {

    /** 日志记录器 */
    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(ChatClientModelAdapter.class);

    /** 聊天客户端 */
    /** Chat客户端 */
    private final ChatClient chatClient;
    /** 模型名称 */
    /** 模型名称 */
    private final String modelName;

    public ChatClientModelAdapter(ChatClient chatClient, String modelName) {
        this.chatClient = chatClient;
        this.modelName = modelName != null ? modelName : "chat-client";
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<ChatMessage> history = new ArrayList<>();
        String prompt = "";

        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.SYSTEM) {
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
            String toolsPrompt = buildToolsPrompt(tools);
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
     * 将 tools 列表熔合为文本提示嵌入 prompt 中，供不支持原生 tool calling 的 LLM 处理。
     */
    private String buildToolsPrompt(List<ToolSchema> tools) {
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

    @Override
    public String getModelName() {
        return modelName;
    }

    public void close() {
        try {
            chatClient.close();
        } catch (Exception ignored) {
            log.debug("ChatClient关闭时出现异常: modelName={}", modelName, ignored);
        }
    }
}