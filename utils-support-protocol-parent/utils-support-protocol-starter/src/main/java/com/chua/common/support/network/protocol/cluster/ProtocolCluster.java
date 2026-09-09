package com.chua.common.support.network.protocol.cluster;

import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.core.spi.ServiceProvider;

import static com.chua.common.support.core.constant.NameConstant.DEFAULT;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 协议集群实现类
 * <p>
 * 该类实现了集群接口，管理服务发现和协议服务器的集群功能。
 *
 * @author CH
 */
public class ProtocolCluster extends AbstractCluster {

    /**
     * 构造函数：使用指定的服务发现、协议服务器和集群名称创建集群实例
     *
     * @param serviceDiscovery 服务发现实例，发现和注册服务
     * @param protocolServer   协议服务器实例，处理协议相关的请求
     * @param clusterName      集群名称，标识该集群实例
     */
    public ProtocolCluster(ServiceDiscovery serviceDiscovery, ProtocolServer protocolServer, String clusterName) {
        super(serviceDiscovery, protocolServer, clusterName);
    }

    /**
     * 构造函数：使用指定的服务发现创建集群实例，使用默认的HTTP代理协议服务器和默认集群名称
     *
     * @param serviceDiscovery 服务发现实例，发现和注册服务
     */
    public ProtocolCluster(ServiceDiscovery serviceDiscovery) {
        super(serviceDiscovery, 
              ProtocolServer.create("http-proxy", ServerSetting.builder().build()),
                DEFAULT);
    }

    /**
     * 构造函数：使用指定的服务发现和集群名称创建集群实例，使用默认的HTTP代理协议服务器
     *
     * @param serviceDiscovery 服务发现实例，发现和注册服务
     * @param clusterName      集群名称，标识该集群实例
     */
    public ProtocolCluster(ServiceDiscovery serviceDiscovery, String clusterName) {
        super(serviceDiscovery, 
              ProtocolServer.create("http-proxy", ServerSetting.builder().build()), 
              clusterName);
    }

    /**
     * 构造函数：使用服务发现类型、发现选项、协议服务器和集群名称创建集群实例
     *
     * @param serviceDiscoveryType 服务发现类型，指定服务发现的实现方式
     * @param discoveryOption      发现选项，包含服务发现的配置信息
     * @param protocolServer       协议服务器实例，处理协议相关的请求
     * @param clusterName          集群名称，标识该集群实例
     */
    public ProtocolCluster(String serviceDiscoveryType, DiscoveryOption discoveryOption, ProtocolServer protocolServer, String clusterName) {
        this(ServiceProvider.of(ServiceDiscovery.class)
                            .getNewExtension(serviceDiscoveryType, discoveryOption, clusterName), 
             protocolServer, 
             clusterName);
    }
}