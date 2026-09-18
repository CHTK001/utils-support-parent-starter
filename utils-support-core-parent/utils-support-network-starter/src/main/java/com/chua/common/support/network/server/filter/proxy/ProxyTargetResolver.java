package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.discovery.Discovery;

import java.net.InetSocketAddress;

/**
* 代理目标解析器，从连接信息中解析后端地址。
*
* <p>代理 Filter 通过此接口获取每个请求对应的后端Discovery，
* 实现与路由策略的解耦。</p>
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface ProxyTargetResolver {

    /**
    * 根据连接信息解析后端地址。
    *
    * @param remoteAddr 客户端地址（可为 空）
    * @return Discovery 对象，返回 空 表示无法解析
    */
    Discovery resolve(InetSocketAddress remoteAddr);
}
