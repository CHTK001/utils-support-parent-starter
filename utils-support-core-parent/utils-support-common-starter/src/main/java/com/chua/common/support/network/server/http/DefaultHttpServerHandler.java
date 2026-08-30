package com.chua.common.support.network.server.http;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.ClassUtils;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 路由处理器接口。
 *
 * <p>该接口专为 HTTP 协议设计，继承自通用的 {@link ServerHandler}，
 * 同时允许定义请求路径（path）、HTTP 方法（method）及响应头（headers）等元数据，
 * 便于在 IOC 容器中自动发现并注册路由处理逻辑。</p>
 *
 * <p>所有 HTTP 处理器默认支持响应式执行：同步 {@link #handle} 会被
 * {@link #handleReactive} 自动包装为 {@link CompletableFuture}。
 * 需要真正异步 I/O 的实现可直接覆写 {@link #handleReative}。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
public class DefaultHttpServerHandler implements HttpDefaultServerHandler {
    /**
     * 目标 Bean 对象，其中包含需要调用的处理方法。
     */
    protected final Object bean;

    /**
     * 待调用的具体方法对象。
     */
    protected final Method method;

    /**
     * 该处理器所映射的 URL 路径。
     */
    private final String path;

    /**
     * 该处理器所支持的 HTTP 请求方法（如 GET, POST 等）。
     */
    private final HttpMethod httpMethod;

    /**
     * 构造函数，初始化处理器所需的各个组件。
     *
     * @param bean        目标 Bean 对象实例。
     * @param method      待调用的反射方法。
     * @param path        绑定的 URL 路径。
     * @param httpMethod  绑定的 HTTP 方法类型。
     */
    public DefaultHttpServerHandler(Object bean, Method method, String path, HttpMethod httpMethod) {
        this.bean = bean;
        this.method = method;
        this.path = path;
        this.httpMethod = httpMethod;
    }
    /**
     * 处理同步请求。
     * <p>
     * 通过反射调用目标 Bean 上的指定方法，并将请求和响应对象作为参数传递。
     * 如果方法抛出异常，则向上抛出。
     * </p>
     *
     * @param request  入站请求对象。
     * @param response 出站响应对象。
     * @throws Exception 如果反射调用过程中发生任何异常。
     */
    @Override
    public void handle(ServerRequest request, ServerResponse response) throws Exception {
        if (method == null || bean == null) {
            throw new IllegalStateException("Method or Bean cannot be null during handler execution.");
        }

        // 确保方法可访问
        ClassUtils.setAccessible(method);
        response.setResult(ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), request, response));
    }

    @Override
    /** Path */
    public String path() {
        return path;
    }

    @Override
    /** Method */
    public HttpMethod method() {
        return httpMethod;
    }
}