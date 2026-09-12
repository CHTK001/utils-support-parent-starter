package com.chua.common.support.network.discovery;

import com.chua.common.support.lang.balance.LoadBalance;
import com.chua.common.support.lang.balance.Node;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
* 服务发现抽象基类。
* 提供了服务列表的本地缓存、负载均衡策略的选择以及路径解析等通用功能。
* 子类需要实现具体的服务注册和发现逻辑（通过 hook 方法）。
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractServiceDiscovery implements ServiceDiscovery {

    /** 日志 */
    protected final Logger log = LoggerFactory.getLogger(getClass());
    /**
    * 本地服务列表缓存：Key 为路径，Value 为该路径下的服务实例列表
     */
    protected final ConcurrentMap<String, List<Discovery>> localCache = new ConcurrentHashMap<>();
    /**
    * 负载均衡器缓存：Key 为组合键 (路径#策略#协议)，Value 为带版本号的负载均衡器
     */
    private final ConcurrentMap<String, CachedLoadBalance> loadBalanceCache = new ConcurrentHashMap<>();
    /**
    * 服务版本计数器，用于使缓存失效
     */
    private final AtomicLong serviceVersion = new AtomicLong(0);

    /** Discoveryoption */
    protected DiscoveryOption discoveryOption;
    /** Cluster名称 */
    protected String clusterName;

    /**
    * 构造函数，使用默认空集群名称。
    * @param discoveryOption 发现选项配置
     */
    public AbstractServiceDiscovery(DiscoveryOption discoveryOption) {
        this(discoveryOption, "");
    }

    /**
    * 构造函数。
    * @param discoveryOption 发现选项配置
    * @param clusterName 集群名称，用于隔离不同集群的服务
     */
    public AbstractServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        this.discoveryOption = discoveryOption;
        this.clusterName = clusterName != null ? clusterName : "";
    }

    // ======================== 缓存辅助方法 ========================

    /**
    * 将单个服务发现对象添加到本地缓存中。
    * @param path 服务路径
    * @param discovery 服务发现对象
     */
    protected void addToCache(String path, Discovery discovery) {
        path = StringUtils.startWithAppend(path, "/");
        // CopyOnWriteArrayList：写时拷贝，读时无锁快照，避免 gossip/心跳/查询并发写坏链表结构
        localCache.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(discovery);
    }

    /**
    * 根据服务器ID从本地缓存中移除对应的服务发现对象。
    * @param path 服务路径
    * @param serverId 服务器唯一标识
     */
    protected void removeFromCache(String path, String serverId) {
        path = StringUtils.startWithAppend(path, "/");
        localCache.computeIfPresent(path, (k, list) -> {
            list.removeIf(d -> serverId.equals(d.getServerId()));
            return list.isEmpty() ? null : list;
        });
    }

    /**
    * 替换指定路径下的整个服务列表缓存。
    * @param path 服务路径
    * @param discoveries 新的服务发现集合
     */
    protected void replaceCache(String path, Collection<Discovery> discoveries) {
        path = StringUtils.startWithAppend(path, "/");
        localCache.put(path, new CopyOnWriteArrayList<>(discoveries));
    }

    /**
    * 清空所有本地缓存。
     */
    public void clearCache() {
        localCache.clear();
    }

    /**
    * 增加服务版本号，通知缓存系统数据已变更，旧缓存失效。
     */
    protected void incrementServiceVersion() {
        serviceVersion.incrementAndGet();
    }

    /**
    * 判断是否为 seed 引导节点或不参与业务路由的标记节点。
    * <p>seed 仅用于引导发现；metadata 标记 self=true 的节点（如网关自身）同样不参与业务负载均衡，
    * 避免网关把流量转发回自身形成回环。</p>
    *
    * @param discovery 服务发现数据
    * @return true 表示不参与业务路由
     */
    protected boolean isSeedNode(Discovery discovery) {
        if (discovery == null || discovery.getMetadata() == null) {
            return false;
        }
        return Boolean.parseBoolean(discovery.getMetadata().get("seed"))
                || Boolean.parseBoolean(discovery.getMetadata().get("self"));
    }

    /**
    * 构建负载均衡器的缓存键。
    * @param path 服务路径
    * @param balance 负载均衡策略名称
    * @param protocol 协议类型
    * @return 组合后的缓存键字符串
     */
    private String buildCacheKey(String path, String scatterId, String balance, String protocol) {
        return path + "#" + (scatterId != null ? scatterId : "") + "#"
                + (balance != null ? balance : "weight") + "#" + (protocol != null ? protocol : "");
    }

    // ======================== 子类查询钩子 ========================

    /**
    * 获取指定路径下的服务列表（直接从本地缓存读取）。
    * 子类可以重写此方法以提供特定的查询逻辑或数据来源。
    * @param path 服务路径
    * @return 服务发现集合
     */
    protected Set<Discovery> get(String path) {
        List<Discovery> list = localCache.get(path);
        if (CollectionUtils.size(list) == 0) {
            return Collections.emptySet();
        }
        // 按 serverId 幂等去重：Discovery 为 @Data 全字段 equals（含动态 weight），
        // 同 serverId 每次 weight 变化会导致 HashSet 视为不同对象、条目无限累积
        // （跨机 gossip 实测 5→12+ 膨胀）。用 LinkedHashMap 以 serverId 为键保留最新一条。
        java.util.Map<String, Discovery> dedup = new java.util.LinkedHashMap<>();
        for (Discovery d : list) {
            if (d.getServerId() != null) {
                dedup.put(d.getServerId(), d);
            }
        }
        return new java.util.LinkedHashSet<>(dedup.values());
    }

    // ======================== 子类后端钩子 ========================

    /**
    * 当服务被注销时调用的钩子方法。
    * 子类应在此处实现向注册中心取消注册的具体逻辑。
    * @param path 服务路径
    * @param discovery 被注销的服务信息
     */
    protected void doUnregister(String path, Discovery discovery) {
    }

    /**
    * 当服务更新时调用的钩子方法。
    * 子类应在此处实现向注册中心更新服务信息的逻辑。
    * @param path 服务路径
    * @param oldDiscovery 旧的服务信息
    * @param newDiscovery 新的服务信息
     */
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
    }

    // ======================== 公共 API ========================

    /**
    * 获取指定路径下的所有服务实例。
    * @param path 服务路径
    * @return 所有服务实例集合
     */
    @Override
    public Set<Discovery> getServiceAll(String path) {
        return getPath(addClusterPrefix(path));
    }

    /**
    * 根据路径、负载均衡策略和协议获取一个服务实例。
    * 优先使用缓存的负载均衡结果，若缓存无效则重新计算并缓存。
    * @param path 服务路径
    * @param balance 负载均衡策略名称（如"weight", "random"等）
    * @param protocol 协议类型（可选，用于过滤）
    * @return 选中的服务实例，若无可用服务则返回null
     */
    @Override
    public Discovery getService(String path, String scatterId, String balance, String protocol) {
        String prefixedPath = addClusterPrefix(path);
        String normalizedPath = StringUtils.startWithAppend(prefixedPath, "/");
        Set<Discovery> services = getPath(normalizedPath);
        if (CollectionUtils.size(services) == 0) {
            return null;
        }

        if (StringUtils.isNullOrEmpty(balance)) {
            balance = "weight";
        }

        String cacheKey = buildCacheKey(normalizedPath, scatterId, balance, protocol);
        long currentVersion = serviceVersion.get();
        // 收集当前服务表中的 serverId，用于检测服务是否实际变化
        Set<String> currentServices = services.stream()
                .map(Discovery::getServerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        CachedLoadBalance cached = loadBalanceCache.get(cacheKey);
        if (cached != null && cached.isValid(currentVersion, currentServices)) {
            // 服务表未变，复用已有 LoadBalance 实例，保持 weight 衰减等状态
            Node selectNode = cached.loadBalance.selectNode();
            return selectNode == null ? null : selectNode.getValue(Discovery.class);
        }

        LoadBalance lb = ServiceProvider.of(LoadBalance.class).getNewExtension(balance);
        if (lb == null) {
            // 如果找不到指定的负载均衡策略，直接返回列表中的第一个
            List<Discovery> list = new ArrayList<>(services);
            return list.isEmpty() ? null : list.get(0);
        }
        LoadBalance balancer = lb.create();
        for (Discovery d : services) {
            // seed 引导节点不参与业务负载均衡，仅用于发现引导
            if (isSeedNode(d)) {
                continue;
            }
            // 如果指定了协议，则只添加匹配协议的服务
            if (StringUtils.isNotBlank(protocol) && !protocol.equalsIgnoreCase(d.getProtocol())) {
                continue;
            }
            // scatterId 业务隔离:仅纳入相同分组的节点
            if (StringUtils.isNotBlank(scatterId) && !scatterId.equals(d.getScatterId())) {
                continue;
            }
            Node node = new Node(d);
            node.setWeight(d.getWeight());
            balancer.addNode(node);
        }
        // 以当前服务快照存入缓存，下次调用时若无变化则复用该 SPI 实例
        loadBalanceCache.put(cacheKey, new CachedLoadBalance(balancer, currentVersion, currentServices));
        Node selectNode = balancer.selectNode();
        return selectNode == null ? null : selectNode.getValue(Discovery.class);
    }

    /**
    * 注销指定服务实例。
    * @param path 服务路径
    * @param discovery 要注销的服务详情
    * @return 当前实例，支持链式调用
     */
    @Override
    public ServiceDiscovery unregisterService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        doUnregister(prefixed, discovery);
        removeFromCache(prefixed, discovery.getServerId());
        incrementServiceVersion();
        return this;
    }

    /**
    * 根据服务器ID注销服务。
    * @param path 服务路径
    * @param serverId 要注销的服务ID
    * @return 当前实例，支持链式调用
     */
    @Override
    public ServiceDiscovery unregisterService(String path, String serverId) {
        String prefixed = addClusterPrefix(path);
        doUnregister(prefixed, Discovery.builder().serverId(serverId).build());
        removeFromCache(prefixed, serverId);
        incrementServiceVersion();
        return this;
    }

    /**
    * 更新指定服务的信息。
    * 如果服务已存在则更新，否则添加新服务。
    * @param path 服务路径
    * @param discovery 新的服务详情
    * @return 当前实例，支持链式调用
     */
    @Override
    public ServiceDiscovery updateService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        String normalizedKey = StringUtils.startWithAppend(prefixed, "/");
        // 使用 compute 而非 computeIfPresent:key 不存在时同样执行(否则新服务被静默丢弃)
        localCache.compute(normalizedKey, (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            for (int i = 0; i < list.size(); i++) {
                if (discovery.getServerId() != null && discovery.getServerId().equals(list.get(i).getServerId())) {
                    Discovery old = list.get(i);
                    list.set(i, discovery);
                    doUpdate(prefixed, old, discovery);
                    return list;
                }
            }
            list.add(discovery);
            return list;
        });
        incrementServiceVersion();
        return this;
    }

    // ======================== 路径处理 ========================

    /**
    * 判断是否需要路径前缀隔离（即是否需要在路径前加上集群名）。
    * @return true 表示需要隔离，false 表示不需要
     */
    protected boolean needsPathPrefixIsolation() {
        return true;
    }

    /**
    * 为路径添加集群前缀。
    * @param path 原始路径
    * @return 添加前缀后的路径
     */
    protected String addClusterPrefix(String path) {
        if (!needsPathPrefixIsolation() || clusterName == null || clusterName.isEmpty()) {
            return path;
        }
        String normalizedPath = StringUtils.startWithAppend(path, "/");
        return "/" + clusterName + normalizedPath;
    }

    /**
    * 获取指定路径下的服务集合，支持路径模糊匹配（向上回溯查找）。
    * 例如：请求 "/a/b/c"，如果该路径下没有服务，会依次尝试 "/a/b", "/a", "/"。
    * @param path 服务路径
    * @return 找到的服务集合，未找到则返回空集合
     */
    public Set<Discovery> getPath(String path) {
        path = StringUtils.endWithMove(path, "/");
        log.debug("getPath: {} ", path);
        Set<Discovery> netAddresses = get(path);
        log.debug("getPath result: {} size={}", path, CollectionUtils.size(netAddresses));
        
        List<String> strings = Arrays.asList(path.replaceFirst("^/", "").split("/"));
        // 如果路径层级过浅且没找到，尝试根路径
        if (strings.size() <= 1 && CollectionUtils.size(netAddresses) == 0) {
            return get("/");
        }
        // 如果当前路径有结果，直接返回
        if (CollectionUtils.size(netAddresses) > 0) {
            return netAddresses;
        }
        
        // 向上回溯查找父级路径
        int index = strings.size() - 1;
        while (CollectionUtils.size(netAddresses) == 0 && index > 0) {
            String newPath = "/" + String.join("/", strings.subList(0, index));
            netAddresses = get(newPath);
            if (CollectionUtils.size(netAddresses) > 0) {
                return netAddresses;
            }
            index--;
        }
        return Collections.emptySet();
    }

    /**
    * 内部类：用于缓存负载均衡器及其关联的版本号和服务快照。
    * 当服务列表发生变更时重建 LoadBalance SPI 实例；
    * 服务表不变时复用已有实例，保持 weight 衰减等内部状态持久。
     */
    private static class CachedLoadBalance {
        final LoadBalance loadBalance;
        final long version;
        /** 注册时绑定的服务 serverId 集合（用于检测服务表是否实际变化） */
        final Set<String> serviceKeys;

        CachedLoadBalance(LoadBalance loadBalance, long version, Set<String> serviceKeys) {
            this.loadBalance = loadBalance;
            this.version = version;
            this.serviceKeys = serviceKeys;
        }

        /**
        * 检查缓存是否有效。
        * 满足以下任一条件即认为有效：版本号匹配，或服务快照相同（服务未增删）。
        * @param currentVersion 当前全局服务版本号
        * @param currentServices 当前服务 serverId 集合
        * @return true 表示缓存有效，false 表示需要重建负载均衡器
         */
        boolean isValid(long currentVersion, Set<String> currentServices) {
            return this.version == currentVersion
                    || java.util.Objects.equals(this.serviceKeys, currentServices);
        }
    }
}
