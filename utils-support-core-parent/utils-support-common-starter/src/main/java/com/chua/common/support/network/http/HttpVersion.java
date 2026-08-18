package com.chua.common.support.network.http;

/**
 * HTTP 协议版本枚举，用于指定客户端与服务器通信时使用的 HTTP 版本。
 *
 * <p>通过 {@link com.chua.common.support.network.client.ClientRequest#setVersion(HttpVersion)}
 * 或 {@link com.chua.common.support.network.client.HttpClientBuilder#version(HttpVersion)} 设置，
 * 各 {@link com.chua.common.support.network.client.spi.HttpClientExecutor} 实现会据此选择
 * 对应的协议版本与服务器建立连接。</p>
 *
 * <p><b>各执行器对版本的支持情况：</b></p>
 * <table border="1">
 *   <tr><th>版本</th><th>JDK</th><th>OkHttp</th><th>HttpClient5</th><th>说明</th></tr>
 *   <tr><td>HTTP/1.1</td><td>✓</td><td>✓</td><td>✓</td><td>所有执行器均支持</td></tr>
 *   <tr><td>HTTP/2</td><td>✓</td><td>✓</td><td>✓</td><td>所有执行器均支持</td></tr>
 *   <tr><td>HTTP/3</td><td>—</td><td>—</td><td>—</td><td>基于 QUIC/UDP，需额外依赖</td></tr>
 * </table>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 强制使用 HTTP/1.1
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .version(HttpVersion.HTTP_1_1)
 *     .path("/users")
 *     .get();
 *
 * // 通过 ClientRequest 设置
 * ClientRequest request = ClientRequest.of("http://example.com");
 * request.setVersion(HttpVersion.HTTP_2);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.common.support.network.client.ClientRequest
 * @see com.chua.common.support.network.client.HttpClientBuilder
 * @see com.chua.common.support.network.client.ClientSetting
 * @see com.chua.common.support.network.client.spi.HttpClientExecutor#supportedVersions()
 */
public enum HttpVersion {

    /**
     * HTTP/1.1 协议版本。
     *
     * <p>经典的 HTTP 协议版本，基于文本协议，支持持久连接（Keep-Alive）。
     * 所有 HTTP 库均支持此版本，兼容性最佳。</p>
     */
    HTTP_1_1(1, 1),

    /**
     * HTTP/2 协议版本。
     *
     * <p>基于二进制帧的多路复用协议，支持头部压缩（HPACK）、服务器推送等特性。
     * 在 HTTPS 连接中可获得更好的性能。需要 TLS 加密连接。</p>
     */
    HTTP_2(2, 0),

    /**
     * HTTP/3 协议版本。
     *
     * <p>基于 QUIC 传输协议（UDP）的新一代 HTTP 协议，具备以下优势：</p>
     * <ul>
     *   <li>消除 TCP 队头阻塞（Head-of-Line Blocking）</li>
     *   <li>更快的连接建立（0-RTT / 1-RTT）</li>
     *   <li>内置 TLS 1.3 加密</li>
     *   <li>连接迁移（Connection Migration），切换网络不断连</li>
     * </ul>
     *
     * <p><b>当前支持状态：</b>JDK HttpClient、OkHttp3、Apache HttpClient5 标准库
     * 均不原生支持 HTTP/3。如需使用，需引入额外依赖（如 Google Cronet、
     * Cloudflare quiche-java 或 Netty 的 netty-incubator-codec-http3）。</p>
     *
     * <p>当请求指定 HTTP/3 但执行器不支持时，执行器会自动降级到 HTTP/2。</p>
     */
    HTTP_3(3, 0);

    /** Major */
    private final int major;
    /** Minor */
    private final int minor;

    HttpVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    /**
     * 获取主版本号。
     *
     * @return 主版本号，如 HTTP/1.1 返回 1，HTTP/2 返回 2，HTTP/3 返回 3
     */
    public int getMajor() { return major; }

    /**
     * 获取次版本号。
     *
     * @return 次版本号，如 HTTP/1.1 返回 1，HTTP/2 返回 0
     */
    public int getMinor() { return minor; }

    /**
     * 判断此版本是否为 HTTP/3（基于 QUIC 传输）。
     *
     * @return HTTP/3 返回 true
     */
    public boolean isQuic() { return this == HTTP_3; }
}
