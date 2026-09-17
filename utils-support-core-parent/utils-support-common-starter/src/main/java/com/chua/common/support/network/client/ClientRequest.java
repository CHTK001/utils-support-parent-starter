package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpVersion;

import java.util.Map;
import java.util.function.Consumer;

/**
* HTTP 客户端请求封装，包含 URL、方法、请求头、请求体、超时等全部请求参数。
*
* <p>本类作为 {@link HttpClientBuilder} 与底层 {@code HttpClientExecutor} 之间的数据传输对象（DTO），
* 由 {@link HttpClientBuilder#execute()} 方法组装后传给执行器处理。
* 通常不直接创建此类，而是通过 {@link HttpClientBuilder} 的链式 API 构建。
*
* <p><b>创建方式：</b>
* <ul>
*   <li>推荐：通过 {@code HttpClientFactory.of(url).post()} 链式构建，内部自动创建此对象</li>
*   <li>直接创建：{@code ClientRequest.of(url)} 或 {@code ClientRequest.of(url, HttpMethod.POST)}</li>
* </ul>
*
* <p><b>字段说明：</b>
* <ul>
*   <li>{@link #url} — 完整的请求 URL（含协议、主机、端口、路径、查询参数）</li>
*   <li>{@link #method} — HTTP 请求方法，默认 {@link HttpMethod#GET}</li>
*   <li>{@link #headers} — 请求头集合，默认空请求头</li>
*   <li>{@link #body} — 请求体，支持 String、byte[] 或任意 Java 对象</li>
*   <li>{@link #params} — 查询参数，由执行器拼接到 URL 末尾</li>
*   <li>{@link #connectTimeout} / {@link #readTimeout} — 超时配置，默认均为 30 秒</li>
*   <li>{@link #followRedirects} — 是否跟随 3xx 重定向，默认跟随</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @see HttpClientBuilder
* @see com.chua.common.support.network.client.spi.HttpClientExecutor
 */
public class ClientRequest {

    /**
    * 完整的请求 URL。
    *
    * <p>包含协议（http/https）、主机名、端口号、路径和查询参数。
    * 例如：{@code "http://api.example.com:8080/users?page=1"}。
    * 由 {@link HttpClientBuilder#execute()} 方法根据 baseUrl + path 拼接而成。
    */
    private String url;

    /**
    * HTTP 请求方法，默认为 {@link HttpMethod#GET}。
    *
    * <p>支持 GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS 七种标准 RESTful 方法。
    * 由 {@link HttpClientBuilder#get()}、{@link HttpClientBuilder#post()} 等快捷方法自动设置。
    */
    private HttpMethod method = HttpMethod.GET;

    /**
    * 请求头集合。
    *
    * <p>内部使用 {@link HttpHeader} 封装，基于 {@link java.util.LinkedHashMap} 保持插入顺序。
    * 可通过 {@link #header(String, String)} 方法链式添加，或直接使用 {@link #setHeaders(HttpHeader)} 批量设置。
    * 默认值为空请求头（{@code HttpHeader.create()}）。
    */
    private HttpHeader headers = HttpHeader.create();

    /**
    * 请求体内容。
    *
    * <p>支持以下类型：
    * <ul>
    *   <li>{@link String} — 字符串（JSON/XML/纯文本等）</li>
    *   <li>{@code byte[]} — 二进制数据</li>
    *   <li>其他对象 — 由执行器调用 {@link Object#toString()} 转为字符串后发送</li>
    *   <li>{@link MultipartBody} — multipart 表单数据（含文件上传）</li>
    * </ul>
    *
    * <p>null 表示无请求体，适用于 GET、DELETE、HEAD 等无需请求体的方法。
    */
    private Object body;

    /**
    * URL 查询参数。
    *
    * <p>键值对集合，由底层 {@code HttpClientExecutor} 在发送请求时拼接到 URL 的 query string 中。
    * 例如 {@code {"page": "1", "size": "20"}} 会拼接为 {@code ?page=1&size=20}。
    * 具体拼接行为取决于选用的执行器实现（OkHttp / HttpClient5 / JDK）。
    */
    private Map<String, String> params;

    /**
    * 连接超时时间（毫秒）。
    *
    * <p>指从客户端发起 TCP 连接到与服务器建立连接的最大等待时间。
    * 默认值：{@code 30000} 毫秒（30 秒）。
    * 超过此时间仍未建立连接时，执行器会抛出超时异常。
    *
    * @see HttpClientBuilder#connectTimeout(long)
    */
    private long connectTimeout = 30000;

