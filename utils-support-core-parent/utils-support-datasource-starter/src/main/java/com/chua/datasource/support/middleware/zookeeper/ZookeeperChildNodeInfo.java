package com.chua.datasource.support.middleware.zookeeper;

/**
 * 子节点及其数据。
 *
 * @param name      节点名
 * @param path      节点绝对路径
 * @param ephemeral 是否临时节点
 * @param data      节点数据的可展示文本
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperChildNodeInfo(String name, String path, Boolean ephemeral, String data) {
}
