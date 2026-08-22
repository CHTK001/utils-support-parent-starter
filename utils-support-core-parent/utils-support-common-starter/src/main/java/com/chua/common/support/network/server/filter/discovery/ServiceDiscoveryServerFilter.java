package com.chua.common.support.network.server.filter.discovery;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务发现过滤器，从 ServiceDiscovery 获取后端地址并存入请求属性。
 *
 * <p>在反向代理场景中，本过滤器负责：</p>
 * <ol>
 *   <li>根据请求路径从 ServiceDiscovery 查找后端服务实例</li>
 *   <li>将查找到的 {@link Discovery} 存入 {@link ServerAttribute#BACKEND_DISCOVERY}</li>
 *   <li>同时填充 {@link ServerAttribute#BACKEND_ADDRESS} 等便捷属性</li>
 * </ol>
 *
 * <p>下游的 {@link ReverseProxyServerFilter} 从属性中读取后端地址进行转发。</p>
 *
 * <h2>配置方式</h2>
 * <pre>{@code
 * // 通过 SPI 名称指定 ServiceDiscovery 实现
 * ServiceDiscoveryServerFilter filter = new ServiceDiscoveryServerFilter("zookeeper");
 * filter.setDiscoveryOption(option);
 * filter.addRoute("/api/**", "/service/api");
 * }</pre>
 *
 * <h2>路由匹配规则</h2>
 * <ul>
 *   <li>{@code /api/**} — 匹配 /api/ 下所有路径，服务路径为 /service/api</li>
 *   <li>{@code /api/*} — 匹配 /api/ 下一级路径</li>
 *   <li>{@code /api/users} — 精确匹配</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/18
 * @see ReverseProxyServerFilter
 */
@Slf4j
public class ServiceDiscoveryServerFilter implements ServerFilter {

    /**
     * 路径前缀到服务路径的映射。
     * Key: 请求路径前缀 (如 /api/**)
     * Value: 注册中心中的服务路径 (如 /service/api)
     */
    private final Map<String, String> routeServiceMap = new ConcurrentHashMap<>();

    /**
     * ServiceDiscovery SPI 名称
     */
    private final String discoveryName;

    /**
     * 负载均衡策略，默认 weight
     */
    private String balance = "weight";

    /**
     * 服务协议过滤，默认 null（不过滤）
     */
    private String protocol;

    /**
     * 业务分组标识(scatterId):同一服务路径下按业务隔离,
     * 仅路由到相同 scatterId 的节点;为 null 时不过滤
     */
    private String scatterId;

    /**
     * 排除的节点 serverId(通常为本节点):路由时避开,防止请求被转发回自身代理
     */
    private String excludeServerId;

    /**
     * ServiceDiscovery 实例（延迟初始化）
     */
    private volatile ServiceDiscovery serviceDiscovery;

    /**
     * 创建 ServiceDiscoveryServerFilter 实例
     * @param discoveryName discoveryName
     */
    public ServiceDiscoveryServerFilter(String discoveryName) {
        this.discoveryName = discoveryName;
    }

    /**
     * 创建 ServiceDiscoveryServerFilter 实例
     * @param serviceDiscovery serviceDiscovery
     */
    public ServiceDiscoveryServerFilter(ServiceDiscovery serviceDiscovery) {
        this.discoveryName = null;
        this.serviceDiscovery = serviceDiscovery;
    }

    /**
     * 添加路由映射。
     *
     * @param pathPrefix   请求路径前缀
     * @param servicePath  注册中心中的服务路径
     */
    public void addRoute(String pathPrefix, String servicePath) {
        routeServiceMap.put(pathPrefix, servicePath);
    }

    /**
     * 批量设置路由。
     *
     * @param routes 路径前缀到服务路径的映射
     */
    public void setRoutes(Map<String, String> routes) {
        if (routes != null) {
            routeServiceMap.putAll(routes);
        }
    }

    /**
     * 设置负载均衡策略。
     *
     * @param balance 策略名称 (weight/random/roundrobin)
     */
    public void setBalance(String balance) {
        this.balance = balance;
    }

    /**
     * 设置服务协议过滤。
     *
     * @param protocol 协议类型 (http/tcp)
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 设置业务分组标识(scatterId)。
     *
     * @param scatterId 业务分组标识,同一服务路径下仅路由到相同分组的节点
     */
    public void setScatterId(String scatterId) {
        this.scatterId = scatterId;
    }

    /**
     * 设置排除的节点 serverId(通常为本节点)。
     *
     * @param excludeServerId 路由时避开的节点 serverId
     */
    public void setExcludeServerId(String excludeServerId) {
        this.excludeServerId = excludeServerId;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MAX_VALUE - 300;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        if (serviceDiscovery == null && discoveryName != null) {
            serviceDiscovery = ServiceProvider.of(ServiceDiscovery.class).getExtension(discoveryName);
            if (serviceDiscovery != null) {
                serviceDiscovery.start();
                log.info("ServiceDiscovery 初始化完成: {}", discoveryName);
            } else {
                log.warn("ServiceDiscovery 未找到: {}", discoveryName);
            }
        }
    }

    @Override
    /** 销毁 */
    public void destroy() {
        if (serviceDiscovery != null) {
            try {
                serviceDiscovery.close();
            } catch (Exception e) {
                log.warn("ServiceDiscovery 关闭异常: {}", e.getMessage());
            }
        }
    }

    @Override
    /**
     * Do过滤
     * @param request request
     * @param response response
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null || serviceDiscovery == null) {
            chain.doFilter(request, response);
            return;
        }

        String servicePath = matchServicePath(path);
        if (servicePath == null) {
            chain.doFilter(request, response);
            return;
        }

        Discovery discovery = null;
        // 排除自身(通常为本节点):避免请求被转发回自身代理造成死循环/404
        // 注意：必须每轮新建请求上下文，避免 LoadBalance 内部状态（如 weight 衰减）跨请求耦合
        for (int attempt = 0; attempt < 5; attempt++) {
            Discovery d = serviceDiscovery.getService(servicePath, scatterId, balance, protocol);
            if (d == null) {
                break;
            }
            if (excludeServerId == null || excludeServerId.isBlank()
                    || !d.getServerId().equals(excludeServerId)) {
                discovery = d;
                break;
            }
            // 本节点是唯一可用节点：构造新对象绕过 SPI 实例状态复用，确保后续操作基于 fresh 副本
            discovery = Discovery.builder()
                    .serverId(d.getServerId()).host(d.getHost()).port(d.getPort())
                    .scatterId(d.getScatterId()).protocol(d.getProtocol())
                    .weight(d.getWeight()).build();
            break;
        }
        if (discovery == null) {
            log.warn("服务未找到: {} (balance={}, protocol={})", servicePath, balance, protocol);
            chain.doFilter(request, response);
            return;
        }

        // 存入 Discovery 对象
        ServerAttribute.setBackendDiscovery(request, discovery);

        // 填充便捷属性
        ServerAttribute.setBackendAddress(request, discovery.getHost() + ":" + discovery.getPort());
        ServerAttribute.setBackendScheme(request, discovery.getProtocol());
        ServerAttribute.setBackendHost(request, discovery.getHost());
        ServerAttribute.setBackendPort(request, discovery.getPort());

        // 构建完整后端 URI
        String backendUri = discovery.getProtocol() + "://" + discovery.getHost() + ":" + discovery.getPort();
        ServerAttribute.setBackendUri(request, backendUri);

        log.debug("服务发现: {} -> {}:{} ({})", path, discovery.getHost(),
                discovery.getPort(), discovery.getProtocol());

        chain.doFilter(request, response);
    }

    /**
     * 匹配请求路径对应的服务路径。
     *
     * @param path 请求路径
     * @return 服务路径，无匹配返回 null
     */
    private String matchServicePath(String path) {
        for (Map.Entry<String, String> entry : routeServiceMap.entrySet()) {
            String prefix = entry.getKey();
            if (prefix.endsWith("/**")) {
                String base = prefix.substring(0, prefix.length() - 3);
                if (path.startsWith(base)) {
                    return entry.getValue();
                }
            } else if (prefix.endsWith("/*")) {
                String base = prefix.substring(0, prefix.length() - 2);
                if (path.startsWith(base) && path.indexOf('/', base.length()) < 0) {
                    return entry.getValue();
                }
            } else {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}


