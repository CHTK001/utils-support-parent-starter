package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.context.ContextCompressor;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
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

    /**
     * 委托对象
    */
    private final Model delegate;
    /**
     * 降级聊天客户端
    */
    private final ChatClient fallbackChatClient;
    /**
     * 压缩配置
    */
    private final AgentCompressionConfig compressionConfig;
    /**
     * 上下文压缩器
    */
    private final ContextCompressor compressor;
    /**
     * 模型名称
    */
    private final String modelName;
    /**
     * 压缩模型标识
    */
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
        if (hasToolBlocks(messages)) {
            // 压缩以纯文本轮次建模，带工具的轮次原样下发才能保住 tool_call_id 配对
            return delegate.stream(messages, tools, options);
        }
        List<ChatMessage> chatMessages = convertToChatMessages(messages);
        List<ChatMessage> compressedMessages = compressor.maybeCompress(chatMessages);
        List<Msg> adaptedMessages = convertToMsg(compressedMessages);
        return delegate.stream(adaptedMessages, tools, options);
    }

    @Override
    /**
     * 获取模型名称
    */
    public String getModelName() {
        return modelName;
    }

    /**
     * 包内测试 / 调试入口，与 流 路径一致。
     * @param messages 方法入参 messages
     * @return 结果列表，无数据时为空列表
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
     * 会话中是否存在工具轮次
     *
     * @param messages 消息
     * @return 含TOOL角色或工具调用/结果块时为 true
     */
    private static boolean hasToolBlocks(List<Msg> messages) {
        if (messages == null) {
            return false;
        }
        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.TOOL
                    || msg.hasContentBlocks(ToolUseBlock.class)
                    || msg.hasContentBlocks(ToolResultBlock.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转换转为对话消息
     *
     * @param messages 消息
     * @return 转换转为对话消息的结果
     */
    static List<ChatMessage> convertToChatMessages(List<Msg> messages) {
        if (messages == null) {
            return Wraps.list();
        }
        List<ChatMessage> result = Wraps.list();
        for (Msg msg : messages) {
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                // SYSTEM 必须保留：Harness 把 sysPrompt 作为 SYSTEM 消息放进 messages，
                // 这里若跳过，压缩轮次下 agent 的人设与约束就被整个丢掉。
                // 之所以此前没被发现，是因为带工具的轮次走 delegate 路径不受此转换影响，
                // 只有无工具的压缩轮次会中招，表现为「时好时坏」。
                result.add(ChatMessage.builder()
                        .role(roleName(msg.getRole()))
                        .content(text)
                        .build());
            }
        }
        return result;
    }

    /**
     * AgentScope 角色转对话角色名。
     *
     * @param role AgentScope 角色
     * @return 对话角色名
     */
    private static String roleName(MsgRole role) {
        if (role == MsgRole.SYSTEM) {
            return "system";
        }
        return role == MsgRole.USER ? "user" : "assistant";
    }

    /**
     * 对话角色名转 AgentScope 角色。
     *
     * <p>system 必须还原为 SYSTEM 而非降级成 USER：压缩后的轮次要继续喂给模型，
     * 把人设当成用户发言会让模型把约束当提问，行为随之漂移。</p>
     *
     * @param roleName 对话角色名
     * @return AgentScope 角色
     */
    private static MsgRole roleOf(String roleName) {
        if ("system".equals(roleName)) {
            return MsgRole.SYSTEM;
        }
        return "assistant".equals(roleName) ? MsgRole.ASSISTANT : MsgRole.USER;
    }

    /**
     * 转换转为msg
     *
     * @param messages 消息
     * @return 转换转为msg的结果
     */
    static List<Msg> convertToMsg(List<ChatMessage> messages) {
        if (messages == null) {
            return Wraps.list();
        }
        List<Msg> result = Wraps.list();
        for (ChatMessage msg : messages) {
            MsgRole role = roleOf(msg.getRole());
            result.add(Msg.builder()
                    .role(role)
                    .content(TextBlock.builder().text(msg.getContent()).build())
                    .build());
        }
        return result;
    }
}
