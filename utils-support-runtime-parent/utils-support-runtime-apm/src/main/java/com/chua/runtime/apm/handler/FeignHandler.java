package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * OpenFeign 应用层 Handler — 拦截 Spring Cloud OpenFeign 声明式 HTTP 调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code feign.SynchronousMethodHandler} — execute（同步请求核心入口）</li>
 *   <li>{@code feign.AsyncFeign$AsyncCallHandler}（异步请求核心入口）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Feign 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FeignHandler extends AbstractAppHandler {

    /**
     * SynchronousMethodHandler 类内部名
     */
    private static final String SYNC_METHOD_HANDLER = "feign/SynchronousMethodHandler";

    /**
     * Feign 方法集合（同步执行入口）
     */
    private static final String[] SYNC_METHODS = {"execute"};

    @Override
    public String name() {
        return "feign-handler";
    }

    @Override
    protected String enabledKey() {
        return "feign.enabled";
    }

    @Override
    protected Software software() {
        return Software.FEIGN;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(SYNC_METHOD_HANDLER, SYNC_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object target = instance != null ? findField(instance, "target") : null;
        String url = target != null ? String.valueOf(findField(target, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.FEIGN)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "http")
                .port(parseUrlPort(url, 80))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}