    /**
    * 读取超时时间（毫秒）。
    *
    * <p>指从服务器建立连接后，等待服务器返回数据的最大时间间隔。
    * 默认值：{@code 30000} 毫秒（30 秒）。
    * 超过此时间仍未收到任何数据时，执行器会抛出超时异常。
    *
    * @see HttpClientBuilder#readTimeout(long)
    */
    private long readTimeout = 30000;

    /**
    * 是否自动跟随 HTTP 3xx 重定向。
    *
    * <p>默认值：{@code true}（自动跟随）。
    * 设置为 false 时，执行器会返回 3xx 响应而非自动跳转，
    * 适用于需要手动处理重定向的场景（如 Cookie 鉴权、跨域跳转等）。
    */
    private boolean followRedirects = true;

    /**
    * 自定义重定向处理器。
    *
    * <p>当服务器返回 3xx 重定向响应且 {@link #followRedirects} 为 false 时，
    * 执行器会调用此处理器，将响应对象传递给它。调用方可在处理器中检查
    * {@code Location} 头，决定是否以及如何手动处理重定向。</p>
    *
    * <p>null 表示不启用自定义重定向处理。</p>
    */
    private Consumer<ClientResponse> redirectHandler;

    /**
    * 代理服务器主机名或 IP 地址。
    *
    * <p>当需要通过 HTTP 代理访问目标服务器时设置此项。
    * null 或空字符串表示不使用代理。</p>
    *
    * @see #proxyPort
    * @see #setProxy(String, int)
    */
    private String proxyHost;

    /**
    * 代理服务器端口号。
    *
    * <p>与 {@link #proxyHost} 配合使用，当 proxyHost 为 null 或空时此值无效。</p>
    */
    private int proxyPort;

    /**
    * 连接保活超时时间（毫秒）。
    *
    * <p>空闲连接在连接池中的最大存活时间。超过此时间未使用的连接将被关闭。
    * 默认值：{@code 60000} 毫秒（60 秒）。
    *
    * @see HttpClientBuilder#keepAliveTimeout(long)
    */
    private long keepAliveTimeout = 60000;

    /**
    * 写入超时时间（毫秒）。
    *
    * <p>指从发起请求到数据写入完成的最大等待时间。
    * 默认值：{@code 30000} 毫秒（30 秒）。
    */
    private long writeTimeout = 30000;

    /**
    * HTTP 协议版本。
    *
    * <p>指定客户端与服务器通信时使用的 HTTP 版本，null 表示使用执行器默认版本。
    */
    private HttpVersion version;

    /**
    * 缓存有效期（毫秒）。
    *
    * <p>响应缓存的有效期，默认值 {@code -1} 表示不启用缓存。
    */
    private long cacheTtl = -1;

    /**
    * 最大重试次数。
    *
    * <p>请求失败时的最大重试次数，默认值 {@code 0} 表示不重试。
    */
    private int maxRetries = 0;

    /**
    * 请求级拦截器。
    *
    * <p>优先级低于客户端级（{@code HttpClient.addInterceptor}）应用层拦截器，
    * 高于网络层拦截器。用于为单次请求附加额外的统一处理（如 Token 注入、日志）。
    * null 表示无请求级拦截器。</p>
    *
    * @see HttpClient#addInterceptor(HttpInterceptor)
    */
    private HttpInterceptor interceptor;

    /**
    * 获取请求级拦截器。
    *
    * @return 请求级拦截器，未设置时返回 null
    */
    public HttpInterceptor getInterceptor() { return interceptor; }

    /**
    * 设置请求级拦截器。
    *
    * @param interceptor 请求级拦截器，null 表示清除
    * @return 当前请求实例（链式调用）
    */
    public ClientRequest setInterceptor(HttpInterceptor interceptor) {
        this.interceptor = interceptor;
        return this;
    }

    /**
    * 获取请求 URL。
    *
    * @return 完整的请求 URL 字符串
    */
    public String getUrl() { return url; }

    /**
    * 设置请求 URL。
    *
    * @param url 完整的请求 URL，包含协议、主机、端口、路径和查询参数
    */
    public void setUrl(String url) { this.url = url; }

    /**
    * 获取 HTTP 请求方法。
    *
    * @return HTTP 请求方法，默认为 GET
    */
    public HttpMethod getMethod() { return method; }

    /**
    * 设置 HTTP 请求方法。
    *
    * @param method HTTP 请求方法（GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS）
    */
    public void setMethod(HttpMethod method) { this.method = method; }

    /**
    * 获取请求头集合。
    *
    * @return 请求头集合，不会返回 null
    */
    public HttpHeader getHeaders() { return headers; }

