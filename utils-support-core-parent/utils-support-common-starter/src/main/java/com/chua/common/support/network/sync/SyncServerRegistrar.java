package com.chua.common.support.network.sync;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步服务端的注册器：把「起服务」和「让客户端找得到」这两件事绑在一起。
 *
 * <p>同步服务端本身的职责只有收发消息，注册到发现中心容易漏做或多做，
 * 因此把它收敛到一个小对象里，由各 {@link com.chua.common.support.network.server.SyncServer}
 * 实现在 {@code start()} 后调用一次、{@code stop()} 前调用一次。</p>
 *
 * <p>注册信息里的 {@link Discovery#getScatterId()} 取自 {@link SyncSetting#effectiveScatterId()}，
 * 与客户端查询侧使用同一份规则，保证零配置下两端也能对上。</p>
 *
 * <p>注册失败只记录日志，不向上抛：发现中心不可用不应该让同步通道起不来。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SyncServerRegistrar {

    /**
     * 日志
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncServerRegistrar.class);

    /**
     * 同步通道配置
     */
    private final SyncSetting setting;

    /**
     * 是否已完成注册（避免重复注册）
     */
    private boolean registered;

    /**
     * 创建注册器。
     *
     * @param setting 同步通道配置，可为 null（表示不使用服务发现）
     */
    public SyncServerRegistrar(SyncSetting setting) {
        this.setting = setting;
    }

    /**
     * 创建注册器。
     *
     * @param discovery   服务发现实例
     * @param serviceName 服务名
     * @param scatterId   业务分组标识，为空时回落到服务名
     * @return 注册器实例
     */
    public static SyncServerRegistrar of(ServiceDiscovery discovery, String serviceName, String scatterId) {
        return new SyncServerRegistrar(new SyncSetting(discovery, serviceName, scatterId));
    }

    /**
     * 把已启动的服务端注册到发现中心。
     *
     * @param server 已启动的同步服务端
     */
    public void register(Server server) {
        if (registered || setting == null || !setting.isDiscoveryAvailable() || server == null) {
            return;
        }
        int port = server.getPort();
        if (port <= 0) {
            LOGGER.warn("[Sync] 服务端未绑定端口，跳过注册, service={}", setting.getServiceName());
            return;
        }
        String scatterId = setting.effectiveScatterId();
        Discovery discovery = Discovery.builder()
                .serverId(server.getProtocol() + "://" + server.getHost() + ":" + port)
                .scatterId(scatterId)
                .protocol(server.getProtocol())
                .host(server.getHost())
                .port(port)
                .weight(1.0d)
                .build();
        try {
            setting.getDiscovery().registerService(setting.getServiceName(), discovery);
            registered = true;
            LOGGER.info("[Sync] 服务端已注册, service={}, scatterId={}, address={}:{}",
                    setting.getServiceName(), scatterId, server.getHost(), port);
        } catch (Exception e) {
            LOGGER.warn("[Sync] 服务端注册失败, service={}, cause={}", setting.getServiceName(), e.getMessage());
        }
    }

    /**
     * 从发现中心注销服务端。
     *
     * @param server 即将停止的同步服务端
     */
    public void unregister(Server server) {
        if (!registered || setting == null || server == null) {
            return;
        }
        registered = false;
        Discovery discovery = Discovery.builder()
                .serverId(server.getProtocol() + "://" + server.getHost() + ":" + server.getPort())
                .scatterId(setting.effectiveScatterId())
                .host(server.getHost())
                .port(server.getPort())
                .build();
        try {
            setting.getDiscovery().unregisterService(setting.getServiceName(), discovery);
        } catch (Exception e) {
            LOGGER.warn("[Sync] 服务端注销失败, service={}, cause={}", setting.getServiceName(), e.getMessage());
        }
    }

    /**
     * 判断是否需要注册。
     *
     * @return 配置了可用服务发现时返回 true
     */
    public boolean isEnabled() {
        return setting != null && setting.isDiscoveryAvailable() && StringUtils.isNotBlank(setting.getServiceName());
    }
}
