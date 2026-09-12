package com.chua.common.support.network.server.proxy;

import java.net.InetSocketAddress;

/**
* 代理目标解析器接口。
* <p>用于根据客户端来源解析实际后端服务地址，便于支持静态路由、服务发现、ACL 等策略。</p>
*
* @param <T> 解析上下文类型（如 {@link InetSocketAddress}）
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface ProxyTargetResolver<T> {

    /**
    * 解析后端服务地址。
    *
    * @param remote 客户端来源地址
    * @return 后端服务地址，返回 null 表示拒绝该连接
     */
    InetSocketAddress resolve(T remote);
}
