package com.chua.common.support.network.sync;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import lombok.Getter;
import lombok.Setter;

/**
 * 同步通道配置：描述「连到谁」，与「怎么连」解耦。
 *
 * <p>同步通道的寻址有两种来源，本类把它们收敛到一个对象里：</p>
 * <ul>
 *     <li><b>服务发现</b>：配置 {@link #discovery} + {@link #serviceName} + {@link #scatterId}，
 *         由发现中心返回可用地址，天然支持多实例与故障摘除；</li>
 *     <li><b>显式地址</b>：配置 {@link #serverUrl}，适合单机、调试或尚未接入发现中心的场景。</li>
 * </ul>
 *
 * <p>两者同时存在时以服务发现为准，发现不到才回落到 {@link #serverUrl}。</p>
 *
 * <p>{@link #scatterId} 对应 {@link Discovery#getScatterId()}：同一服务名下用业务分组隔离
 * 不同集群的实例，服务端注册与客户端查询必须使用同一个值，否则会「注册上了却查不到」。</p>
 *
 * <p>本类刻意与 {@link com.chua.common.support.network.client.ClientSetting}（HTTP 客户端超时/重试）
 * 和 {@link com.chua.common.support.network.server.ServerSetting}（服务端绑定/线程/缓冲）分开：
 * 那两个类的字段全部服务于各自的协议栈，同步寻址塞进去会变成两个不相干的语义挤在一个类里。</p>
 *
 * @author CH
 * @since 4.0.0.50
 * @see SyncAddressResolver
 */
@Getter
@Setter
public class SyncSetting {

    /**
     * 显式服务端地址（如 http://127.0.0.1:19380）。
     *
     * <p>服务发现可用时作为兜底，不可用时作为唯一地址来源。</p>
     */
    private String serverUrl;

    /**
     * 服务发现实例，为空表示不使用服务发现。
     */
    private ServiceDiscovery discovery;

    /**
     * 服务名，即服务端注册时使用的 path。
     */
    private String serviceName;

    /**
     * 业务分组标识，对应 {@link Discovery#getScatterId()}。
     *
     * <p>为空时回落到 {@link #serviceName}，保证「注册与查询落在同一分组」这一默认行为成立。</p>
     */
    private String scatterId;

    /**
     * 创建空配置。
     */
    public SyncSetting() {
    }

    /**
     * 创建仅含显式地址的配置。
     *
     * @param serverUrl 服务端地址
     */
    public SyncSetting(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * 创建基于服务发现的配置。
     *
     * @param discovery   服务发现实例
     * @param serviceName 服务名（注册时使用的 path）
     * @param scatterId   业务分组标识
     */
    public SyncSetting(ServiceDiscovery discovery, String serviceName, String scatterId) {
        this.discovery = discovery;
        this.serviceName = serviceName;
        this.scatterId = scatterId;
    }

    /**
     * 获取实际生效的业务分组标识。
     *
     * <p>未显式配置时回落到服务名：服务名本身已经是「这个通道叫什么」的声明，
     * 用它当分组可以让注册端和查询端在零配置下依然互通。</p>
     *
     * @return 生效的 scatterId；服务名也为空时返回 null
     */
    public String effectiveScatterId() {
        return scatterId == null || scatterId.isBlank() ? serviceName : scatterId;
    }

    /**
     * 判断是否配置了可用的服务发现。
     *
     * @return 发现实例与服务名都存在时返回 true
     */
    public boolean isDiscoveryAvailable() {
        return discovery != null && serviceName != null && !serviceName.isBlank();
    }
}
