package com.chua.common.support.network.rpc;

import lombok.Data;

import java.io.Serializable;

/**
 * RPC 请求数据传输对象，封装一次远程过程调用的完整请求信息。
 *
 * <p>客户端在发起 RPC 调用时构造此对象，序列化后通过网络发送至服务端。
 * 服务端反序列化后，根据 {@link #service}、{@link #method} 和 {@link #paramTypes}
 * 定位到对应的本地服务实现方法，使用反射执行调用。</p>
 *
 * <h2>字段说明</h2>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code service}</td><td>{@code String}</td><td>远程服务接口的全限定类名，服务端通过此名称查找已注册的服务实现</td></tr>
 *   <tr><td>{@code method}</td><td>{@code String}</td><td>要调用的方法名，服务端通过反射匹配对应的 {@code java.lang.reflect.Method}</td></tr>
 *   <tr><td>{@code paramTypes}</td><td>{@code String[]}</td><td>方法参数类型的全限定类名数组，用于区分重载方法</td></tr>
 *   <tr><td>{@code args}</td><td>{@code Object[]}</td><td>方法实际传入的参数值数组，必须与 {@code paramTypes} 一一对应</td></tr>
 * </table>
 *
 * <p>实现 {@link Serializable} 接口，确保可在网络上进行二进制传输。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class RpcRequest implements Serializable {
    /**
     * 远程服务接口的全限定类名
     *
     * <p>服务端通过此名称在已注册的服务列表中进行查找。
     * 必须与 {@link RpcServer#register(String, Object)} 注册时的 {@code name} 完全匹配。</p>
     *
     * <p>示例：</p>
     * <pre>{@code "com.example.service.UserService"}</pre>
     */
    private String service;

    /**
     * 要调用的方法名称
     *
     * <p>服务端通过反射在目标服务对象上查找同名方法。
     * 如果存在重载方法（同方法名、不同参数类型），结合 {@link #paramTypes} 进行精确匹配。</p>
     *
     * <p>示例值：</p>
     * <pre>{@code "findById", "update", "delete"}</pre>
     */
    private String method;

    /**
     * 方法参数类型的全限定类名数组
     *
     * <p>数组中每个元素对应一个参数的 Java 类型名称（{@code Class.getName()} 格式）。
     * 服务端使用 {@code Class.forName()} 还原类型后，通过方法签名精确匹配合适的重载版本。</p>
     *
     * <p>对于无参方法，此字段为空数组 {@code new String[0]} 或 {@code null}。</p>
     *
     * <p>示例值：</p>
     * <pre>{@code ["java.lang.String", "java.lang.Integer"]}</pre>
     */
    private String[] paramTypes;

    /**
     * 方法调用的实际参数值数组
     *
     * <p>数组长度和元素顺序必须与 {@link #paramTypes} 完全对应。
     * 参数值将通过 Java 反射 {@code Method.invoke(bean, args)} 传递给目标方法。</p>
     *
     * <p>参数对象必须实现 {@link Serializable} 或其实际类型可被底层序列化框架处理。</p>
     */
    private Object[] args;
}
