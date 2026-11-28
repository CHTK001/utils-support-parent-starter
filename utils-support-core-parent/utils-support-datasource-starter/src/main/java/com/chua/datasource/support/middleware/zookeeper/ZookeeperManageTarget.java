package com.chua.datasource.support.middleware.zookeeper;

import java.util.ArrayList;
import java.util.List;

/**
 * ZooKeeper 管理侧连接目标：由宿主（数据源台账）解析后传入。
 *
 * <p>实现方<b>不要</b>自己去查库或依赖宿主的 Spring 容器 —— 拿到的就是这份不可变参数，
 * 因此实现可以直接跑在 OSGi bundle 里。</p>
 *
 * @param datasourceId 数据源主键，即席探测时为 0
 * @param name         数据源名称（仅用于失败文案）
 * @param host         主机地址，支持逗号分隔多节点，每个节点可自带端口
 * @param port         端口，缺省 2181
 * @param namespace    chroot 命名空间，可为空串
 * @param username     digest 账号，空表示无认证
 * @param password     digest 口令
 * @param versionToken 数据源配置版本标识（如更新时间），用于配置变更后淘汰旧会话
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperManageTarget(long datasourceId,
                                    String name,
                                    String host,
                                    int port,
                                    String namespace,
                                    String username,
                                    String password,
                                    String versionToken) {

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 2181;

    /**
     * 规整空值，避免实现方到处判空。
     */
    public ZookeeperManageTarget {
        name = name == null ? "" : name;
        host = host == null ? "" : host.trim();
        namespace = namespace == null ? "" : namespace.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        versionToken = versionToken == null ? "" : versionToken;
    }

    /**
     * 实际端口：未填写或非法时取默认 2181。
     *
     * @return 端口号
     */
    public int effectivePort() {
        return port <= 0 ? DEFAULT_PORT : port;
    }

    /**
     * 解析出全部集群节点地址（{@code host:port}）。
     *
     * <p>host 支持逗号分隔多节点；未显式写端口的节点统一补 {@link #effectivePort()}。</p>
     *
     * @return 节点地址列表，host 为空时返回空列表
     */
    public List<String> hosts() {
        List<String> nodes = new ArrayList<>();
        for (String node : host.split(",")) {
            String value = node.trim();
            if (value.isEmpty()) {
                continue;
            }
            nodes.add(value.contains(":") ? value : value + ":" + effectivePort());
        }
        return nodes;
    }

    /**
     * Curator 连接串。
     *
     * @return 逗号分隔的节点地址；host 为空时返回空串
     * @throws IllegalStateException host 填写了但无法解析出任何节点
     */
    public String connectString() {
        List<String> nodes = hosts();
        if (!host.isEmpty() && nodes.isEmpty()) {
            throw new IllegalStateException("数据源 " + name + " 的主机地址无法解析");
        }
        return String.join(",", nodes);
    }
}
