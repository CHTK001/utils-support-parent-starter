package com.chua.common.support.network.client;

import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.network.http.HttpHeader;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * JDK 内置 {@link java.net.http.HttpClient} 的执行器实现。
 *
 * <p>使用 Java 11+ 标准库中的 {@link java.net.http.HttpClient} 作为底层 HTTP 引擎，
 * 无需引入任何第三方依赖即可完成 HTTP 请求。作为 {@link HttpClientFactory} 的默认回退执行器，
 * 在 OkHttp / HttpClient5 等外部依赖缺失时始终可用。</p>
 *
 * <p><b>特性说明：</b></p>
 * <ul>
 *   <li><b>HTTP/2 支持</b> — JDK HttpClient 默认支持 HTTP/2 协议</li>
 *   <li><b>自动重定向</b> — 默认跟随 3xx 重定向（{@link HttpClient.Redirect#NORMAL}）</li>
 *   <li><b>请求体类型支持</b> — 支持 {@link String}、{@code byte[]} 及任意 Java 对象</li>
 *   <li><b>响应处理</b> — 将底层响应统一封装为 {@link ClientResponse}</li>
 *   <li><b>线程安全</b> — 内部 {@link HttpClient} 实例是线程安全的，可全局共享</li>
 * </ul>
 *
 * @author CH
 * @see HttpClientExecutor
 * @see HttpClientFactory
 */
public class JdkHttpClientExecutor implements HttpClientExecutor {

    /**
     * JDK 内置 HTTP 客户端实例（跟随重定向）。
     *
     * <p>配置了自动跟随标准重定向（NORMAL 模式：跟随 HTTP→HTTP 和 HTTPS→HTTPS 的重定向，
     * 但不跟随 HTTP→HTTPS 的协议降级重定向）。客户端实例是线程安全的，可被多个请求共享。</p>
     */
    private final HttpClient redirectClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * JDK 内置 HTTP 客户端实例（不跟随重定向）。
     *
     * <p>配置为从不跟随重定向（NEVER 模式）。当请求设置了自定义重定向处理器
     * 或显式禁用了自动跟随重定向时使用此客户端，以便执行器收到原始的 3xx 响应。</p>
     */
    private final HttpClient noRedirectClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 根据请求配置创建或选择对应的 JDK HttpClient 实例。
     *
     * <p>如果请求设置了代理，则动态创建带代理配置的新客户端；
     * 否则使用预先创建的两个缓存实例（redirectClient / noRedirectClient）。</p>
     *
     * @param request 请求对象，包含代理和重定向配置
     * @return 适合当前请求的 JDK HttpClient 实例
     */
    private HttpClient resolveHttpClient(com.chua.common.support.network.client.ClientRequest request) {
        // 检查是否需要自定义配置的客户端
        boolean needsCustomClient = (request.getProxyHost() != null && !request.getProxyHost().isEmpty())
                || request.getKeepAliveTimeout() != 60000;
        if (needsCustomClient) {
            HttpClient.Builder builder = HttpClient.newBuilder();
            builder.followRedirects(request.isFollowRedirects()
                    ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
            builder.connectTimeout(Duration.ofMillis(request.getConnectTimeout()));
            // 配置代理
            if (request.getProxyHost() != null && !request.getProxyHost().isEmpty()) {
                builder.proxy(ProxySelector.of(
                        new InetSocketAddress(request.getProxyHost(), request.getProxyPort())));
            }
            return builder.build();
        }
        // 无自定义配置：使用缓存的客户端实例
        return request.isFollowRedirects() ? redirectClient : noRedirectClient;
    }

    /**
     * 执行 HTTP 请求。
     *
     * <p>将 {@link ClientRequest} 转换为 JDK 内置的 {@link HttpRequest} 并发送。
     * 执行流程如下：</p>
     * <ol>
     *   <li><b>构建请求</b> — 设置 URI、超时时间</li>
     *   <li><b>设置请求头</b> — 遍历 {@link ClientRequest#getHeaders()} 逐条添加</li>
     *   <li><b>设置方法和体</b> — 根据 HTTP 方法选择对应的 BodyPublisher</li>
     *   <li><b>选择客户端</b> — 根据代理和重定向配置选择或创建 HttpClient</li>
     *   <li><b>发送请求</b> — 同步发送，等待响应</li>
     *   <li><b>封装响应</b> — 将 JDK 响应转换为统一的 {@link ClientResponse}</li>
     * </ol>
     *
     * @param request 封装好的请求对象
     * @return 响应对象 {@link ClientResponse}
     * @throws Exception 网络异常、超时、URI 格式错误等
     */
    @Override
    public ClientResponse execute(com.chua.common.support.network.client.ClientRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()))
                .timeout(Duration.ofMillis(request.getReadTimeout()));

        // 设置请求头
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().toMap().entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        // 根据 HTTP 方法设置请求体和内容发布器
        switch (request.getMethod()) {
            case GET:
                builder.GET();
                break;
            case DELETE:
                builder.DELETE();
                break;
            case POST:
                builder.POST(bodyPublisher(request));
                break;
            case PUT:
                builder.PUT(bodyPublisher(request));
                break;
            case PATCH:
                builder.method("PATCH", bodyPublisher(request));
                break;
            case HEAD:
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                break;
            case OPTIONS:
                builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                break;
            default:
                throw new UnsupportedOperationException("Unsupported method: " + request.getMethod());
        }

        // 根据代理和重定向配置选择合适的客户端实例
        HttpClient targetClient = resolveHttpClient(request);

        // 发送请求并获取响应（同步）
        HttpResponse<byte[]> response = targetClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        // 封装响应
        ClientResponse cr = new ClientResponse();
        cr.setStatusCode(response.statusCode());
        cr.setBody(response.body());
        HttpHeader header = HttpHeader.create();
        response.headers().map().forEach((k, v) -> header.add(k, String.join(", ", v)));
        cr.setHeaders(header);

        // 当不自动跟随重定向且有自定义重定向处理器时，触发处理器
        if (!request.isFollowRedirects()) {
            Consumer<ClientResponse> handler = request.getRedirectHandler();
            if (handler != null && isRedirect(response.statusCode())) {
                handler.accept(cr);
            }
        }

        return cr;
    }

    /**
     * 根据请求体内容创建对应的 {@link HttpRequest.BodyPublisher}。
     *
     * <p>支持以下类型的请求体：</p>
     * <ul>
     *   <li>{@code null} — 返回 {@code noBody()}（空体）</li>
     *   <li>{@code byte[]} — 使用 {@code ofByteArray()}，适用二进制数据及{@link MultipartBody}序列化结果</li>
     *   <li>{@link String} — 使用 {@code ofString()}，适用 JSON/XML 等文本数据</li>
     *   <li>其他对象 — 调用 {@link Object#toString()} 转为字符串后发布</li>
     * </ul>
     *
     * @param request 请求对象
     * @return 对应的 BodyPublisher
     */
    private HttpRequest.BodyPublisher bodyPublisher(com.chua.common.support.network.client.ClientRequest request) {
        Object body = request.getBody();
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (body instanceof byte[] b) {
            return HttpRequest.BodyPublishers.ofByteArray(b);
        }
        if (body instanceof String s) {
            return HttpRequest.BodyPublishers.ofString(s);
        }
        return HttpRequest.BodyPublishers.ofString(body.toString());
    }

    /**
     * 判断是否为重定向状态码（3xx）。
     *
     * @param statusCode HTTP 状态码
     * @return true 表示是重定向状态码
     */
    private static boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    /**
     * 异步执行 HTTP 请求。
     *
     * <p>使用 JDK HttpClient 的 {@link HttpClient#sendAsync(HttpRequest, HttpResponse.BodyHandler)}
     * 方法实现真正的异步 I/O，无需额外线程。与默认的虚拟线程包装实现不同，
     * 此方法不会阻塞任何线程，完全由 JDK 内部的 NIO 事件驱动。</p>
     *
     * <p><b>优势：</b></p>
     * <ul>
     *   <li>零阻塞 — 请求发送和响应读取完全异步</li>
     *   <li>资源高效 — 使用少量 I/O 线程处理大量并发请求</li>
     *   <li>与虚拟线程互补 — 虚拟线程处理 CPU 密集型回调，NIO 处理网络 I/O</li>
     * </ul>
     *
     * @param request 封装好的请求对象
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    @Override
    public CompletableFuture<ClientResponse> executeAsync(ClientRequest request) {
        try {
            // 构建 JDK HttpRequest（复用同步方法的构建逻辑）
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.getUrl()))
                    .timeout(Duration.ofMillis(request.getReadTimeout()));

            // 设置请求头
            if (request.getHeaders() != null) {
                for (Map.Entry<String, String> entry : request.getHeaders().toMap().entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            // 根据 HTTP 方法设置请求体和内容发布器
            switch (request.getMethod()) {
                case GET:
                    builder.GET();
                    break;
                case DELETE:
                    builder.DELETE();
                    break;
                case POST:
                    builder.POST(bodyPublisher(request));
                    break;
                case PUT:
                    builder.PUT(bodyPublisher(request));
                    break;
                case PATCH:
                    builder.method("PATCH", bodyPublisher(request));
                    break;
                case HEAD:
                    builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                    break;
                case OPTIONS:
                    builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported method: " + request.getMethod());
            }

            // 根据代理和重定向配置选择合适的客户端实例
            HttpClient targetClient = resolveHttpClient(request);

            // 异步发送请求并处理重定向回调
            CompletableFuture<HttpResponse<byte[]>> future = targetClient
                    .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            return future.thenApply(response -> {
                // 封装响应
                ClientResponse cr = new ClientResponse();
                cr.setStatusCode(response.statusCode());
                cr.setBody(response.body());
                HttpHeader header = HttpHeader.create();
                response.headers().map().forEach((k, v) -> header.add(k, String.join(", ", v)));
                cr.setHeaders(header);

                // 当不自动跟随重定向且有自定义重定向处理器时，触发处理器
                if (!request.isFollowRedirects()) {
                    Consumer<ClientResponse> handler = request.getRedirectHandler();
                    if (handler != null && isRedirect(response.statusCode())) {
                        handler.accept(cr);
                    }
                }

                return cr;
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 获取执行器名称。
     *
     * @return 固定返回 {@code "jdk"}
     */
    @Override
    public String getName() {
        return "jdk";
    }

    /**
     * 判断当前环境是否支持此执行器。
     *
     * <p>JDK 内置执行器始终可用，无需外部依赖。</p>
     *
     * @return 始终返回 true
     */
    @Override
    public boolean isAvailable() {
        return true;
    }
}
