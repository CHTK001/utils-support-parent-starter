package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * HTTP 客户端应用层 处理器 — 拦截 OkHttp / Apache HTTP客户端 调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code okhttp3.RealCall} — execute（OkHttp 同步请求入口）</li>
 *   <li>{@code org.apache.http.impl.client.InternalHttpClient} — doExecute（HttpClient 请求入口）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HttpClientHandler extends AbstractAppHandler {

    /**
     * OkHttp realcall 类内部名
     */
    private static final String OKHTTP_REAL_CALL = "okhttp3/RealCall";

    /**
     * OkHttp enqueue 类内部名
     */
    private static final String OKHTTP_ASYNC_CALL = "okhttp3/RealCall$AsyncCall";

    /**
     * Apache HTTP客户端 内部http客户端 类内部名
     */
    private static final String APACHE_HTTP_CLIENT = "org/apache/http/impl/client/InternalHttpClient";

    /**
     * OkHttp 方法集合
     */
    private static final String[] OKHTTP_METHODS = {"execute"};

    /**
     * OkHttp 异步方法集合
     */
    private static final String[] OKHTTP_ASYNC_METHODS = {"executeOn"};

    /**
     * HTTP客户端 方法集合
     */
    private static final String[] APACHE_METHODS = {"doExecute"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "httpclient-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "httpclient.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.OKHTTP;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /**
     * softwareforentry
    */
    protected Software softwareForEntry(InterceptContext ctx) {
        String cn = ctx.getClassName();
        if (cn != null && cn.startsWith("okhttp")) {
            return Software.OKHTTP;
        }
        if (cn != null && cn.startsWith("org/apache/http")) {
            return Software.APACHE_HTTPCLIENT;
        }
        return Software.OKHTTP;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(OKHTTP_REAL_CALL, OKHTTP_METHODS);
        registerAll(OKHTTP_ASYNC_CALL, OKHTTP_ASYNC_METHODS);
        registerAll(APACHE_HTTP_CLIENT, APACHE_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object request = findField(instance, "originalRequest");
        if (request == null) {
            request = findField(instance, "request");
        }
        String url = request != null ? String.valueOf(findField(request, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(softwareForEntry(ctx))
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "http")
                .port(parseUrlPort(url, 80))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}