    /**
    * 设置请求头集合。
    *
    * <p>会替换现有的请求头集合。如需增量添加，请使用 {@link #header(String, String)} 方法。
    *
    * @param headers 请求头集合，传入 null 会使用空请求头
    */
    public void setHeaders(HttpHeader headers) {
        this.headers = headers != null ? headers : HttpHeader.create();
    }

    /**
    * 获取请求体。
    *
    * @return 请求体对象，可能为 null、String、byte[]、{@link MultipartBody} 或任意对象
    */
    public Object getBody() { return body; }

    /**
    * 设置请求体。
    *
    * @param body 请求体对象，支持 String、byte[]、{@link MultipartBody} 或任意 Java 对象；
    *             null 表示无请求体
    */
    public void setBody(Object body) { this.body = body; }

    /**
    * 获取 URL 查询参数。
    *
    * @return 查询参数 Map，可能为 null
    */
    public Map<String, String> getParams() { return params; }

    /**
    * 设置 URL 查询参数。
    *
    * @param params 查询参数 Map，键为参数名，值为参数值；
    *               由执行器拼接到 URL 末尾作为 query string
    */
    public void setParams(Map<String, String> params) { this.params = params; }

    /**
    * 获取连接超时时间。
    *
    * @return 连接超时时间（毫秒）
    */
    public long getConnectTimeout() { return connectTimeout; }

    /**
    * 设置连接超时时间。
    *
    * @param connectTimeout 连接超时时间（毫秒），必须为正数
    */
    public void setConnectTimeout(long connectTimeout) { this.connectTimeout = connectTimeout; }

    /**
    * 获取读取超时时间。
    *
    * @return 读取超时时间（毫秒）
    */
    public long getReadTimeout() { return readTimeout; }

    /**
    * 设置读取超时时间。
    *
    * @param readTimeout 读取超时时间（毫秒），0 表示无限等待（具体行为取决于底层执行器）
    */
    public void setReadTimeout(long readTimeout) { this.readTimeout = readTimeout; }

    /**
    * 获取连接保活超时时间。
    *
    * @return 保活超时时间（毫秒）
    */
    public long getKeepAliveTimeout() { return keepAliveTimeout; }

    /**
    * 设置连接保活超时时间。
    *
    * @param keepAliveTimeout 保活超时时间（毫秒），0 表示不限制
    */
    public void setKeepAliveTimeout(long keepAliveTimeout) { this.keepAliveTimeout = keepAliveTimeout; }

    /**
    * 获取写入超时时间。
    *
    * @return 写入超时时间（毫秒）
    */
    public long getWriteTimeout() { return writeTimeout; }

    /**
    * 设置写入超时时间。
    *
    * @param writeTimeout 写入超时时间（毫秒）
    */
    public void setWriteTimeout(long writeTimeout) { this.writeTimeout = writeTimeout; }

    /**
    * 获取 HTTP 协议版本。
    *
    * @return HTTP 协议版本，null 表示使用执行器默认版本
    */
    public HttpVersion getVersion() { return version; }

    /**
    * 设置 HTTP 协议版本。
    *
    * @param version HTTP 协议版本，null 表示使用执行器默认版本
    */
    public void setVersion(HttpVersion version) { this.version = version; }

    /**
    * 获取缓存有效期。
    *
    * @return 缓存有效期（毫秒），{@code -1} 表示不启用缓存
    */
    public long getCacheTtl() { return cacheTtl; }

    /**
    * 设置缓存有效期。
    *
    * @param cacheTtl 缓存有效期（毫秒），{@code -1} 表示不启用缓存
    */
    public void setCacheTtl(long cacheTtl) { this.cacheTtl = cacheTtl; }

    /**
    * 获取最大重试次数。
    *
    * @return 最大重试次数，{@code 0} 表示不重试
    */
    public int getMaxRetries() { return maxRetries; }

    /**
    * 设置最大重试次数。
    *
    * @param maxRetries 最大重试次数，{@code 0} 表示不重试
    */
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    /**
    * 判断是否自动跟随 HTTP 重定向。
    *
    * @return true 表示跟随重定向（默认），false 表示不跟随
    */
    public boolean isFollowRedirects() { return followRedirects; }

    /**
    * 设置是否自动跟随 HTTP 重定向。
    *
    * <p>当设置为 false 时，执行器会直接返回 3xx 响应结果，
    * 由调用方自行决定是否重定向。适用于需要手动处理 Cookie 或跨域跳转的场景。
    *
    * @param followRedirects true 跟随重定向，false 不跟随
    */
    public void setFollowRedirects(boolean followRedirects) { this.followRedirects = followRedirects; }

