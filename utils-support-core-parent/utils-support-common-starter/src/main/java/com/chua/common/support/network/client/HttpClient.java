package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;

import java.util.concurrent.CompletableFuture;

/**
* HTTP 客户端顶层接口，提供 HTTP 请求的抽象和便捷方法。
*
* <p>本接口是整个 HTTP 客户端模块的<b>核心抽象</b>，定义了统一的请求执行契约。
* 实现类通过 {@link #execute(ClientRequest)} 方法完成实际的 HTTP 通信。
* 接口中提供了 GET、POST、PUT、DELETE 四种常用请求的默认快捷方法，
* 实现类无需重复实现这些方法，只需关注 {@link #execute(ClientRequest)} 的核心逻辑。
*
* <p><b>使用方式对比：</b></p>
* <ul>
*   <li><b>链式 API（推荐）：</b>通过 {@link HttpClientFactory#of(String)} 创建 {@link HttpClientBuilder}，<br>
*   用链式调用构建复杂请求（设置请求头、请求体、超时、表单、文件上传等），最后调用 {@code get()/post()} 执行。</li>
*   <li><b>直接调用：</b>通过 {@link HttpClientFactory#getClient()} 获取全局客户端实例，<br>
*   直接调用 {@link #get(String)}、{@link #post(String, Object)} 等快捷方法，适合简单请求。</li>
* </ul>
*
* <p><b>实现类说明：</b></p>
* <ul>
*   <li>{@link DefaultHttpClient} — 默认实现，委托给 SPI 加载的 {@code HttpClientExecutor}</li>
*   <li>{@link AbstractHttpClient} — 抽象基类，提供模板方法模式，子类只需实现 doExecute</li>
* </ul>
*
* <p><b>资源管理：</b>实现 {@link AutoCloseable} 接口，可通过 try-with-resources 自动释放资源。</p>
*
* @author CH
* @since 4.0.0.42
* @see HttpClientFactory
* @see HttpClientBuilder
* @see DefaultHttpClient
* @see AbstractHttpClient
 */
public interface HttpClient extends AutoCloseable {

    /**
    * 执行 HTTP 请求，获取响应。
    *
    * <p>核心抽象方法，所有 HTTP 请求最终都通过此方法执行。
    * 实现类应在此方法中完成以下工作：</p>
    * <ol>
    *   <li>解析 {@link ClientRequest} 中的 URL、方法、请求头、请求体等参数</li>
    *   <li>调用底层 HTTP 库（JDK HttpClient / OkHttp / HttpClient5）发送请求</li>
    *   <li>将底层响应转换为统一的 {@link ClientResponse} 返回</li>
    *   <li>处理网络异常、超时等错误情况</li>
    * </ol>
    *
    * @param request 封装好的请求对象，包含 URL、方法、请求头、请求体、超时等全部参数
    * @return 响应对象 {@link ClientResponse}，包含状态码、响应头和响应体
    * @throws RuntimeException 如果请求执行过程中发生异常
    */
    ClientResponse execute(ClientRequest request);

    /**
    * 创建请求规格，用于链式配置请求参数。
    *
    * <p>与 {@link #get(String)}、{@link #post(String, Object)} 等直接执行的方法不同，
    * 此方法返回绑定当前客户端的 {@link RequestSpec}，可继续链式设置请求头、
    * 请求体、超时等参数，最后通过 {@link RequestSpec#execute()} 执行。</p>
    *
    * @param url    请求 URL，如 {@code "http://api.example.com/users"}
    * @param method HTTP 请求方法
    * @return 链式请求规格 RequestSpec
    */
    default RequestSpec request(String url, HttpMethod method) {
        return new RequestSpec(this, url, method);
    }

    // ==================== 拦截器 ====================

