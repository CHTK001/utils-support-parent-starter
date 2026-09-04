package com.chua.common.support.network.invoker;

import com.chua.common.support.network.client.HttpApiOptions;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.HttpInterceptor;
import com.chua.common.support.network.http.HttpVersion;
import com.chua.common.support.network.invoker.filter.InjectCallback;
import com.chua.common.support.network.invoker.filter.SharedInvocationContext;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@link com.chua.common.support.network.client.HttpApiFactory} 的默认 HTTP 调用器实现。
 *
 * <p>使用项目自有的声明式 HTTP 客户端框架，通过 JDK 动态代理将接口方法调用
 * 转换为 HTTP 请求。支持 Spring MVC 注解和 {@code @RequestMethod} 注解。</p>
 *
 * <p>SPI 名称为 {@code "http"}，order=0 作为默认兜底实现。
 * 第三方集成（如 Retrofit）可通过 SPI 提供更高优先级的实现。</p>
 *
 * <p><b>自定义与集成：</b>通过 {@link #of()} 创建的独立实例支持链式自定义：</p>
 * <ul>
 *   <li>{@link #baseUrl(String)} — 覆盖接口类级注解解析出的 baseUrl（多环境/网关路由）</li>
 *   <li>{@link #client(HttpClient)} — 注入自定义 {@link HttpClient}（含已注册的拦截器）</li>
 *   <li>{@link #addInterceptor(HttpInterceptor)} / {@link #addNetworkInterceptor(HttpInterceptor)} —
 *       仅对当前 Invoker 生成的代理生效的拦截器（统一加 Token、Header、日志等）</li>
 *   <li>未做任何自定义时，{@link #create(Class)} 行为与 SPI 默认实例完全一致</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 自定义 Invoker：覆盖 baseUrl + 统一注入 Token
 * Invoker invoker = HttpInvoker.of()
 *     .baseUrl("http://gateway.example.com")
 *     .addInterceptor((chain, request) -> {
 *         request.header("Authorization", "Bearer " + TokenManager.getToken());
 *         return chain.proceed(request);
 *     });
 *
 * UserApi api = invoker.createNew(UserApi.class);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.common.support.network.client.HttpApiFactory
 * @see Invoker
 */
@Spi(value = "http", order = 0)
public class HttpInvoker implements Invoker {

    /**
     * 自定义配置（baseUrl/客户端/拦截器）。
     *
     * <p>为 null 或全默认值时，走原有 {@code HttpApiFactory.create/createNew} 快捷路径。</p>
     */
    private HttpApiOptions options;

    /**
     * 创建自定义 HTTP Invoker 实例。
     *
     * <p>SPI 默认实例为无状态单例；如需自定义（baseUrl/客户端/拦截器），
     * 应通过本方法创建独立实例，避免影响全局默认行为。</p>
     *
     * @return 可链式自定义的 HttpInvoker 实例
     */
    public static HttpInvoker of() {
        return new HttpInvoker();
    }

    /**
     * 覆盖接口级注解解析出的 baseUrl。
     *
     * <p>优先级：此处设置的 baseUrl &gt; 接口类级注解（{@code @RequestMethod}/{@code @RemoteService}）中声明的域名。</p>
     *
     * @param baseUrl 自定义 baseUrl，如 {@code "http://gateway.example.com"}
     * @return 当前实例（链式调用）
     */
    public HttpInvoker baseUrl(String baseUrl) {
        options().baseUrl(baseUrl);
        return this;
    }

    /**
     * 注入自定义底层 {@link HttpClient}。
     *
     * <p>可使用 {@link HttpClientFactory#newClient()} 创建独立客户端并链式注册拦截器，
     * 避免污染全局单例。</p>
     *
     * @param client 自定义 HttpClient
     * @return 当前实例（链式调用）
     */
    public HttpInvoker client(HttpClient client) {
        options().client(client);
        return this;
    }

    /**
     * 注册应用层拦截器（仅对当前 Invoker 生成的代理生效）。
     *
     * <p>适合统一加 Token、Header、日志、请求预处理等。
     * 优先级：client 上注册的拦截器 &gt; 此处注册的应用层拦截器 &gt; 网络层拦截器。</p>
     *
     * @param interceptor 应用层拦截器
     * @return 当前实例（链式调用）
     */
    public HttpInvoker addInterceptor(HttpInterceptor interceptor) {
        options().addInterceptor(interceptor);
        return this;
    }

    /**
     * 注册网络层拦截器（仅对当前 Invoker 生成的代理生效）。
     *
     * <p>紧贴网络调用，适合监控、错误码统一包装、网络层日志等。</p>
     *
     * @param interceptor 网络层拦截器
     * @return 当前实例（链式调用）
     */
    public HttpInvoker addNetworkInterceptor(HttpInterceptor interceptor) {
        options().addNetworkInterceptor(interceptor);
        return this;
    }

    /**
     * 注册编程式注入规则（与 {@code @RemoteInject} 注解功能一致）。
     *
     * <p>每次远程调用前执行回调，将返回值按 target 注入：</p>
     * <ul>
     *   <li>{@code "headers.X"} — 注入到请求头 {@code X}（如统一加 Token）</li>
     *   <li>{@code "attributes.X"} — 注入到共享属性 {@code X}（可被后续注入规则读取）</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * Invoker invoker = HttpInvoker.of()
     *     .addInject("headers.Authorization", ctx -> "Bearer " + TokenManager.getToken())
     *     .addInject("attributes.traceId", ctx -> TraceContext.nextId());
     * UserApi api = invoker.create(UserApi.class);
     * }</pre>
     *
     * @param target   注入目标路径，如 {@code "headers.Authorization"}
     * @param callback 注入回调，每次调用时执行，返回注入值；返回 null 则跳过
     * @return 当前实例（链式调用）
     */
    @Override
    public HttpInvoker addInject(String target, InjectCallback callback) {
        options().addInject(target, callback);
        return this;
    }

    /**
     * 获取已注册的注入规则（只读）。
     *
     * @return 注入规则列表
     */
    public List<SharedInvocationContext.InjectRule> getInjectRules() {
        return options == null ? List.of() : new ArrayList<>(options.getInjectRules());
    }

    /**
     * 添加默认请求头（当前 Invoker 生成的代理每次调用自动携带）。
     *
     * @param name  请求头名称，如 {@code "Accept"}
     * @param value 请求头值，如 {@code "application/json"}
     * @return 当前实例（链式调用）
     */
    public HttpInvoker header(String name, String value) {
        options().header(name, value);
        return this;
    }

    /**
     * 批量添加默认请求头。
     *
     * @param headers 请求头 Map
     * @return 当前实例（链式调用）
     */
    public HttpInvoker headers(Map<String, String> headers) {
        options().headers(headers);
        return this;
    }

    /**
     * 设置默认连接超时（毫秒）。
     *
     * @param timeout 连接超时，-1 表示使用执行器默认值
     * @return 当前实例（链式调用）
     */
    public HttpInvoker connectTimeout(long timeout) {
        options().connectTimeout(timeout);
        return this;
    }

    /**
     * 设置默认读取超时（毫秒）。
     *
     * @param timeout 读取超时，-1 表示使用执行器默认值
     * @return 当前实例（链式调用）
     */
    public HttpInvoker readTimeout(long timeout) {
        options().readTimeout(timeout);
        return this;
    }

    /**
     * 设置默认写入超时（毫秒）。
     *
     * @param timeout 写入超时，-1 表示使用执行器默认值
     * @return 当前实例（链式调用）
     */
    public HttpInvoker writeTimeout(long timeout) {
        options().writeTimeout(timeout);
        return this;
    }

    /**
     * 设置默认最大重试次数。
     *
     * @param retries 最大重试次数，-1 表示不重试
     * @return 当前实例（链式调用）
     */
    public HttpInvoker retry(int retries) {
        options().retry(retries);
        return this;
    }

    /**
     * 设置默认响应缓存有效期（毫秒）。
     *
     * @param ttlMs 缓存有效期，-1 表示不缓存
     * @return 当前实例（链式调用）
     */
    public HttpInvoker cache(long ttlMs) {
        options().cache(ttlMs);
        return this;
    }

    /**
     * 设置默认是否跟随重定向。
     *
     * @param follow true 跟随重定向，false 不跟随
     * @return 当前实例（链式调用）
     */
    public HttpInvoker followRedirects(boolean follow) {
        options().followRedirects(follow);
        return this;
    }

    /**
     * 设置默认 HTTP 协议版本。
     *
     * @param version HTTP 版本（HTTP_1_1 / HTTP_2）
     * @return 当前实例（链式调用）
     */
    public HttpInvoker version(HttpVersion version) {
        options().version(version);
        return this;
    }

    /**
     * 设置默认 HTTP 代理。
     *
     * @param host 代理服务器主机名或 IP 地址
     * @param port 代理服务器端口号
     * @return 当前实例（链式调用）
     */
    public HttpInvoker proxy(String host, int port) {
        options().proxy(host, port);
        return this;
    }

    /**
     * 获取已注册的应用层拦截器（只读）。
     *
     * @return 应用层拦截器列表
     */
    public List<HttpInterceptor> getInterceptors() {
        return options == null ? List.of() : new ArrayList<>(options.getInterceptors());
    }

    /**
     * 获取已注册的网络层拦截器（只读）。
     *
     * @return 网络层拦截器列表
     */
    public List<HttpInterceptor> getNetworkInterceptors() {
        return options == null ? List.of() : new ArrayList<>(options.getNetworkInterceptors());
    }

    /**
     * 懒加载并返回自定义配置实例。
     *
     * @return HttpApiOptions 实例
     */
    private HttpApiOptions options() {
        if (options == null) {
            options = HttpApiOptions.of();
        }
        return options;
    }

    @Override
    /** 创建 */
    public <T> T create(Class<T> apiClass) {
        if (isDefault()) {
            return com.chua.common.support.network.client.HttpApiFactory.create(apiClass);
        }
        return com.chua.common.support.network.client.HttpApiFactory.create(apiClass, options);
    }

    @Override
    /** 创建New */
    public <T> T createNew(Class<T> apiClass) {
        if (isDefault()) {
            return com.chua.common.support.network.client.HttpApiFactory.createNew(apiClass);
        }
        return com.chua.common.support.network.client.HttpApiFactory.createNew(apiClass, options);
    }

    /**
     * 判断当前是否处于默认（未自定义）状态。
     *
     * @return true 表示未做任何自定义，可走快捷路径
     */
    private boolean isDefault() {
        if (options == null) {
            return true;
        }
        return options.getBaseUrl() == null && options.getClient() == null
                && options.getInterceptors().isEmpty() && options.getNetworkInterceptors().isEmpty()
                && options.getInjectRules().isEmpty() && options.getDefaultHeaders().isEmpty()
                && options.getConnectTimeout() < 0 && options.getReadTimeout() < 0
                && options.getWriteTimeout() < 0 && options.getMaxRetries() < 0
                && options.getCacheTtl() < 0 && options.getFollowRedirects() == null
                && options.getVersion() == null && options.getProxyHost() == null;
    }
}