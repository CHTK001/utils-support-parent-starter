package com.chua.datasource.support.middleware.zookeeper;

/**
 * 树形浏览用的一级节点信息。
 *
 * @param path          节点绝对路径
 * @param name          节点名
 * @param ephemeral     是否临时节点
 * @param childrenCount 直接子节点数量
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperNodeInfo(String path, String name, Boolean ephemeral, Integer childrenCount) {
}
