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
   * Compression-aware 模型 decorator.
 * <p>
   * 统一走 {@link ContextCompressor}（与轻量 对话客户端 同一入口/算法）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CompressionAwareModel implements Model {

    /** 委托对象 */
    /** Delegate */
    private final Model delegate;
    /** 降级聊天客户端 */
    /** 回退对话客户端 */
    private final ChatClient fallbackChatClient;
    /** 压缩配置 */
    /** Compression配置 */
    private final AgentCompressionConfig compressionConfig;
    /** 上下文压缩器 */
    /** Compressor */
    private final ContextCompressor compressor;
    /** 模型名称 */
    private final String modelName;
    /** 压缩模型标识 */
    /** Compression模型标识 */
    private final String compressionModelId;

    /**
      * 创建 compressionaware模型 实例
     * @param delegate delegate
     * @param fallbackChatClient 降级对话客户端
     * @param compressionConfig compression配置
     * @param modelName 模型名称
     * @param workspace workspace
     * @param compressionModelId compression模型标识
     */
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
    /**
     * 流式输出
     * @param messages 消息
     * @param tools tools
     * @param options 期权
     */
    public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                                     GenerateOptions options) {
        List<ChatMessage> chatMessages = convertToChatMessages(messages);
        List<ChatMessage> compressedMessages = compressor.maybeCompress(chatMessages);
        List<Msg> adaptedMessages = convertToMsg(compressedMessages);
        return delegate.stream(adaptedMessages, tools, options);
    }

    @Override
    /** 获取模型名称 */
    public String getModelName() {
        return modelName;
    }

    /**
      * 包内测试 / 调试入口，与 流 路径一致。
     */
    List<ChatMessage> detectAndCompress(List<ChatMessage> messages) {
        return compressor.maybeCompress(messages);
    }

    /**
     * 获取Compressor
     *
     * @return 获取compressor的结果
     */
    public ContextCompressor getCompressor() {
        return compressor;
    }

    /**
     * 获取compression模型id
     *
     * @return 获取compression模型id的结果
     */
    public String getCompressionModelId() {
        return compressionModelId;
    }

    /**
     * 转换转为对话消息
     *
     * @param messages 消息
     * @return 转换转为对话消息的结果
     */
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

    /**
     * 转换转为msg
     *
     * @param messages 消息
     * @return 转换转为msg的结果
     */
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
