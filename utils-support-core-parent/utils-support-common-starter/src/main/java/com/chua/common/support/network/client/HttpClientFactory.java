package com.chua.common.support.network.client;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.spi.ServiceProvider;

import java.util.concurrent.atomic.AtomicReference;

/**
* HTTP 客户端工厂，负责自动选择可用的 {@link HttpClientExecutor} 实现并创建客户端实例。
*
* <p>本类是 {@code utils-support-common-starter} HTTP 客户端模块的<b>入口点</b>，
* 提供以下两种使用方式：</p>
*
* <p><b>方式一：链式构建（推荐）</b> — 通过 {@link #of(String)} 创建 {@link HttpClientBuilder}，
* 用流式 API 构建复杂的 HTTP 请求：</p>
* <pre>{@code
* ClientResponse resp = HttpClientFactory.of("http://api.example.com")
*     .path("/users")
*     .json()
*     .body("{\"name\":\"test\"}")
*     .post();
* }</pre>
*
* <p><b>方式二：直接调用</b> — 通过 {@link #getClient()} 获取全局客户端实例，
* 直接调用 get/post/put/delete 方法：</p>
* <pre>{@code
* HttpClient client = HttpClientFactory.getClient();
* ClientResponse resp = client.get("http://api.example.com/users");
* }</pre>
*
* <p><b>执行器选择机制：</b></p>
* <p>通过 SPI（Service Provider Interface）机制自动选择可用的 HTTP 执行器，
* 按以下优先级尝试：</p>
* <ol>
*   <li><b>okhttp</b> — OkHttp3（需要 {@code okhttp3} 依赖）</li>
*   <li><b>httpclient5</b> — Apache HttpClient5（需要 {@code httpclient5} 依赖）</li>
*   <li><b>httpclient</b> — Apache HttpClient4（需要 {@code httpclient4} 依赖）</li>
*   <li><b>jdk</b> — JDK 内置 {@link java.net.http.HttpClient}（默认回退，无外部依赖）</li>
* </ol>
*
* <p>如需强制使用特定执行器，可通过 {@link #getClient(String)} 传入名称。</p>
*
* @author CH
* @since 4.0.0.42
* @see HttpClientBuilder
* @see HttpClientExecutor
 */
public class HttpClientFactory {

    /**
    * 全局客户端实例的原子引用。
    *
    * <p>使用 {@link AtomicReference} 保证线程安全的懒加载单例。
    * 仅通过 {@link #getClient()} 访问，通过 {@code CAS} 操作确保只创建一次。
    */
    private static final AtomicReference<HttpClient> CLIENT_REF = new AtomicReference<>();

    /**
    * 私有构造方法，防止外部实例化。
    *
    * <p>本类为静态工具类，所有方法均为静态方法，无需实例化。
    */
    private HttpClientFactory() {
    }

    /**
    * 获取默认 HTTP 客户端实例（全局单例）。
    *
    * <p>按 <b>okhttp &gt; httpclient5 &gt; httpclient &gt; jdk</b> 的优先级自动选择
    * 当前环境可用的底层 HTTP 执行器。线程安全，首次调用时创建客户端实例，
    * 后续调用直接返回已创建的实例。</p>
    *
    * <p><b>使用场景：</b>适用于简单请求，创建后可以直接调用
    * {@code get(url)}、{@code post(url, body)} 等快捷方法。</p>
    *
    * @return HttpClient 全局单例实例
    */
    public static HttpClient getClient() {
        HttpClient client = CLIENT_REF.get();
        if (client != null) {
            return client;
        }
        HttpClientExecutor executor = findAvailableExecutor();
        HttpClient newClient = new DefaultHttpClient(executor);
        if (CLIENT_REF.compareAndSet(null, newClient)) {
            return newClient;
        }
        return CLIENT_REF.get();
    }

    /**
    * 创建一个全新的 HTTP 客户端实例（不缓存）。
    *
    * <p>与 {@link #getClient()}（全局单例）不同，本方法每次都会创建独立的客户端实例，
    * 可用于以下场景：</p>
    * <ul>
    *   <li>需要独立拦截器配置的客户端（如在 {@code HttpInvoker} 中注册自定义拦截器，
    *       避免污染全局单例）</li>
    *   <li>需要不同超时/代理默认行为的客户端</li>
    *   <li>需要隔离且可在使用后 {@code close()} 释放资源的客户端</li>
    * </ul>
    *
    * <p>底层执行器按<b> okhttp &gt; httpclient5 &gt; httpclient &gt; jdk</b> 优先级自动选择。</p>
    *
    * @return 新的 HttpClient 实例
    */
    public static HttpClient newClient() {
        return new DefaultHttpClient(findAvailableExecutor());
    }

