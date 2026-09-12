package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Consul 应用层 处理器 — 拦截 Consul Java 客户端 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.ecwid.consul.v1.ConsulClient} — setKVValue / getKVValue / registerService / agentServiceRegister</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Consul 客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ConsulHandler extends AbstractAppHandler {

    /**
      * consul客户端 类内部名
     */
    private static final String CONSUL_CLIENT = "com/ecwid/consul/v1/ConsulClient";

    /**
     * Consul 方法集合（KV + 服务注册）
     */
    private static final String[] CLIENT_METHODS = {
            "setKVValue", "getKVValue", "getKVValues", "deleteKVValue",
            "registerService", "registerServiceWithId", "agentServiceRegister",
            "getCatalogServices", "agentServiceDeregister"
    };

    @Override
    /** 名称 */
    public String name() {
        return "consul-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "consul.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.CONSUL;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.CONSUL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(CONSUL_CLIENT, CLIENT_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        String host = "consul";
        int port = Protocol.CONSUL.defaultPort();
        if (instance != null) {
            Object client = findField(instance, "client");
            if (client != null) {
                Object hosts = findField(client, "host");
                if (hosts != null) {
                    String h = String.valueOf(hosts);
                    if (h.contains(":")) {
                        String[] hp = h.split(":");
                        host = hp[0];
                        try {
                            port = Integer.parseInt(hp[1]);
                        } catch (NumberFormatException e) {
                            port = Protocol.CONSUL.defaultPort();
                        }
                    } else if (!h.isEmpty()) {
                        host = h;
                    }
                }
                Object p = findField(client, "port");
                if (p instanceof Number) {
                    port = ((Number) p).intValue();
                }
            }
        }
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.CONSUL)
                .software(Software.CONSUL)
                .host(host)
                .port(port)
                .path("/")
                .build();
    }
}