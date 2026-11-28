package com.chua.datasource.support.middleware.zookeeper;

/**
 * 节点详情：Stat 元信息 + 节点数据。
 *
 * @param path           节点绝对路径
 * @param ephemeral      是否临时节点
 * @param createdAt      创建时间，可读文本
 * @param updatedAt      最后修改时间，可读文本
 * @param version        数据版本号
 * @param numChildren    直接子节点数量
 * @param dataLength     数据长度（字节）
 * @param data           节点数据可展示文本
 * @param czxid          创建事务 ID
 * @param mzxid          最后修改事务 ID
 * @param pzxid          最后修改子节点的事务 ID
 * @param cversion       子节点版本号
 * @param aversion       ACL 版本号
 * @param ephemeralOwner 临时节点持有者会话 ID，持久节点为 0
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperNodeDetail(String path,
                                  Boolean ephemeral,
                                  String createdAt,
                                  String updatedAt,
                                  Integer version,
                                  Integer numChildren,
                                  Integer dataLength,
                                  String data,
                                  Long czxid,
                                  Long mzxid,
                                  Long pzxid,
                                  Integer cversion,
                                  Integer aversion,
                                  Long ephemeralOwner) {
}
