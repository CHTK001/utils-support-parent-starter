package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Memcached 应用层 处理器 — 拦截 Spymemcached 客户端关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code net.spy.memcached.MemcachedClient} — get / set / delete / add / replace / incr / decr / touch</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Memcached 客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemcachedHandler extends AbstractAppHandler {

    /**
     * memcached客户端 类内部名
     */
    private static final String MEMCACHED_CLIENT = "net/spy/memcached/MemcachedClient";

    /**
     * Memcached 方法集合
     */
    private static final String[] CLIENT_METHODS = {
            "get", "gets", "set", "add", "replace", "delete", "incr", "decr", "touch",
            "getAndTouch", "append", "prepend", "cas", "asyncGet", "asyncSet"
    };

    @Override
    /** 名称 */
    public String name() {
        return "memcached-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "memcached.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.MEMCACHED;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.MEMCACHED;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(MEMCACHED_CLIENT, CLIENT_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object address = findField(instance, "mux");
        String url = address != null ? String.valueOf(findField(address, "locator")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MEMCACHED)
                .software(Software.MEMCACHED)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "memcached")
                .port(parseUrlPort(url, Protocol.MEMCACHED.defaultPort()))
                .path("/")
                .build();
    }
}