    /**
    * 获取自定义重定向处理器。
    *
    * @return 重定向处理器，未设置时返回 null
    */
    public Consumer<ClientResponse> getRedirectHandler() { return redirectHandler; }

    /**
    * 设置自定义重定向处理器。
    *
    * <p>当 {@link #followRedirects} 为 false 且执行器收到 3xx 响应时，
    * 会调用此处理器。处理器接收完整的响应对象，可用于提取 Location 头、
    * 记录日志或手动发起重定向请求。</p>
    *
    * @param redirectHandler 重定向处理器，null 表示清除
    */
    public void setRedirectHandler(Consumer<ClientResponse> redirectHandler) { this.redirectHandler = redirectHandler; }

    /**
    * 获取代理主机名。
    *
    * @return 代理主机名，null 表示不使用代理
    */
    public String getProxyHost() { return proxyHost; }

    /**
    * 设置代理主机名。
    *
    * @param proxyHost 代理主机名或 IP 地址；null 或空字符串表示不使用代理
    */
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }

    /**
    * 获取代理端口号。
    *
    * @return 代理端口号
    */
    public int getProxyPort() { return proxyPort; }

    /**
    * 设置代理端口号。
    *
    * @param proxyPort 代理端口号，当 proxyHost 为 null 时此值无效
    */
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    /**
    * 便捷方法：同时设置代理主机和端口。
    *
    * @param host 代理主机名或 IP 地址
    * @param port 代理端口号
    */
    public void setProxy(String host, int port) {
        this.proxyHost = host;
        this.proxyPort = port;
    }

    /**
    * 创建指定 URL 的 GET 请求。
    *
    * <p>快速创建仅包含 URL 的 GET 请求实例，其他参数使用默认值
    * （方法=GET、请求头=空、超时=30s、跟随重定向=true）。
    *
    * @param url 请求 URL
    * @return ClientRequest 实例
    */
    public static ClientRequest of(String url) {
        ClientRequest r = new ClientRequest();
        r.url = url;
        return r;
    }

    /**
    * 创建指定 URL 和方法的请求。
    *
    * <p>快速创建包含 URL 和请求方法的请求实例，其他参数使用默认值
    * （请求头=空、超时=30s、跟随重定向=true）。</p>
    *
    * @param url    请求 URL
    * @param method HTTP 请求方法
    * @return ClientRequest 实例
    */
    public static ClientRequest of(String url, HttpMethod method) {
        ClientRequest r = of(url);
        r.method = method;
        return r;
    }

    /**
    * 创建指定 URL、方法和请求级拦截器的请求。
    *
    * <p>快捷方法，无需后续调用 {@link #setInterceptor(HttpInterceptor)}。
    * 请求级拦截器优先级低于客户端级应用层拦截器，高于网络层拦截器。</p>
    *
    * @param url         请求 URL
    * @param method      HTTP 请求方法
    * @param interceptor 请求级拦截器
    * @return ClientRequest 实例
    */
    public static ClientRequest of(String url, HttpMethod method, HttpInterceptor interceptor) {
        ClientRequest r = of(url, method);
        r.interceptor = interceptor;
        return r;
    }

    /**
    * 添加单个请求头。
    *
    * <p>链式方法，可用于快速构建请求头：
    * <pre>{@code
    * ClientRequest request = ClientRequest.of("http://example.com")
    *     .header("Content-Type", "application/json")
    *     .header("Authorization", "Bearer token");
    * }</pre>
    *
    * <p>如果同名请求头已存在，新值会覆盖旧值（基于 {@link java.util.LinkedHashMap#put} 的语义）。
    *
    * @param name  请求头名称，如 {@code "Content-Type"}、{@code "Authorization"}
    * @param value 请求头值，如 {@code "application/json"}、{@code "Bearer xxx"}
    * @return 当前实例（链式调用）
    */
    public ClientRequest header(String name, String value) {
        this.headers.add(name, value);
        return this;
    }

    /**
    * 获取指定请求头的值。
    *
    * <p>委托给 {@link HttpHeader#get(String)}，用于在拦截器中读取/判断请求头。
    * 请求头名称是大小写敏感的，必须与添加时的名称完全一致。</p>
    *
    * @param name 请求头名称，如 {@code "Content-Type"}、{@code "Authorization"}
    * @return 请求头值，不存在返回 null
    */
    public String getHeader(String name) {
        return headers.get(name);
    }
}
