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

    public JdkHttpProxyServer(ServerSetting setting) {
        super(setting);
    }

    public JdkHttpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        super(setting, backend);
    }

    public JdkHttpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting, targetResolver);
    }
}