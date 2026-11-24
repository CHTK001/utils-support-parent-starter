package com.chua.common.support.ai.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 基于 Java 原生序列化的 {@link AgentSuspendPayloadCodec} 默认实现。
 *
 * <p>进程内对象 ⇄ 字节的通用兜底方案：载荷及其引用链上的所有类型都必须实现
 * {@link java.io.Serializable}。框架外的 SDK 类型（如 langchain4j 的
 * {@code ChatMessage}、agentscope 的 {@code ToolUseBlock}）通常不可序列化，
 * 编码时将抛出 {@link java.io.NotSerializableException}——使用方应为此类载荷
 * 提供引擎专用编解码器，而不是依赖本实现。</p>
 *
 * <p><b>安全提示</b>：Java 原生反序列化不校验类型白名单，只应用于读取
 * 本服务自己写入的持久化数据，不得用于解码不可信来源的字节。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class JavaSerializationAgentSuspendPayloadCodec implements AgentSuspendPayloadCodec {

    /**
     * 共享单例
     */
    public static final JavaSerializationAgentSuspendPayloadCodec INSTANCE =
            new JavaSerializationAgentSuspendPayloadCodec();

    private JavaSerializationAgentSuspendPayloadCodec() {
    }

    @Override
    public byte[] encode(Object payload) {
        if (payload == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Agent 挂起载荷序列化失败: " + payload.getClass().getName(), e);
        }
        return buffer.toByteArray();
    }

    @Override
    public Object decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Agent 挂起载荷反序列化失败", e);
        }
    }
}
