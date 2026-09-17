package com.chua.common.support.network.client.spi;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.http.HttpVersion;
import reactor.core.publisher.Mono;

import java.util.List;

/**
* HTTP 客户端执行器 SPI（Service Provider Interface），封装不同 HTTP 库的实现差异。
*
* <p>本接口是整个 HTTP 客户端模块的<b>可扩展点</b>。每种底层 HTTP 库（JDK / OkHttp / HttpClient5）
* 都通过实现此接口来接入统一的请求执行框架。接口的实现类通过 Java SPI 机制自动发现和加载。</p>
*
* <p><b>支持的实现：</b></p>
* <ul>
*   <li>{@link com.chua.common.support.network.client.JdkHttpClientExecutor} — JDK 内置客户端（SPI 名称：{@code "jdk"}）</li>
*   <li>OkHttp — OkHttp3 客户端（SPI 名称：{@code "okhttp"}，需 okhttp3 依赖）</li>
*   <li>HttpClient5 — Apache HttpClient5（SPI 名称：{@code "httpclient5"}，需 httpclient5 依赖）</li>
* </ul>
*
* <p><b>实现者注意事项：</b></p>
* <ul>
*   <li>实现类应通过 {@link Spi} 注解注册 SPI 名称</li>
*   <li>{@link #getName()} 返回的名称应与注解中的 SPI 名称一致</li>
*   <li>{@link #isAvailable()} 应检测当前运行时环境的依赖是否存在，避免 ClassNotFoundException</li>
*   <li>{@link #getOrder()} 返回值越小优先级越高，OkHttp 为 0，默认实现为 100</li>
*   <li>实现类应将受检异常抛出，由 {@link DefaultHttpClient} 统一包装为 {@link RuntimeException}</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @see com.chua.common.support.network.client.HttpClientFactory
* @see com.chua.common.support.network.client.DefaultHttpClient
* @see com.chua.common.support.network.client.JdkHttpClientExecutor
 */
public interface HttpClientExecutor {

    /**
    * 执行 HTTP 请求。
    *
    * <p>实现类应根据 {@link ClientRequest} 中的参数（URL、方法、请求头、请求体、超时等）
    * 构造底层 HTTP 库的请求对象并发送，然后将底层响应转换为统一的 {@link ClientResponse}。</p>
    *
    * <p>实现类应处理以下场景：</p>
    * <ul>
    *   <li>请求体为 {@link String} 或 {@code byte[]} 时直接发送</li>
    *   <li>请求体为 {@code null} 时发送无体请求（适用于 GET / DELETE / HEAD）</li>
    *   <li>请求体为其他类型时调用 {@link Object#toString()} 转为字符串</li>
    *   <li>网络异常、超时等应直接抛出受检异常</li>
    * </ul>
    *
    * @param request 封装好的请求对象，包含 URL、方法、请求头、请求体、超时等参数
    * @return 响应对象 {@link ClientResponse}，包含状态码、响应头和响应体
    * @throws Exception 网络异常、超时、URI 格式错误等，由调用方统一处理
    */
    ClientResponse execute(ClientRequest request) throws Exception;

    /**
    * 获取 HTTP 客户端执行器的 SPI 名称。
    *
    * <p>该名称用于在 {@link com.chua.common.support.network.client.HttpClientFactory} 中
    * 按名称查找和选择执行器，应与 {@code @Spi} 注解中注册的名称一致。</p>
    *
    * <p>常见的返回值：
    * <ul>
    *   <li>{@code "jdk"} — JDK 内置客户端</li>
    *   <li>{@code "okhttp"} — OkHttp3</li>
    *   <li>{@code "httpclient"} — Apache HttpClient4</li>
    *   <li>{@code "httpclient5"} — Apache HttpClient5</li>
    * </ul>
    *
    * @return SPI 名称，用于执行器查找和选择
    */
    String getName();

    /**
    * 判断当前运行时环境是否支持该执行器。
    *
    * <p>实现类应检测所需的第三方依赖是否存在于 classpath 中。
    * 例如，OkHttp 执行器应尝试加载 {@code okhttp3.OkHttpClient} 类，
    * 加载成功返回 true，失败返回 false。</p>
    *
    * <p>此方法用于 {@link com.chua.common.support.network.client.HttpClientFactory} 在多个
    * SPI 实现中自动选择可用的执行器。不可用的执行器会被跳过。</p>
    *
    * @return 当前环境可用返回 true，否则返回 false
    */
    boolean isAvailable();

    /**
    * 异步执行 HTTP 请求，返回 {@link Mono}。
    *
    * <p>子类应覆写此方法以提供真正的 NIO 非阻塞异步执行能力，
    * 例如 {@link com.chua.common.support.network.client.JdkHttpClientExecutor}
    * 使用 JDK {@code sendAsync()} 直接返回 {@code Mono}，零线程切换。</p>
    *
    * <p><b>默认实现：</b>将同步 {@link #execute} 包装为 {@code Mono.justOrEmpty()}，
    * 在 Reactor 的 {@code boundedElastic} 线程上执行。</p>
    *
    * @param request 封装好的请求对象
    * @return 响应 Mono，完成时包含 {@link ClientResponse}
    */
    default Mono<ClientResponse> executeAsync(ClientRequest request) {
        return Mono.fromFuture(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (Exception e) {
                throw new RuntimeException("HTTP async request failed: " + request.getUrl(), e);
            }
        }));
    }

    /**
    * 获取执行器的 SPI 排序值（优先级）。
    *
    * <p>值越小优先级越高，在自动选择时会被优先使用。</p>
    *
    * <p>各执行器的典型排序值：</p>
    * <ul>
    *   <li>OkHttp — {@code 0}（最高优先级）</li>
    *   <li>Apache HttpClient5 — {@code 1}</li>
    *   <li>JDK 内置 — {@code 100}（默认，最低优先级，作为回退）</li>
    * </ul>
    *
    * @return 排序值，默认 100
    */
    default int getOrder() {
        return 100;
    }

    /**
    * 获取此执行器支持的 HTTP 协议版本列表。
    *
    * <p>调用方可通过此方法判断执行器是否支持特定的 HTTP 版本（如 HTTP/2），
    * 在发送请求前进行版本兼容性检查。若请求指定的版本不在支持列表中，
    * 执行器应抛出 {@link UnsupportedOperationException}。</p>
    *
    * <p><b>各执行器的典型支持情况：</b></p>
    * <ul>
    *   <li>JDK HttpClient — HTTP/1.1、HTTP/2</li>
    *   <li>OkHttp — HTTP/1.1、HTTP/2</li>
    *   <li>Apache HttpClient5 — HTTP/1.1、HTTP/2</li>
    *   <li>Netty HttpClient — 仅 HTTP/1.1</li>
    * </ul>
    *
    * @return 支持的 HTTP 版本列表，默认包含 HTTP/1.1 和 HTTP/2
    * @see HttpVersion
    */
    default List<HttpVersion> supportedVersions() {
        return List.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2);
    }

    /**
    * 关闭执行器并释放底层资源。
    *
    * <p>关闭连接池、线程池、HTTP 客户端等底层资源。调用此方法后，
    * 执行器不应再被用于发送新的请求。</p>
    *
    * <p><b>默认实现：</b>不执行任何操作。持有底层资源的执行器（如 Netty、OkHttp）
    * 应覆写此方法以正确释放资源。</p>
    */
    default void close() {
        // 默认无操作，由持有资源的实现类覆写
    }
}
