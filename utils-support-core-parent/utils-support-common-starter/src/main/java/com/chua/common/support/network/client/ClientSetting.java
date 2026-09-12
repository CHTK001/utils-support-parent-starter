package com.chua.common.support.network.client;


/**
* HTTP 客户端全局配置，定义连接超时、代理、重试等参数。
*
* <p>本类作为 {@link AbstractHttpClient} 的构造参数，用于集中管理 HTTP 客户端的全局行为。
* 所有字段均提供 getter/setter，当某个字段未设置时使用对应的默认值。
*
* <p><b>字段一览：</b>
* <ul>
*   <li>{@link #connectTimeout} — 连接超时，默认 30 秒</li>
*   <li>{@link #readTimeout} — 读取超时，默认 30 秒</li>
*   <li>{@link #writeTimeout} — 写入超时，默认 30 秒</li>
*   <li>{@link #followRedirects} — 是否跟随重定向，默认跟随</li>
*   <li>{@link #maxRetries} — 失败重试次数，默认 0（不重试）</li>
*   <li>{@link #proxyHost} / {@link #proxyPort} — HTTP 代理配置</li>
* </ul>
*
* <p><b>使用示例：</b>
* <pre>{@code
* ClientSetting setting = new ClientSetting();
* setting.setConnectTimeout(5000);
* setting.setReadTimeout(10000);
* setting.setMaxRetries(3);
*
* AbstractHttpClient client = new MyHttpClient(setting);
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see AbstractHttpClient
 */
public class ClientSetting {

    /**
    * 连接超时时间（毫秒）。
    *
    * <p>指从客户端发起 TCP 连接到与服务器建立连接的最大等待时间。
    * 默认值 {@code 30000} 毫秒（30 秒）。如果设置为 0 或负数，部分实现可能会使用平台默认值。
    *
    * @see #setConnectTimeout(long)
     */
    private long connectTimeout = 30000;

    /**
    * 读取超时时间（毫秒）。
    *
    * <p>指从服务器建立连接后，等待服务器返回数据的最大时间间隔。
    * 默认值 {@code 30000} 毫秒（30 秒）。如果设置为 0，部分实现视为无限等待。
    *
    * @see #setReadTimeout(long)
     */
    private long readTimeout = 30000;

    /**
    * 写入超时时间（毫秒）。
    *
    * <p>指将请求数据发送到服务器的最大时间。
    * 默认值 {@code 30000} 毫秒（30 秒）。
    * 适用于大文件上传等需要较长写入时间的场景。
     */
    private long writeTimeout = 30000;

    /**
    * 是否自动跟随 HTTP 3xx 重定向。
    *
    * <p>默认值 {@code true}（自动跟随）。
    * 设置为 {@code false} 时，3xx 响应会直接返回给调用方，由调用方自行处理重定向逻辑。
    * 适用于需要手动处理 Cookie、跨域跳转或自定义重定向策略的场景。
     */
    private boolean followRedirects = true;

    /**
    * 最大重试次数。
    *
    * <p>当请求因网络异常或可重试的状态码失败时，自动重试的最大次数。
    * 默认值 {@code 0}（不重试）。
    *
    * <p><b>注意：</b>重试仅对幂等请求（GET、HEAD、OPTIONS、PUT、DELETE）安全，
    * 对于非幂等请求（POST、PATCH）应谨慎使用，避免产生重复数据。
     */
    private int maxRetries;

    /**
    * 代理服务器主机名或 IP 地址。
    *
    * <p>当需要通过 HTTP 代理访问目标服务器时设置此项。
    * 例如 {@code "proxy.example.com"} 或 {@code "192.168.1.100"}。
    * null 或空字符串表示不使用代理。
    *
    * @see #proxyPort
     */
    private String proxyHost;

    /**
    * 代理服务器端口号。
    *
    * <p>与 {@link #proxyHost} 配合使用，指定代理服务器的端口。
    * 常见代理端口：HTTP 代理为 8080、3128；HTTPS 代理为 443、8443。
    * 当 {@link #proxyHost} 为 null 或空时，此值无效。
    *
    * @see #proxyHost
     */
    private int proxyPort;

    /**
    * 获取连接超时时间。
    *
    * @return 连接超时时间（毫秒）
     */
    public long getConnectTimeout() { return connectTimeout; }

    /**
    * 设置连接超时时间。
    *
    * @param connectTimeout 连接超时时间（毫秒），推荐范围：3000~30000
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
    * @param readTimeout 读取超时时间（毫秒），0 表示部分实现中为无限等待
     */
    public void setReadTimeout(long readTimeout) { this.readTimeout = readTimeout; }

    /**
    * 获取写入超时时间。
    *
    * @return 写入超时时间（毫秒）
     */
    public long getWriteTimeout() { return writeTimeout; }

    /**
    * 设置写入超时时间。
    *
    * @param writeTimeout 写入超时时间（毫秒），0 表示部分实现中为无限等待
     */
    public void setWriteTimeout(long writeTimeout) { this.writeTimeout = writeTimeout; }

    /**
    * 判断是否自动跟随 HTTP 重定向。
    *
    * @return true 表示跟随重定向，false 表示不跟随
     */
    public boolean isFollowRedirects() { return followRedirects; }

    /**
    * 设置是否自动跟随 HTTP 重定向。
    *
    * @param followRedirects true 跟随重定向，false 不跟随
     */
    public void setFollowRedirects(boolean followRedirects) { this.followRedirects = followRedirects; }

    /**
    * 获取最大重试次数。
    *
    * @return 最大重试次数，0 表示不重试
     */
    public int getMaxRetries() { return maxRetries; }

    /**
    * 设置最大重试次数。
    *
    * <p>建议仅对幂等请求（GET、HEAD、PUT、DELETE、OPTIONS）启用重试。
    *
    * @param maxRetries 最大重试次数，0 表示不重试
     */
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    /**
    * 获取代理服务器主机名。
    *
    * @return 代理主机名，null 或空字符串表示不使用代理
     */
    public String getProxyHost() { return proxyHost; }

    /**
    * 设置代理服务器主机名。
    *
    * @param proxyHost 代理主机名或 IP 地址，如 {@code "proxy.example.com"}；
    *                  null 或空字符串表示不使用代理
     */
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }

    /**
    * 获取代理服务器端口号。
    *
    * @return 代理端口号，0 表示未设置
     */
    public int getProxyPort() { return proxyPort; }

    /**
    * 代理服务器端口号。
    *
    * <p>当 {@link #proxyHost} 为 null 或空时，此设置无效。
    *
    * @param proxyPort 代理端口号，如 8080、3128
     */
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    /**
    * 连接保活超时时间（毫秒）。
    *
    * <p>空闲连接在连接池中的最大存活时间。超过此时间未使用的连接将被关闭。
    * 默认值 {@code 60000} 毫秒（60 秒）。
    * 设置为 0 表示不限制保活时间。
     */
    private long keepAliveTimeout = 60000;

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
}
