package com.chua.remote.support.gateway.core.auth;

import com.chua.common.support.exception.AuthenticationException;
import com.chua.remote.support.gateway.config.Protocol;
import io.netty.channel.ChannelHandlerContext;

/**
 * 认证处理器接口。
 * <p>函数式接口，用于网关连接的逐协议身份验证。
 * 实现类根据连接协议类型（SSH / DESKTOP / HTTP 等）执行相应的认证逻辑，
 * 认证通过后返回客户端标识，失败时抛出 {@link AuthenticationException}。</p>
 *
 * @author CH
 */
@FunctionalInterface
public interface AuthHandler {

    /**
     * 执行认证。
     *
     * @param ctx      Netty 通道处理器上下文
     * @param protocol 连接使用的协议类型
     * @return 认证通过后的客户端标识（如 clientId）
     * @throws AuthenticationException 认证失败时抛出
     */
    String authenticate(ChannelHandlerContext ctx, Protocol protocol);
}
