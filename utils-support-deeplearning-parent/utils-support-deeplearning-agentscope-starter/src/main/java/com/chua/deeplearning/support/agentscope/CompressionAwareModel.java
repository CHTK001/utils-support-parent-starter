package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.context.ContextCompressor;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Compression-aware Model decorator.
 * <p>
 * 统一走 {@link ContextCompressor}（与轻量 ChatClient 同一入口/算法）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CompressionAwareModel implements Model {

    /** 委托对象 */
    private final Model delegate;
    /** 降级聊天客户端 */
    private final ChatClient fallbackChatClient;
    /** 压缩配置 */
    private final AgentCompressionConfig compressionConfig;
    /** 上下文压缩器 */
    private final ContextCompressor compressor;
    /** 模型名称 */
    private final String modelName;
    /** 压缩模型标识 */
    private final String compressionModelId;

    public CompressionAwareModel(Model delegate, ChatClient fallbackChatClient,
                                 AgentCompressionConfig compressionConfig,
                                 String modelName, String workspace,
                                 String compressionModelId) {
        this.delegate = delegate;
        this.fallbackChatClient = fallbackChatClient;
        this.compressionConfig = compressionConfig;
        this.modelName = modelName != null ? modelName : "compression-aware";
        String ws = workspace != null ? workspace
                : (System.getProperty("java.io.tmpdir", "/tmp") + "agentscope-compression");
        this.compressionModelId = compressionModelId;
        this.compressor = ContextCompressor.fromAgent(compressionConfig, fallbackChatClient, ws);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                                     GenerateOptions options) {
        List<ChatMessage> chatMessages = convertToChatMessages(messages);
        List<ChatMessage> compressedMessages = compressor.maybeCompress(chatMessages);
        List<Msg> adaptedMessages = convertToMsg(compressedMessages);
        return delegate.stream(adaptedMessages, tools, options);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 包内测试 / 调试入口，与 stream 路径一致。
     */
    List<ChatMessage> detectAndCompress(List<ChatMessage> messages) {
        return compressor.maybeCompress(messages);
    }

    public ContextCompressor getCompressor() {
        return compressor;
    }

    public String getCompressionModelId() {
        return compressionModelId;
    }

    private static List<ChatMessage> convertToChatMessages(List<Msg> messages) {
        if (messages == null) {
            return new ArrayList<>();
        }
        List<ChatMessage> result = new ArrayList<>();
        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.SYSTEM) {
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                result.add(new ChatMessage(
                        msg.getRole() == MsgRole.USER ? "user" : "assistant",
                        text));
            }
        }
        return result;
    }

    private static List<Msg> convertToMsg(List<ChatMessage> messages) {
        if (messages == null) {
            return new ArrayList<>();
        }
        List<Msg> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            MsgRole role = "assistant".equals(msg.getRole()) ? MsgRole.ASSISTANT : MsgRole.USER;
            result.add(Msg.builder()
                    .role(role)
                    .content(TextBlock.builder().text(msg.getContent()).build())
                    .build());
        }
        return result;
    }
}
