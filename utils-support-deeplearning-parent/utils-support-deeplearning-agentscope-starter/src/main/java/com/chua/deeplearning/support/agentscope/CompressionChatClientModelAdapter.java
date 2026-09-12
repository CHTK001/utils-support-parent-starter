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
* 将压缩专用 {@link ChatClient} 适配为 智能体scope 的 {@link Model} 接口。
*
* <p>供 {@link com.chua.common.support.ai.agent.AgentContextCompressionService}
* 在偏差矫正阶段调用，使用独立的高阶模型进行基线总结和上下文矫正。
*
* @author CH
* @since 4.0.0.42
 */
public class CompressionChatClientModelAdapter implements Model {

    /** 日志记录器 */
    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(CompressionChatClientModelAdapter.class);

    /** 压缩聊天客户端 */
    /** Compressionchat客户端 */
    private final ChatClient compressionChatClient;
    /** 模型名称 */
    private final String modelName;

    /**
    * 创建 compression对话客户端模型适配器 实例
    * @param compressionChatClient compression对话客户端
    * @param modelName 字符串
    * @param modelName 模型名称
     */
    public CompressionChatClientModelAdapter(ChatClient compressionChatClient, String modelName) {
        this.compressionChatClient = compressionChatClient;
        this.modelName = modelName != null ? modelName : "compression-model";
    }

    @Override
    /** 流 */
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String prompt = extractLastUserPrompt(messages);

        compressionChatClient.newChat();
        compressionChatClient.session("compression-" + UUID.randomUUID().toString().substring(0, 8));

        String responseText;
        try {
            responseText = compressionChatClient.chatSync(prompt, 120_000);
        } catch (Exception e) {
            responseText = compressionChatClient.chatSync(prompt);
        }

        ChatResponse response = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(responseText).build()))
                .finishReason("stop")
                .build();
        return Flux.just(response);
    }

    @Override
    /** 获取模型名称 */
    public String getModelName() {
        return modelName;
    }

    /**
    * extract最后一个用户提示符
    *
    * @param messages 消息
    * @return extract最后一个用户提示符的结果
     */
    private static String extractLastUserPrompt(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return messages.get(messages.size() - 1).getTextContent();
    }
}