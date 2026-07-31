package com.chua.remote.support.gateway.core.auth;

import com.chua.remote.support.gateway.config.Protocol;

/**
 * ACL 访问控制管理器
 *
 * <p>函数式接口，用于检查客户端是否有权访问指定的目标服务。
 * 根据客户端 ID、目标服务 ID 和协议类型进行权限判定。
 *
 * @author CH
 * @since 4.0.0.41
 */
@FunctionalInterface
public interface AclManager {
    /**
     * 检查权限
     *
     * @param clientId 客户端标识
     * @param targetId 目标服务标识
     * @param protocol 请求的协议类型
     * @return 允许访问返回 true
     */
    boolean checkPermission(String clientId, String targetId, Protocol protocol);
}
