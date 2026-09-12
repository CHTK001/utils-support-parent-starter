package com.chua.common.support.network.discovery;

import lombok.extern.slf4j.Slf4j;

/**
* 默认的服务发现实现类。
* <p>
* 该类继承自 {@link AbstractServiceDiscovery}，提供了服务注册和发现的基础功能。
* 它支持通过配置选项初始化，并允许指定集群名称以隔离不同的服务环境。
* 该实现采用本地缓存机制来管理已注册的服务，但不涉及网络层面的服务发现逻辑。
* </p>
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DefaultServiceDiscovery extends AbstractServiceDiscovery {

    /**
    * 默认构造函数。
    * <p>
    * 使用默认的 {@link DiscoveryOption} 配置初始化服务发现实例。
    * </p>
     */
    public DefaultServiceDiscovery() {
        this(new DiscoveryOption());
    }

    /**
    * 带配置选项的构造函数。
    * <p>
    * 根据指定的 {@link DiscoveryOption} 配置初始化服务发现实例。
    *
    * @param discoveryOption 服务发现配置选项，包含协议、超时等参数设置。
     */
    public DefaultServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    /**
    * 带配置选项和集群名称的构造函数。
    * <p>
    * 根据指定的配置选项和集群名称初始化服务发现实例，用于区分不同集群的服务。
    *
    * @param discoveryOption 服务发现配置选项。
    * @param clusterName     集群名称，用于生成带有前缀的服务路径。
     */
    public DefaultServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    /**
    * 启动服务发现进程。
    * <p>
    * 当前实现为无操作（NOP），因为本类主要依赖本地缓存，不涉及异步监听或网络轮询。
    * 子类若需扩展动态发现逻辑，可在此处重写。
    * </p>
     */
    @Override
    public void start() {
        // 默认实现为空，无需执行额外启动逻辑
    }

    /**
    * 注册一个服务到本地缓存中。
    * <p>
    * 该方法将给定的服务路径添加集群前缀后存入缓存，并更新服务版本号。
    *
    * @param path      服务的相对路径，例如 "/api/user"。
    * @param discovery 具体的服务发现对象，包含服务的元数据和连接信息。
    * @return 当前 {@link ServiceDiscovery} 实例，支持链式调用。
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        // 为路径添加集群前缀，确保服务路径的唯一性和隔离性
        String prefixedPath = addClusterPrefix(path);
        // 将处理后的路径设置为发现对象的 URI 规范
        discovery.setUriSpec(prefixedPath);
        // 将服务加入本地缓存
        addToCache(prefixedPath, discovery);
        // 递增服务版本号，触发缓存刷新或通知机制
        incrementServiceVersion();
        return this;
    }

    /**
    * 关闭服务发现实例。
    * <p>
    * 清除所有已缓存的服务信息，释放相关资源。
    * </p>
     */
    @Override
    public void close() {
        clearCache();
    }
}
