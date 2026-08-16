package com.chua.zookeeper.support.discovery;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.Event;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.discovery.ServiceDiscoveryListener;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("zookeeper")
public class ZookeeperServiceDiscovery extends AbstractServiceDiscovery {

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Map<String, List<ServiceDiscoveryListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Set<Discovery>> preDeleteState = new ConcurrentHashMap<>();
    private CuratorFramework client;
    private String root;

    public ZookeeperServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    public ZookeeperServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        discovery.setUriSpec(prefixed);
        String zkPath = root + prefixed + "/" + discovery.getHost() + ":" + discovery.getPort();
        try {
            byte[] data = Json.toJsonByte(discovery);
            if (client.checkExists().forPath(zkPath) == null) {
                client.create()
                        .creatingParentContainersIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(zkPath, data);
            }
            addToCache(prefixed, discovery);
            incrementServiceVersion();
        } catch (Exception e) {
            throw new RuntimeException("Zookeeper register failed", e);
        }
        return this;
    }

    @Override
    protected void doUnregister(String path, Discovery discovery) {
        String host = discovery.getHost();
        int port = discovery.getPort();
        // 如果 host/port 为空，从 ZK 直接扫描获取
        if (host == null || port <= 0) {
            String zkPath = root + path;
            try {
                List<String> children = client.getChildren().forPath(zkPath);
                for (String child : children) {
                    byte[] data = client.getData().forPath(zkPath + "/" + child);
                    if (data != null && data.length > 0) {
                        String json = new String(data, StandardCharsets.UTF_8);
                        Map<String, Object> map = Json.fromJson(json,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        String serverId = (String) map.get("serverId");
                        if (serverId != null && serverId.equals(discovery.getServerId())) {
                            host = (String) map.get("host");
                            port = ((Number) map.get("port")).intValue();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to scan ZK for serverId {}", discovery.getServerId(), e);
            }
        }
        if (host == null || port <= 0) {
            return;
        }
        String zkPath = root + path + "/" + host + ":" + port;
        // 捕获删除前的 ZK 状态
        List<Discovery> preDelete = fetchAllInstances(root + path);
        if (preDelete != null && !preDelete.isEmpty()) {
            preDeleteState.put(path, new HashSet<>(preDelete));
        }
        try {
            if (client.checkExists().forPath(zkPath) != null) {
                client.delete().forPath(zkPath);
            }
        } catch (Exception e) {
            log.warn("Zookeeper unregister failed for {}", zkPath, e);
        }
    }

    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        String zkPath = root + path + "/" + newDiscovery.getHost() + ":" + newDiscovery.getPort();
        try {
            client.setData().forPath(zkPath, Json.toJsonByte(newDiscovery));
        } catch (Exception e) {
            log.warn("Zookeeper update failed for {}", zkPath, e);
        }
    }

    @Override
    public void start() {
        if (started.get()) {
            return;
        }
        started.set(true);
        this.root = StringUtils.isNullOrEmpty(discoveryOption.getRoot())
                ? "/discovery" : StringUtils.startWithAppend(discoveryOption.getRoot(), "/");
        CountDownLatch latch = new CountDownLatch(1);
        client = CuratorFrameworkFactory.builder()
                .connectString(discoveryOption.getAddress())
                .retryPolicy(new RetryNTimes(3, 1000))
                .build();
        client.getConnectionStateListenable().addListener((c, s) -> {
            if (s.isConnected()) {
                latch.countDown();
            }
        });
        client.start();
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                close();
                throw new RuntimeException("Zookeeper connect timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        watchChildren(root);
    }

    private void watchChildren(String zkPath) {
        try {
            List<String> children = client.getChildren()
                    .usingWatcher(new Watcher() {
                        @Override
                        public void process(WatchedEvent event) {
                            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                                refreshPath(zkPath);
                            }
                            watchChildren(zkPath);
                        }
                    })
                    .forPath(zkPath);
            for (String child : children) {
                watchChildren(zkPath + "/" + child);
            }
        } catch (Exception e) {
            log.warn("Watch failed for {}", zkPath, e);
        }
    }

    private void refreshPath(String zkPath) {
        String discoveryPath = zkPath.substring(root.length());
        if (discoveryPath.isEmpty()) {
            return;
        }
        try {
            Set<Discovery> oldSet = preDeleteState.get(discoveryPath);
            if (oldSet == null) {
                oldSet = getServiceAll(discoveryPath);
            }
            List<Discovery> list = fetchAllInstances(zkPath);
            if (list == null) {
                return;
            }
            preDeleteState.remove(discoveryPath);
            replaceCache(discoveryPath, list);
            notifyListeners(discoveryPath, oldSet, list);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("STOPPED")) {
                return;
            }
            log.warn("Refresh failed for {}", zkPath, e);
        }
    }

    private List<Discovery> fetchAllInstances(String zkPath) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                List<String> children = client.getChildren().forPath(zkPath);
                if (children.isEmpty()) {
                    return new LinkedList<>();
                }
                // 验证第一个子节点是否有数据（区分实例路径与容器路径）
                byte[] firstData = client.getData().forPath(zkPath + "/" + children.get(0));
                if (firstData == null || firstData.length == 0) {
                    return new LinkedList<>();
                }
                List<Discovery> list = new LinkedList<>();
                for (String child : children) {
                    byte[] data = client.getData().forPath(zkPath + "/" + child);
                    if (data == null || data.length == 0) {
                        continue;
                    }
                    try {
                        String json = new String(data, StandardCharsets.UTF_8);
                        Map<String, Object> map = Json.fromJson(json,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        list.add(Discovery.builder()
                                .serverId((String) map.get("serverId"))
                                .host((String) map.get("host"))
                                .port(((Number) map.get("port")).intValue())
                                .weight(((Number) map.get("weight")).doubleValue())
                                .protocol((String) map.get("protocol"))
                                .uriSpec((String) map.get("uriSpec"))
                                .env((String) map.get("env"))
                                .build());
                    } catch (Exception e) {
                        log.debug("Failed to parse ZK node {}", child, e);
                    }
                }
                return list;
            } catch (ConcurrentModificationException e) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("STOPPED")) {
                    return null;
                }
                if (e.getMessage() != null && e.getMessage().contains("No such file")) {
                    return new LinkedList<>();
                }
                return null;
            }
        }
        return null;
    }

    private void notifyListeners(String path, Set<Discovery> oldSet, List<Discovery> newList) {
        List<ServiceDiscoveryListener> pathListeners = listeners.get(path);
        if (pathListeners == null || pathListeners.isEmpty()) {
            return;
        }
        Set<String> oldIds = new HashSet<>();
        for (Discovery d : oldSet) {
            oldIds.add(d.getServerId());
        }
        Set<String> newIds = new HashSet<>();
        for (Discovery d : newList) {
            newIds.add(d.getServerId());
        }
        for (Discovery d : oldSet) {
            if (!newIds.contains(d.getServerId())) {
                for (ServiceDiscoveryListener l : pathListeners) {
                    l.listen(path, d, Event.REMOVE);
                }
            }
        }
        for (Discovery d : newList) {
            if (!oldIds.contains(d.getServerId())) {
                for (ServiceDiscoveryListener l : pathListeners) {
                    l.listen(path, d, Event.ADD);
                }
            }
        }
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        String path = StringUtils.startWithAppend(serviceName, "/");
        listeners.computeIfAbsent(path, k -> new ArrayList<>()).add(listener);
    }

    @Override
    public void unsubscribe(String serviceName, ServiceDiscoveryListener listener) {
        String path = StringUtils.startWithAppend(serviceName, "/");
        List<ServiceDiscoveryListener> pathListeners = listeners.get(path);
        if (pathListeners != null) {
            pathListeners.remove(listener);
        }
    }

    @Override
    public void close() {
        if (client != null && client.getState() == CuratorFrameworkState.STARTED) {
            client.close();
        }
    }
}
