package com.chua.common.support.network.server.dht;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.Event;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.discovery.ServiceDiscoveryListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 DHT 的服务发现实现。
 * <p>
 * 使用 Kademlia DHT 协议实现去中心化的服务注册与发现。
 * 服务注册时以 "{@code /dht/discovery/路径}" 为键存储到 DHT 网络，
 * 发现时通过 DHT 查找值获取服务信息。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DhtServiceDiscovery extends AbstractServiceDiscovery {

    /**
     * DHT 协议引擎
     */
    private DhtProtocol protocol;

    /**
     * 本地已注册的服务映射（路径 -> Discovery 集合）
     */
    private final Map<String, Set<Discovery>> registeredServices = new ConcurrentHashMap<>();

    /**
     * 服务发现监听器列表
     */
    private final List<ServiceDiscoveryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 是否正在运行
     */
    private volatile boolean running;

    /**
     * DHT 服务发现键的前缀
     */
    private static final String DHT_PREFIX = "/dht/discovery/";

    /**
     * 构造 DHT 服务发现。
     *
     * @param discoveryOption 服务发现配置
     */
    public DhtServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    /**
     * 构造 DHT 服务发现（指定集群名称）。
     *
     * @param discoveryOption 服务发现配置
     * @param clusterName     集群名称
     */
    public DhtServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    /**
     * 设置 DHT 协议引擎。
     *
     * @param protocol DhtProtocol 实例
     */
    public void setProtocol(DhtProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public void start() {
        if (protocol == null) {
            throw new IllegalStateException("DhtProtocol not set");
        }
        running = true;
        log.info("DHT ServiceDiscovery started. NodeId={}", protocol.selfId());
    }

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String fullPath = addClusterPrefix(DHT_PREFIX + path);
        registeredServices.computeIfAbsent(fullPath, k -> ConcurrentHashMap.newKeySet()).add(discovery);
        String key = fullPath;
        String value = Json.toJson(discovery);
        protocol.store(key, value);
        log.debug("Registered service: {} -> {}", fullPath, discovery.toFullString());
        notifyListeners(fullPath, discovery, Event.ADD);
        return this;
    }

    @Override
    public Discovery getService(String path, String balance, String protocolType) {
        Set<Discovery> all = getServiceAll(path);
        if (all.isEmpty()) {
            return null;
        }
        return selectByBalance(all, balance);
    }

    @Override
    public Set<Discovery> getServiceAll(String path) {
        String fullPath = addClusterPrefix(DHT_PREFIX + path);
        Set<Discovery> cached = registeredServices.get(fullPath);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        Set<Discovery> result = new HashSet<>();
        String value = protocol != null ? protocol.findValue(fullPath) : null;
        if (value != null) {
            try {
                Discovery d = Json.fromJson(value, Discovery.class);
                if (d != null) {
                    result.add(d);
                }
            } catch (Exception e) {
                log.debug("Failed to parse Discovery from DHT value: {}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        if (listener != null) {
            listeners.add(listener);
            log.debug("Subscribed to service: {}", serviceName);
        }
    }

    /**
     * 添加服务发现监听器。
     *
     * @param listener 监听器
     */
    public void addListener(ServiceDiscoveryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 通知所有监听器服务变更事件。
     *
     * @param path       服务路径
     * @param discovery  服务发现信息
     * @param event      事件类型（ADD/REMOVE/UPDATE）
     */
    private void notifyListeners(String path, Discovery discovery, Event event) {
        for (ServiceDiscoveryListener l : listeners) {
            try {
                l.listen(path, discovery, event);
            } catch (Exception e) {
                log.warn("Listener error: {}", e.getMessage());
            }
        }
    }

    /**
     * 根据负载均衡策略选择一个服务。
     *
     * @param services 服务集合
     * @param balance  负载均衡策略（"round" 或 "weight"）
     * @return 选中的服务
     */
    private Discovery selectByBalance(Set<Discovery> services, String balance) {
        if (services.isEmpty()) {
            return null;
        }
        List<Discovery> list = new ArrayList<>(services);
        if ("round".equals(balance) || "weight".equals(balance)) {
            int idx = (int) (System.currentTimeMillis() % list.size());
            return list.get(idx);
        }
        return list.iterator().next();
    }

    @Override
    public void close() {
        running = false;
        registeredServices.clear();
        listeners.clear();
        if (protocol != null) {
            protocol.close();
        }
    }

    @Override
    protected Set<Discovery> get(String path) {
        return getServiceAll(path.replace(DHT_PREFIX, ""));
    }
}
