package com.chua.nacos.support.discovery;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.chua.common.support.network.discovery.*;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("nacos")
public class NacosServiceDiscovery extends AbstractServiceDiscovery {

    /** Naming服务 */
    private NamingService namingService;

    public NacosServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    public NacosServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    @Override
    protected boolean needsPathPrefixIsolation() {
        return false;
    }

    @Override
    protected Set<Discovery> get(String path) {
        if (path == null || path.trim().isEmpty() || "/".equals(path)) {
            return Collections.emptySet();
        }
        List<Discovery> cached = localCache.get(path);
        if (CollectionUtils.size(cached) > 0) {
            return new HashSet<>(cached);
        }
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            List<Instance> instances = clusterName != null && !clusterName.isEmpty()
                    ? namingService.getAllInstances(clusterName, name)
                    : namingService.getAllInstances(name);
            if (instances == null || instances.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Discovery> result = instances.stream().map(it -> Discovery.builder()
                    .protocol(getMeta(it, "protocol"))
                    .uriSpec(getMeta(it, "uriSpec"))
                    .port(it.getPort())
                    .weight((double) it.getWeight())
                    .serverId(getMeta(it, "serverId"))
                    .host(it.getIp())
                    .metadata(it.getMetadata())
                    .build()).collect(Collectors.toSet());
            replaceCache(path, result);
            return result;
        } catch (NacosException e) {
            log.error("Nacos query failed for path {}", path, e);
            return Collections.emptySet();
        }
    }

    private static String getMeta(Instance it, String key) {
        return it.getMetadata() != null ? it.getMetadata().get(key) : null;
    }

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        discovery.setUriSpec(path);
        Instance instance = new Instance();
        instance.setIp(discovery.getHost());
        instance.setPort(discovery.getPort());
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.isEmpty()) {
            name = discovery.getId();
        }
        instance.setServiceName(name);
        instance.setInstanceId(discovery.getId());
        if (discovery.getWeight() > 0) {
            instance.setWeight(discovery.getWeight());
        }
        instance.setHealthy(true);
        Map<String, String> meta = new HashMap<>(discovery.getMetadata());
        meta.put("protocol", discovery.getProtocol());
        meta.put("serverId", discovery.getServerId());
        meta.put("uriSpec", path);
        instance.setMetadata(meta);
        try {
            namingService.registerInstance(name, instance);
            addToCache(path, discovery);
            incrementServiceVersion();
            log.info("Registered: {} at {}:{}", name, discovery.getHost(), discovery.getPort());
        } catch (NacosException e) {
            log.error("Register failed", e);
        }
        return this;
    }

    @Override
    protected void doUnregister(String path, Discovery discovery) {
        try {
            String name = path.startsWith("/") ? path.substring(1) : path;
            namingService.deregisterInstance(name, discovery.getHost(), discovery.getPort());
        } catch (NacosException e) {
            log.error("Deregister failed", e);
        }
    }

    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        doUnregister(path, oldDiscovery);
        registerService(path, newDiscovery);
    }

    @Override
    public void start() {
        try {
            if (clusterName != null && !clusterName.isEmpty()) {
                var props = new java.util.Properties();
                props.setProperty("serverAddr", discoveryOption.getAddress());
                props.setProperty("namespace", clusterName);
                namingService = NacosFactory.createNamingService(props);
            } else {
                namingService = NacosFactory.createNamingService(discoveryOption.getAddress());
            }
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        try {
            namingService.subscribe(serviceName, new EventListener() {
                @Override
                public void onEvent(com.alibaba.nacos.api.naming.listener.Event event) {
                    if (event instanceof NamingEvent ne) {
                        String name = ne.getServiceName();
                        List<Instance> instances = ne.getInstances();
                        if (instances == null) {
                            return;
                        }
                        for (Instance inst : instances) {
                            Discovery d = Discovery.builder()
                                    .host(inst.getIp())
                                    .port(inst.getPort())
                                    .weight((double) inst.getWeight())
                                    .build();
                            listener.listen(name, d, inst.isHealthy() ? Event.ONLINE : Event.OFFLINE);
                        }
                    }
                }
            });
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        clearCache();
        if (namingService != null) {
            try { namingService.shutDown(); } catch (NacosException e) { log.error("", e); }
        }
    }
}
