package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Eureka 应用层 处理器 — 拦截 Netflix Eureka 客户端关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.netflix.discovery.DiscoveryClient} — register / fetchRegistry / getApplications</li>
 *   <li>{@code com.netflix.appinfo.InstanceInfo$Builder}（实例注册信息构造）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Eureka 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EurekaHandler extends AbstractAppHandler {

    /**
     * discovery客户端 类内部名
     */
    private static final String DISCOVERY_CLIENT = "com/netflix/discovery/DiscoveryClient";

    /**
     * eureka客户端 接口类内部名
     */
    private static final String EUREKA_CLIENT = "com/netflix/discovery/shared/DiscoveryClient";

    /**
     * discovery客户端 方法集合
     */
    private static final String[] DISCOVERY_METHODS = {
            "register", "registerHealthCheck", "fetchRegistry", "getApplications",
            "getApplication", "getInstancesById", "getDiscoveryClientOptionalArg", "shutdown"
    };

    /**
     * 名称
    */
    @Override
    public String name() {
        return "eureka-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "eureka.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.EUREKA;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.EUREKA;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(DISCOVERY_CLIENT, DISCOVERY_METHODS);
        registerAll(EUREKA_CLIENT, DISCOVERY_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object config = instance != null ? findField(instance, "eurekaClientConfig") : null;
        String host = "eureka";
        int port = Protocol.EUREKA.defaultPort();
        if (config != null) {
            Object defaultZone = findField(config, "region");
            if (defaultZone != null) {
                String zone = String.valueOf(defaultZone);
                if (zone.contains("://")) {
                    String url = zone;
                    String parsedHost = parseUrlHost(url);
                    if (parsedHost != null) {
                        host = parsedHost;
                    }
                    int parsedPort = parseUrlPort(url, port);
                    if (parsedPort != port) {
                        port = parsedPort;
                    }
                }
            }
        }
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.EUREKA)
                .software(Software.EUREKA)
                .host(host)
                .port(port)
                .path("/")
                .build();
    }
}