    /**
    * 获取指定名称的 HTTP 客户端实例（不缓存）。
    *
    * <p>通过 SPI 名称获取指定的执行器实现，每次调用都会重新创建 {@link DefaultHttpClient} 实例。
    * 适用于需要强制使用特定底层 HTTP 库的场景。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * // 强制使用 OkHttp
    * HttpClient okClient = HttpClientFactory.getClient("okhttp");
    *
    * // 强制使用 Apache HttpClient5
    * HttpClient hc5Client = HttpClientFactory.getClient("httpclient5");
    * }</pre>
    *
    * <p><b>回退策略：</b>如果指定名称的执行器不可用，会自动回退到 JDK 内置执行器
    * {@link JdkHttpClientExecutor}，确保请求不会因为执行器缺失而失败。</p>
    *
    * @param executorName 执行器 SPI 名称，可选值：{@code "okhttp"}、{@code "httpclient5"}、
    *                     {@code "httpclient"}、{@code "jdk"}
    * @return HttpClient 实例
    */
    public static HttpClient getClient(String executorName) {
        HttpClientExecutor executor = ServiceProvider.of(HttpClientExecutor.class).getExtension(executorName);
        if (executor != null && executor.isAvailable()) {
            return new DefaultHttpClient(executor);
        }
        return new DefaultHttpClient(new JdkHttpClientExecutor());
    }

    /**
    * 获取响应式 HTTP 客户端实例（全局单例）。
    *
    * <p>底层执行器与 {@link #getClient()} 相同（按优先级自动选择），
    * 但所有方法返回 {@link reactor.core.publisher.Mono}/{@link reactor.core.publisher.Flux}，
    * 支持背压和响应式操作符链。</p>
    *
    * @return 响应式 HTTP 客户端
    * @see ReactiveHttpClient
    */
    public static ReactiveHttpClient getReactiveClient() {
        return new ReactiveHttpClient(getClient());
    }

    /**
    * 包装为折叠 HTTP 客户端（默认折叠配置）。
    *
    * <p>并发窗口内方法为 GET 且 URL 相同的请求合并为一次真实网络调用，响应广播给各请求方；
    * 折叠细节与限制见 {@link CollapseHttpClient}。返回的客户端需要由使用方负责关闭。</p>
    *
    * @param delegate 底层 HTTP 客户端，不可为空
    * @return 折叠 HTTP 客户端
    */
    public static HttpClient collapse(HttpClient delegate) {
        // 包装外部客户端时默认不级联关闭，避免关闭折叠客户端误关共享底层实例
        return new CollapseHttpClient(delegate, null, false);
    }

    /**
    * 包装为折叠 HTTP 客户端（自定义折叠配置）。
    *
    * <p>通过 {@link CollapseConfig} 可调整折叠阈值、补收等待时间与虚拟线程开关，
    * 其余语义与 {@link #collapse(HttpClient)} 一致。</p>
    *
    * @param delegate 底层 HTTP 客户端，不可为空
    * @param config   折叠配置，可为空（使用默认配置）
    * @return 折叠 HTTP 客户端
    */
    public static HttpClient collapse(HttpClient delegate, CollapseConfig config) {
        // 包装外部客户端时默认不级联关闭，避免关闭折叠客户端误关共享底层实例
        return new CollapseHttpClient(delegate, config, false);
    }

    /**
    * 从 curl 命令字符串创建 {@link HttpClientBuilder}。
    *
    * <p>便捷入口，等价于 {@code HttpClientBuilder.fromCurl(curl)}。</p>
    *
    * @param curl curl 命令字符串
    * @return 构建好的 HttpClientBuilder
    */
    public static HttpClientBuilder fromCurl(String curl) {
        return CurlParser.fromCurl(curl);
    }

    /**
    * 直接执行 curl 命令并返回响应。
    *
    * <p>等价于 {@code fromCurl(curl).execute()}。</p>
    *
    * @param curl curl 命令字符串
    * @return HTTP 响应
    */
    public static ClientResponse curl(String curl) {
        return CurlParser.curl(curl);
    }

    /**
    * 创建链式 HTTP 请求构建器。
    *
    * <p>这是<b>推荐</b>的 HTTP 请求构建方式。通过链式调用依次设置
    * 请求路径、请求头、请求体、超时、查询参数等，最后调用 get/post/put/delete 执行。</p>
    *
    * <p><b>使用示例：</b></p>
    * <pre>{@code
    * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
    *     .path("/users")
    *     .header("Authorization", "Bearer xxx")
    *     .body("{\"name\":\"test\"}")
    *     .post();
    * }</pre>
    *
    * @param baseUrl 基础 URL，如 {@code "http://localhost:8080"} 或 {@code "http://localhost:8080/"}
    * @return {@link HttpClientBuilder} 构建器实例，用于链式设置请求参数
    */
    public static HttpClientBuilder of(String baseUrl) {
        return new HttpClientBuilder(baseUrl);
    }

    /**
    * 查找当前环境可用的 HTTP 执行器。
    *
    * <p>按优先级顺序遍历执行器名称列表，查找第一个可用的 {@link HttpClientExecutor} 实现。
    * 优先级：okhttp &gt; httpclient5 &gt; httpclient &gt; jdk。</p>
    *
    * <p>如果所有 SPI 执行器均不可用（外部依赖缺失），则回退到 JDK 内置执行器
    * {@link JdkHttpClientExecutor}（始终可用，无需外部依赖）。</p>
    *
    * @return 可用的 HttpClientExecutor 实例，不会返回 null
    */
    private static HttpClientExecutor findAvailableExecutor() {
        String[] names = {"okhttp", "httpclient5", "httpclient", "jdk"};
        for (String name : names) {
            HttpClientExecutor executor = ServiceProvider.of(HttpClientExecutor.class).getExtension(name);
            if (executor != null && executor.isAvailable()) {
                return executor;
            }
        }
        return new JdkHttpClientExecutor();
    }
}
