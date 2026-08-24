package com.chua.common.support.network.rpc;

import lombok.Data;

import java.io.Serializable;

/**
 * RPC 响应数据传输对象，封装一次远程过程调用的执行结果。
 *
 * <p>服务端在执行完目标方法后构造此对象，序列化后通过网络返回给客户端。
 * 客户端反序列化后，根据 {@link #success} 字段判断调用结果：</p>
 * <ul>
 *   <li>成功（{@code success = true}）：从 {@link #result} 中获取业务返回值</li>
 *   <li>失败（{@code success = false}）：从 {@link #error} 中获取异常信息</li>
 * </ul>
 *
 * <h2>字段说明</h2>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code success}</td><td>{@code boolean}</td><td>调用是否成功。</td></tr>
 *   <tr><td>{@code result}</td><td>{@code Object}</td><td>当成功时存放方法调用返回值；当失败时为 {@code null}</td></tr>
 *   <tr><td>{@code error}</td><td>{@code String}</td><td>当失败时存放异常信息描述；当成功时为 {@code null}</td></tr>
 * </table>
 *
 * <p>实现 {@link Serializable} 接口，确保可在网络上进行二进制传输。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class RpcResponse implements Serializable {
    /**
     * 远程调用是否执行成功
     *
     * <ul>
     *   <li>{@code true} — 服务端成功执行了目标方法，业务结果存放在 {@link #result} 字段中</li>
     *   <li>{@code false} — 服务端执行过程中发生异常，错误信息存放在 {@link #error} 字段中</li>
     * </ul>
     *
     * <p>客户端应先检查此字段，再决定读取 {@code result} 还是处理 {@code error}。</p>
     */
    private boolean success;

    /**
     * 远程调用的业务返回值
     *
     * <p>当且仅当 {@link #success} 为 {@code true} 时，此字段存放目标方法的实际返回值。</p>
     *
     * <ul>
     *   <li>如果目标方法返回为 {@code void}，此字段值为 {@code null}</li>
     *   <li>如果目标方法返回基本类型（如 {@code int}），值为对应的包装类型（如 {@code Integer}）</li>
     *   <li>返回对象类型必须可被序列化框架处理（如实现 {@link Serializable}）</li>
     * </ul>
     */
    private Object result;

    /** 异常类型全限定名，用于客户端精确识别异常种类 */
    private String exceptionType;

    /** 远程调用的失败原因描述
     *
     * <p>当且仅当 {@link #success} 为 {@code false} 时，此字段存放失败原因。</p>
     *
     * <p>常见错误信息示例：</p>
     * <ul>
     *   <li>服务端抛出的业务异常消息，如 {@code "余额不足"}</li>
     *   <li>定位服务方法失败，如 {@code "Method not found: findByEmail"}</li>
     *   <li>方法调用反射异常，如 {@code "java.lang.reflect.InvocationTargetException"}</li>
     *   <li>参数类型不匹配，如 {@code "No matching method found for [String, Integer]"}</li>
     * </ul>
     */
    private String error;
}
