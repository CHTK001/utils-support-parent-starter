package com.chua.common.support.network.server.filter.discovery;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ReactiveFilterChain;
import com.chua.common.support.network.server.filter.ReactiveServerFilter;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 服务发现过滤器，从 ServiceDiscovery 获取后端地址并存入请求属性。
 *
 * @author CH
 * @since 2026/07/18
 * @see ReverseProxyServerFilter
 */
@Slf4j
public class ServiceDiscoveryServerFilter implements ServerFilter, ReactiveServerFilter {

    private final Map<String, String> routeServiceMap = new ConcurrentHashMap<>();
    private final String discoveryName;
    /**
     * balance
    */
    private String balance = "weight";
    /**
     * protocol
    */
    private String protocol;
    /**
     * scatterID
    */
    private String scatterId;
    /**
     * exclude服务端ID
    */
    private String excludeServerId;
    private volatile ServiceDiscovery serviceDiscovery;

    /**
     * 构造方法，创建 服务Discovery服务端过滤 实例。
     *
     * @param discoveryName discovery名称，不允许为 null
     */
    public ServiceDiscoveryServerFilter(String discoveryName) {
        this.discoveryName = discoveryName;
    }

    /**
     * 构造方法，创建 服务Discovery服务端过滤 实例。
     *
     * @param serviceDiscovery 服务Discovery，不允许为 null
     */
    public ServiceDiscoveryServerFilter(ServiceDiscovery serviceDiscovery) {
        this.discoveryName = null;
        this.serviceDiscovery = serviceDiscovery;
    }

    /**
     * 添加Route。
     *
     * @param pathPrefix 路径前缀，不允许为 null
     * @param servicePath 服务路径，不允许为 null
     */
    public void addRoute(String pathPrefix, String servicePath) {
        routeServiceMap.put(pathPrefix, servicePath);
    }

    /**
     * 设置Routes。
     *
     * @param routes 方法入参 routes
     */
    public void setRoutes(Map<String, String> routes) {
        if (routes != null) {
            routeServiceMap.putAll(routes);
        }
    }

    /**
     * 设置Balance。
     *
     * @param balance 方法入参 balance
     */
    public void setBalance(String balance) {
        this.balance = balance;
    }

    /**
     * 设置Protocol。
     *
     * @param protocol 方法入参 protocol
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 设置ScatterID。
     *
     * @param scatterId scatterID，不允许为 null
     */
    public void setScatterId(String scatterId) {
        this.scatterId = scatterId;
    }

    /**
     * 设置Exclude服务端ID。
     *
     * @param excludeServerId exclude服务端ID，不允许为 null
     */
    public void setExcludeServerId(String excludeServerId) {
        this.excludeServerId = excludeServerId;
    }

    /**
     * 获取发现源名称
     *
     * @return 发现源名称（按名称创建时为 SPI 名，直接传入 ServiceDiscovery 时为 null）
     */
    public String getDiscoveryName() {
        return discoveryName;
    }

    /**
     * 获取当前持有的 ServiceDiscovery 实例（可能尚未 init 而为 null）
     *
     * @return ServiceDiscovery 实例或 null
     */
    public ServiceDiscovery getServiceDiscovery() {
        return serviceDiscovery;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 300;
    }

    @Override
    public String supportPath() {
        return null;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
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
    public void doFilter(ServerRequest request, ServerResponse response,
                          ServerFilterChain chain) throws Exception {
        executeDiscovery(request, response, (req, res) -> {
            try {
                chain.doFilter(req, res);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        try {
            executeDiscovery(request, response, (req, res) -> { /* no-op */ });
        } catch (Exception e) {
            log.warn("服务发现异常: {}", e.getMessage());
        }
        return chain.doFilter(request, response);
    }

    private void executeDiscovery(ServerRequest request, ServerResponse response,
                                  BiConsumer<ServerRequest, ServerResponse> next) {
        String path = request.getPath();
        if (path == null || serviceDiscovery == null) {
            next.accept(request, response);
            return;
        }

        String servicePath = matchServicePath(path);
        if (servicePath == null) {
            next.accept(request, response);
            return;
        }

        Discovery discovery = null;
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                Discovery d = serviceDiscovery.getService(servicePath, scatterId, balance, protocol);
                if (d == null) {
                    break;
                }
                if (!isExcluded(d.getServerId())) {
                    discovery = d;
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("服务发现异常: {}", e.getMessage());
            next.accept(request, response);
            return;
        }

        if (discovery == null) {
            log.debug("服务未找到: {} (balance={}, protocol={}, scatterId={})",
                    servicePath, balance, protocol, scatterId);
            next.accept(request, response);
            return;
        }

        ServerAttribute.setBackendDiscovery(request, discovery);
        ServerAttribute.setBackendAddress(request, discovery.getHost() + ":" + discovery.getPort());
        ServerAttribute.setBackendScheme(request, discovery.getProtocol());
        ServerAttribute.setBackendHost(request, discovery.getHost());
        ServerAttribute.setBackendPort(request, discovery.getPort());
        String backendUri = discovery.getProtocol() + "://" + discovery.getHost() + ":" + discovery.getPort();
        ServerAttribute.setBackendUri(request, backendUri);

        log.debug("服务发现: {} -> {}:{} ({})", path, discovery.getHost(),
                discovery.getPort(), discovery.getProtocol());

        next.accept(request, response);
    }

    /**
     * 判断是否排除（防止请求被转发回自身代理造成死循环/404）。
     * 同时匹配完整 serverId 和去除协议后缀后的基础 nodeId，
     * 以兼容 scatter 内部 serverId 格式（可能带 -http/-tcp 后缀或不带）。
     * @param serverId 服务端ID，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private boolean isExcluded(String serverId) {
        if (serverId == null) {
            return false;
        }
        if (excludeServerId == null || excludeServerId.isBlank()) {
            return false;
        }
        if (serverId.equals(excludeServerId)) {
            return true;
        }
        for (String suffix : new String[]{"-http", "-tcp", "-ws"}) {
            if (serverId.equals(excludeServerId + suffix)) {
                return true;
            }
        }
        for (String suffix : new String[]{"-http", "-tcp", "-ws"}) {
            if (excludeServerId.equals(serverId + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * match服务路径。
     *
     * @param path 路径，不允许为 null
     * @return 结果字符串
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
