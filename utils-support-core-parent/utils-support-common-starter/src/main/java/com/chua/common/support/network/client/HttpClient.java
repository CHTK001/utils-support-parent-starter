package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpMethod;

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
     * 异步执行 HTTP 请求，返回 {@link CompletableFuture}。
     *
     * <p>发送异步 HTTP 请求，不会阻塞当前线程。返回的 {@link CompletableFuture}
     * 在请求完成后完成，可通过 thenApply/whenComplete 等链式方法组合异步逻辑。
     * 默认实现使用虚拟线程包装 {@link #execute(ClientRequest)} 同步调用。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * client.executeAsync(ClientRequest.of("http://api.example.com"))
     *     .thenApply(ClientResponse::getBodyString)
     *     .thenAccept(System.out::println)
     *     .exceptionally(err -> { System.err.println(err.getMessage()); return null; });
     * }</pre>
     *
     * @param request 封装好的请求对象
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    default CompletableFuture<ClientResponse> executeAsync(ClientRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request));
    }

    /**
     * 异步执行 HTTP 请求，通过回调通知结果。
     *
     * <p>以回调风格发送异步 HTTP 请求，无需手动管理 {@link CompletableFuture}。
     * 成功时调用 {@link Callback#onSuccess(Object)}，失败时调用
     * {@link Callback#onError(Throwable)}。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * client.executeAsync(ClientRequest.of("http://api.example.com"), new Callback<>() {
     *     public void onSuccess(ClientResponse resp) {
     *         System.out.println(resp.getBodyString());
     *     }
     *     public void onError(Throwable err) {
     *         System.err.println(err.getMessage());
     *     }
     * });
     *
     * // Lambda 简化写法
     * client.executeAsync(request,
     *     resp -> System.out.println(resp.getBodyString()),
     *     err  -> System.err.println(err.getMessage())
     * );
     * }</pre>
     *
     * @param request  封装好的请求对象
     * @param callback 异步回调，成功时回调 {@link Callback#onSuccess(Object)}，
     *                 失败时回调 {@link Callback#onError(Throwable)}
     */
    default void executeAsync(ClientRequest request, Callback<ClientResponse> callback) {
        executeAsync(request).whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
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

