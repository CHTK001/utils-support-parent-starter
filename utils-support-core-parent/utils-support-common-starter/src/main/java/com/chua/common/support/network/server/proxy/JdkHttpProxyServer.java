package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;

import java.net.InetSocketAddress;

/**
 * JDK 虚拟线程版 HTTP 反向代理服务器。
 *
 * <p>复用 {@link TcpProxyServer} 的字节转发模型（全双工虚拟线程流式转发），
 * 不解析 HTTP 协议，纯字节级透传，性能与 tcp-proxy 一致（~19k TPS @1000c）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"http-proxy"})
public class JdkHttpProxyServer extends TcpProxyServer {

    /**
     * 构造方法，创建 JdkHttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     */
    public JdkHttpProxyServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 构造方法，创建 JdkHttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     * @param backend 方法入参 backend
     */
    public JdkHttpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        super(setting, backend);
    }

    /**
     * 构造方法，创建 JdkHttpProxy服务端 实例。
     *
     * @param setting 方法入参 setting
     * @param targetResolver 目标Resolver，不允许为 null
     */
    public JdkHttpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting, targetResolver);
    }
}
