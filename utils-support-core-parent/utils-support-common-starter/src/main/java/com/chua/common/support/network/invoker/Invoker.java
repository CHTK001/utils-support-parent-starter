package com.chua.common.support.network.invoker;

import com.chua.common.support.network.invoker.filter.InjectCallback;
import com.chua.common.support.network.invoker.filter.InvocationContext;

/**
 * 服务调用器顶层接口，为注解标注的接口生成 HTTP/RPC 等动态代理。
 *
 * @author CH
 * @since 4.0.0.42
 * @see HttpInvoker
 * @see InvokerFactory
 */
public interface Invoker {

    /**
     * 为指定接口创建动态代理（带缓存）。
     * @param apiClass 方法入参 apiClass
     * @return T 对象
     */
    <T> T create(Class<T> apiClass);

    /**
     * 创建新的动态代理实例（不缓存）。
     * @param apiClass 方法入参 apiClass
     * @return T 对象
     */
    <T> T createNew(Class<T> apiClass);

    /**
     * 添加注入规则，在每次远程调用时将回调结果注入到上下文中。
     *
     * <p>与 {@code @RemoteInject} 注解功能一致，支持编程式注入。
     * target 格式与 {@code @RemoteInject.target()} 相同：</p>
     * <ul>
     *   <li>{@code "headers.X"} — 注入到请求头 {@code X}</li>
     *   <li>{@code "attributes.X"} — 注入到共享属性 {@code X}</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * Invoker invoker = InvokerFactory.getInvoker("http")
     *     .addInject("headers.Authorization", ctx -> "Bearer " + TokenManager.getToken());
     * UserApi api = invoker.create(UserApi.class);
     * }</pre>
     *
     * @param target  注入目标路径
     * @param callback 注入回调，每次调用时执行
     * @return 当前 Invoker 实例（链式调用）
     */
    default Invoker addInject(String target, InjectCallback callback) {
        return this;
    }
}
