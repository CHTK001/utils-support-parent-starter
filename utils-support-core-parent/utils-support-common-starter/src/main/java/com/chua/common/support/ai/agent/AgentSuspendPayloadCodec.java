package com.chua.common.support.ai.agent;

/**
 * Agent 挂起载荷编解码契约。
 *
 * <p>{@link AgentSuspendState#payload()} 对 {@link AgentSuspendStore} 是不透明的；
 * 内存实现直接持有对象引用，而持久化实现（如 {@link JdbcAgentSuspendStore}）
 * 必须把载荷转成字节才能落库。本契约把「载荷 ⇄ 字节」的转换收敛为可插拔实现：
 * 不同 Agent 引擎的载荷类型不同（如 langchain4j 的 ChatMessage、
 * agentscope 的 ToolUseBlock），由各引擎侧提供对应编解码器。</p>
 *
 * <p><b>实现要求</b>：编解码必须可逆——{@code decode(encode(payload))} 语义上
 * 等价于 {@code payload}；实现必须线程安全；对 null 载荷，encode 应返回
 * null 或空数组，decode 对 null/空输入应返回 null（往返一致）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentSuspendPayloadCodec {

    /**
     * 把载荷编码为可持久化的字节。
     *
     * <p>载荷不可编码时抛出异常；持久化实现应捕获并按「写入失败」处理，
     * 不应让存储故障中断 Agent 主流程。</p>
     *
     * @param payload 挂起载荷，可为 null
     * @return 编码后的字节；null 载荷返回 null
     */
    byte[] encode(Object payload);

    /**
     * 把字节解码回载荷。
     *
     * <p>字节损坏或类型不可解析时抛出异常；持久化实现应捕获并按
     * 「条目不可恢复」处理（清理坏数据并返回 null）。</p>
     *
     * @param bytes 编码字节，可为 null
     * @return 载荷；null/空输入返回 null
     */
    Object decode(byte[] bytes);

    /**
     * 默认编解码器：Java 原生序列化。
     *
     * <p>仅适用于实现了 {@link java.io.Serializable} 的载荷；框架外 SDK 类型
     * （如 langchain4j / agentscope 的消息与工具块）大多不可序列化，
     * 使用方应提供对应引擎的专用编解码器。</p>
     *
     * @return Java 序列化编解码器
     */
    static AgentSuspendPayloadCodec javaSerialization() {
        return JavaSerializationAgentSuspendPayloadCodec.INSTANCE;
    }
}