    /**
    * 注册<b>应用层拦截器</b>。
    *
    * <p>应用层拦截器处于拦截器链的最外层，在请求进入网络之前执行，
    * 适合统一加 Token、加 Header、日志、请求预处理、响应后处理、短路 Mock/缓存等业务级横切逻辑。</p>
    *
    * <p><b>优先级（从外到内）：</b>
    * 客户端级应用层拦截器 → 请求级（{@link ClientRequest}）拦截器 → 网络层拦截器 → 网络调用。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * HttpClient client = HttpClientFactory.getClient();
    * client.addInterceptor((chain, request) -> {
    *     request.header("Authorization", "Bearer " + token);
    *     long start = System.currentTimeMillis();
    *     ClientResponse resp = chain.proceed(request);
    *     log.info("{} {} -> {} 耗时 {}ms", request.getMethod(), request.getUrl(),
    *             resp.getStatusCode(), System.currentTimeMillis() - start);
    *     return resp;
    * });
    * }</pre>
    *
    * <p><b>注意：</b>默认实现返回 {@code this}，因此可以在语句中连续注册多个拦截器。</p>
    *
    * @param interceptor 应用层拦截器
    * @return 当前客户端实例（链式调用）
    */
    default HttpClient addInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            getInterceptors().add(interceptor);
        }
        return this;
    }

    /**
    * 注册<b>网络层拦截器</b>。
    *
    * <p>网络层拦截器紧贴底层网络调用，位于应用层拦截器的内层，适合统一接入耗时监控、
    * 错误码统一包装、网络层日志等场景。响应与网络真实返回高度一致。</p>
    *
    * @param interceptor 网络层拦截器
    * @return 当前客户端实例（链式调用）
    */
    default HttpClient addNetworkInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            getNetworkInterceptors().add(interceptor);
        }
        return this;
    }

    /**
    * 注册<b>应用层拦截器</b>（Consumer 便捷版）。
    *
    * <p>通过 {@link Consumer} 方式处理请求，无需手动调用 {@code chain.proceed(request)}，
    * 框架会自动放行。适用于只需在请求前追加 Header 等场景；若需拦截响应或短路请求，
    * 请使用 {@link #addInterceptor(HttpInterceptor)}。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * client.interceptor(request -> request.header("X-Request-Id", requestId()));
    * }</pre>
    *
    * @param interceptor 请求预处理 Consumer，在请求发出前执行
    * @return 当前客户端实例（链式调用）
    */
    default HttpClient interceptor(Consumer<ClientRequest> interceptor) {
        return addInterceptor((chain, request) -> {
            interceptor.accept(request);
            return chain.proceed(request);
        });
    }

    /**
    * 注册<b>网络层拦截器</b>（Consumer 便捷版）。
    *
    * @param interceptor 请求预处理 Consumer，在请求发出前执行
    * @return 当前客户端实例（链式调用）
    */
    default HttpClient networkInterceptor(Consumer<ClientRequest> interceptor) {
        return addNetworkInterceptor((chain, request) -> {
            interceptor.accept(request);
            return chain.proceed(request);
        });
    }

    /**
    * 获取已注册的<b>应用层拦截器</b>列表。
    *
    * <p>默认实现返回可变的空列表，避免调用 {@link #addInterceptor(HttpInterceptor)} 时抛出
    * {@code UnsupportedOperationException}。实现类（如 {@link DefaultHttpClient}、{@link AbstractHttpClient}）
    * 应覆写此方法返回其持有的应用层拦截器列表，以便在 {@link #execute(ClientRequest)} 中组装拦截器链。</p>
    *
    * @return 应用层拦截器列表，不会返回 null
    */
    default List<HttpInterceptor> getInterceptors() {
        return new java.util.ArrayList<>();
    }

    /**
    * 获取已注册的<b>网络层拦截器</b>列表。
    *
    * <p>默认实现返回可变的空列表，避免调用 {@link #addNetworkInterceptor(HttpInterceptor)} 时抛出
    * {@code UnsupportedOperationException}。</p>
    *
    * @return 网络层拦截器列表，不会返回 null
    */
    default List<HttpInterceptor> getNetworkInterceptors() {
        return new java.util.ArrayList<>();
    }

    /**
    * 将当前客户端包装为折叠 HTTP 客户端（并发请求合并）。
    *
    * <p>并发窗口内<b>方法为 GET 且 URL 相同</b>的请求合并为一次真实网络调用，
    * 响应广播给各请求方，降低下游连接数与 I/O 次数。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * HttpClient client = HttpClientFactory.getClient().collapse();
    * // 或
    * HttpClient collapsed = newClient.collapse();
    * }</pre>
    *
    * @return 折叠 HTTP 客户端
    * @see HttpClientFactory#collapse(HttpClient)
    */
    default HttpClient collapse() {
        return HttpClientFactory.collapse(this);
    }

    /**
    * 执行 GET 请求。
    *
    * <p>快速发起 HTTP GET 请求，适用于查询、获取资源等幂等操作。
    * 内部通过 {@link #execute(ClientRequest)} 实现。</p>
    *
    * @param url 请求 URL，如 {@code "http://api.example.com/users"}
    * @return 响应对象 {@link ClientResponse}
    */
    default ClientResponse get(String url) {
        return execute(ClientRequest.of(url, HttpMethod.GET));
    }

    /**
    * 执行 POST 请求。
    *
    * <p>快速发起 HTTP POST 请求，适用于创建资源、提交表单等操作。
    * 内部通过 {@link #execute(ClientRequest)} 实现。</p>
    *
    * @param url  请求 URL，如 {@code "http://api.example.com/users"}
    * @param body 请求体对象，支持 String、byte[] 或任意 Java 对象
    * @return 响应对象 {@link ClientResponse}
    */
    default ClientResponse post(String url, Object body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.POST);
        request.setBody(body);
        return execute(request);
    }

    /**
    * 执行 PUT 请求。
    *
    * <p>快速发起 HTTP PUT 请求，适用于全量更新资源。
    * 内部通过 {@link #execute(ClientRequest)} 实现。</p>
    *
    * @param url  请求 URL，如 {@code "http://api.example.com/users/1"}
    * @param body 请求体对象，支持 String、byte[] 或任意 Java 对象
    * @return 响应对象 {@link ClientResponse}
    */
    default ClientResponse put(String url, Object body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.PUT);
        request.setBody(body);
        return execute(request);
    }

    /**
    * 执行 DELETE 请求。
    *
    * <p>快速发起 HTTP DELETE 请求，适用于删除资源。
    * 内部通过 {@link #execute(ClientRequest)} 实现。</p>
    *
    * @param url 请求 URL，如 {@code "http://api.example.com/users/1"}
    * @return 响应对象 {@link ClientResponse}
    */
    default ClientResponse delete(String url) {
        return execute(ClientRequest.of(url, HttpMethod.DELETE));
    }

    /**
    * 异步执行 HTTP 请求，返回 {@link Mono}。
    *
    * <p>发送异步 HTTP 请求，底层通过 {@code sendAsync()}（NIO 非阻塞）实现，
    * 不阻塞调用线程。返回的 {@link Mono} 在请求完成后发出响应，
    * 支持背压和 Reactor 操作符链组合（map/flatMap/timeout/retryWhen 等）。</p>
    *
    * <p><b>与 {@link #execute} 的关系：</b>
    * {@link #get}/{@link #post}/{@link #put}/{@link #delete} 走同步 {@link #execute()}；
    * 异步场景统一走本方法，通过 subscribe/block 控制执行时机。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * client.executeAsync(ClientRequest.of("http://api.example.com"))
    *     .timeout(Duration.ofSeconds(5))
    *     .doOnNext(resp -> System.out.println(resp.getBodyString()))
    *     .subscribe();
    *
    * // 或直接阻塞等待
    * ClientResponse resp = client.executeAsync(request).block();
    * }</pre>
    *
    * @param request 封装好的请求对象
    * @return 响应 Mono，完成时包含 {@link ClientResponse}
    */
    Mono<ClientResponse> executeAsync(ClientRequest request);

    /**
    * 异步执行 HTTP 请求，通过回调通知结果。
    *
    * <p>内部委托给 {@link #executeAsync(ClientRequest)}，以回调风格包装 Mono 结果。</p>
    *
    * @param request  封装好的请求对象
    * @param callback 异步回调，成功时回调 {@link Callback#onSuccess(Object)}，
    *                 失败时回调 {@link Callback#onError(Throwable)}
    */
    default void executeAsync(ClientRequest request, Callback<ClientResponse> callback) {
        executeAsync(request).subscribe(
                callback::onSuccess,
                callback::onError
        );
    }

    /**
    * 释放 HTTP 客户端占用的资源（连接池、线程等）。
    *
    * <p>实现类应在此方法中关闭底层 HTTP 客户端的连接池和线程资源。
    * 使用 try-with-resources 可以确保自动调用此方法。</p>
    */
    @Override
    void close();
}

