package com.chua.common.support.network.server.http;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.ThreadUtils;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/**
 * 基于反射实现的 HTTP 服务器处理器。
 * <p>
 * 该处理器负责调用指定的 Bean 对象上的方法，以处理 HTTP 请求并生成响应。
 * 它支持同步和异步（反应式）两种处理模式。
 * </p>
 *
 * @author CH
 */
public class HttpReflectiveDefaultServerHandler extends DefaultHttpServerHandler implements ReflectiveHttpDefaultServerHandler {


    /**
     * 构造函数，初始化处理器所需的各个组件。
     *
     * @param bean       目标 Bean 对象实例。
     * @param method     待调用的反射方法。
     * @param path       绑定的 URL 路径。
     * @param httpMethod 绑定的 HTTP 方法类型。
     */
    public HttpReflectiveDefaultServerHandler(Object bean, Method method, String path, HttpMethod httpMethod) {
        super(bean, method, path, httpMethod);
    }

    /**
     * 处理反应式（Reactive）请求。
     * <p>
     * 当前实现为默认空实现，返回一个已完成但无结果的 CompletionStage。
     * 子类或后续版本可在此处扩展非阻塞处理逻辑。
     * </p>
     *
     * @param request  入站请求对象。
     * @param response 出站响应对象。
     * @return 表示处理完成阶段的 Void 对象。
     */
    @Override
    public CompletionStage<Void> handleReactive(ServerRequest request, ServerResponse response) {
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    handle(request, response);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, ThreadUtils.GLOBAL_EXECUTOR);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

}
