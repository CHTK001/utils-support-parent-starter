package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Nacos 应用层 处理器 — 拦截 Nacos 注册中心客户端关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.alibaba.nacos.client.naming.NacosNamingService} — registerInstance / deregisterInstance / getAllInstances</li>
 *   <li>{@code com.alibaba.nacos.client.config.NacosConfigService} — getConfig / publishConfig</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Nacos 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NacosHandler extends AbstractAppHandler {

    /**
      * nacos名称服务 类内部名
     */
    private static final String NAMING_SERVICE = "com/alibaba/nacos/client/naming/NacosNamingService";

    /**
      * nacos配置服务 类内部名
     */
    private static final String CONFIG_SERVICE = "com/alibaba/nacos/client/config/NacosConfigService";

    /**
     * 注册中心方法集合
     */
    private static final String[] NAMING_METHODS = {
            "registerInstance", "deregisterInstance", "getAllInstances", "selectInstances",
            "getServerStatus", "registerService", "deregisterService", "getSubscribeServices"
    };

    /**
     * 配置中心方法集合
     */
    private static final String[] CONFIG_METHODS = {"getConfig", "publishConfig", "removeConfig"};

    @Override
    /** 名称 */
    public String name() {
        return "nacos-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "nacos.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.NACOS;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.NACOS;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(NAMING_SERVICE, NAMING_METHODS);
        registerAll(CONFIG_SERVICE, CONFIG_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        String host = "nacos";
        int port = Protocol.NACOS.defaultPort();
        if (instance != null) {
            Object serverProxy = findField(instance, "serverProxy");
            if (serverProxy != null) {
                Object nacosClient = findField(serverProxy, "nacosClient");
                if (nacosClient != null) {
                    Object clientConfig = findField(nacosClient, "properties");
                    if (clientConfig != null) {
                        Object addr = findField(clientConfig, "serverAddr");
                        if (addr != null) {
                            String serverAddr = String.valueOf(addr);
                            if (serverAddr.contains(":")) {
                                String[] hp = serverAddr.split(":");
                                host = hp[0];
                                try {
                                    port = Integer.parseInt(hp[1]);
                                } catch (NumberFormatException e) {
                                    port = Protocol.NACOS.defaultPort();
                                }
                            } else if (!serverAddr.isEmpty()) {
                                host = serverAddr;
                            }
                        }
                    }
                }
            }
        }
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.NACOS)
                .software(Software.NACOS)
                .host(host)
                .port(port)
                .path("/")
                .build();
    }
}