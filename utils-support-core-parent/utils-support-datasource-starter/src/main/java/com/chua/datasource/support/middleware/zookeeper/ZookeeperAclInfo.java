package com.chua.datasource.support.middleware.zookeeper;

/**
 * 节点 ACL 条目。
 *
 * @param scheme    授权方案，如 {@code world} / {@code digest} / {@code ip}
 * @param id        授权标识（digest 为 {@code user:base64}）
 * @param perms     权限位掩码
 * @param permsText 权限位可读组合，如 {@code READ|WRITE}
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperAclInfo(String scheme, String id, Integer perms, String permsText) {
}
