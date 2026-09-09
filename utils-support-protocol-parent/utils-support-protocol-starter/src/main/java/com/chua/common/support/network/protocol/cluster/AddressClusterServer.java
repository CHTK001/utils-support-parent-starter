package com.chua.common.support.network.protocol.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.net.NetAddress;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.network.protocol.server.ServletHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于地址列表的集群服务器实现
 * <p>
 * 管理基于地址列表的集群服务器，支持多地址部署。
 *
 * @author CH
 * @since 4.0.0.30
 */
@Slf4j
@Getter
public class AddressClusterServer extends AbstractProtocolServer implements ClusterServer {

    /** 集群实例 */
    private final Cluster cluster;

    /** 地址列表 */
    private final String[] addresses;

    /** 运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造函数
     *
     * @param cluster 集群实例
     * @param addresses 地址列表
     */
    public AddressClusterServer(Cluster cluster, String... addresses) {
        super(ServerSetting.builder().build());
        if (cluster == null) {
            throw new IllegalArgumentException("集群实例不能为空");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("地址列表不能为空");
        }

        // 验证地址格式
        for (String address : addresses) {
            if (address == null || address.trim().isEmpty()) {
                throw new IllegalArgumentException("地址不能为空");
            }
        }

        this.cluster = cluster;
        this.addresses = Arrays.stream(addresses)
                .map(String::trim)
                .toArray(String[]::new);

        if (log.isDebugEnabled()) {
            log.debug("创建地址集群服务器: 地址={}", Arrays.toString(this.addresses));
        }
    }


    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    protected void doStart() throws Exception {
        if (running.compareAndSet(false, true)) {
            try {
                log.info("启动地址集群服务器: 地址={}", Arrays.toString(addresses));

                // 1. 首先启动集群
                if (!cluster.isRunning()) {
                    cluster.start();
                    if (log.isDebugEnabled()) {
                        log.debug("集群启动完成: {}", cluster.getClusterName());
                    }
                }

                // 2. 注册地址到服务发现
                registerAddresses();

                log.info("地址集群服务器启动成功: 地址={}", Arrays.toString(addresses));

            } catch (Exception e) {
                running.set(false);
                log.error("地址集群服务器启动失败: 地址={}", Arrays.toString(addresses), e);

                // 清理已启动的资源
                cleanup();
                throw e;
            }
        } else {
            log.warn("地址集群服务器已经在运行中: 地址={}", Arrays.toString(addresses));
        }
    }

    @Override
    protected void doStop() throws Exception {
        if (running.compareAndSet(true, false)) {
            try {
                log.info("停止地址集群服务器: 地址={}", Arrays.toString(addresses));

                // 按照启动的逆序进行停止操作

                // 1. 注销地址
                unregisterAddresses();

                // 2. 停止集群
                if (cluster.isRunning()) {
                    cluster.stop();
                    if (log.isDebugEnabled()) {
                        log.debug("集群停止完成: {}", cluster.getClusterName());
                    }
                }

                log.info("地址集群服务器停止成功: 地址={}", Arrays.toString(addresses));

            } catch (Exception e) {
                running.set(true);
                log.error("地址集群服务器停止失败: 地址={}", Arrays.toString(addresses), e);
                throw e;
            }
        } else {
            log.warn("地址集群服务器已经停止: 地址={}", Arrays.toString(addresses));
        }
    }

    @Override
    public String getClusterName() {
        return cluster.getClusterName();
    }

    @Override
    public String getClusterServerInfo() {
        return String.format("AddressClusterServer{地址=%s, 集群=%s, 运行状态=%s}",
                Arrays.toString(addresses), cluster.getClusterName(), isRunning());
    }

    /**
     * 注册地址到服务发现
     *
     * @throws Exception 如果注册失败
     */
    private void registerAddresses() throws Exception {
        try {
            for (String address : addresses) {
                // 这里可以根据具体的服务发现实现来注册地址
                NetAddress netAddress = NetAddress.of(address);
                cluster.addDiscovery(
                        Discovery.builder()
                                .uriSpec("/")
                                .host(netAddress.getHost())
                                .port(netAddress.getPort())
                                .protocol(netAddress.getProtocol())
                                .headers(netAddress.getUrlQuery().getQueryMap())
                                .build()
                );
                if (log.isDebugEnabled()) {
                    log.debug("注册地址到服务发现: 集群={}, 地址={}", cluster.getClusterName(), address);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("所有地址注册完成: {}", Arrays.toString(addresses));
            }
        } catch (Exception e) {
            log.error("注册地址失败", e);
            throw e;
        }
    }

    /**
     * 注销地址
     *
     * @throws Exception 如果注销失败
     */
    private void unregisterAddresses() throws Exception {
        try {
            for (String address : addresses) {
                // 这里可以根据具体的服务发现实现来注销地址
                // 例如：cluster.getServiceDiscovery().unregister(cluster.getClusterName(), address);
                if (log.isDebugEnabled()) {
                    log.debug("注销地址: 集群={}, 地址={}", cluster.getClusterName(), address);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("所有地址注销完成: {}", Arrays.toString(addresses));
            }
        } catch (Exception e) {
            log.error("注销地址失败", e);
            throw e;
        }
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        try {
            unregisterAddresses();
        } catch (Exception e) {
            log.warn("清理地址注册时发生错误", e);
        }

        try {
            if (cluster.isRunning()) {
                cluster.stop();
            }
        } catch (Exception e) {
            log.warn("清理集群时发生错误", e);
        }
    }

    @Override
    public ProtocolServer registerMapping(String path, ServletHandler handler) {
        return null;
    }

    @Override
    public ProtocolServer registerMapping(String path, HttpMethod method, ServletHandler handler) {
        return null;
    }

    @Override
    public ProtocolServer registerMapping(String path, HttpMethod[] methods, ServletHandler handler) {
        return null;
    }
}