package com.chua.datasource.support.middleware.zookeeper;

import java.util.List;

/**
 * ZooKeeper 管理面 SPI：节点树浏览、节点读写、ACL 查看与集群指标采集。
 *
 * <h3>为什么单独一个契约</h3>
 * <p>通用中间件契约 {@link com.chua.datasource.support.middleware.MiddlewareClientProvider}
 * 的能力位只覆盖「连通性 + 浏览 + 查询」，而管理页面还需要建删节点、改数据、看 ACL、
 * 读 {@code mntr} 指标。这些动作与 Curator/ZooKeeper 客户端强绑定，不能塞进通用能力位里，
 * 否则每个中间件实现都要为不属于自己的能力位写空实现。</p>
 *
 * <h3>装配方式</h3>
 * <ul>
 *   <li><b>默认装载</b>：宿主引了 {@code utils-support-zookeeper-starter}，
 *       实现方在 {@code META-INF/extensions/com.chua.datasource.support.middleware.zookeeper.
 *       ZookeeperManageProvider} 登记，按 {@link #SPI_NAME} 取用；</li>
 *   <li><b>OSGi 后装</b>：同一份 jar 装进框架后由 {@code OsgiServiceResolver} 命中；</li>
 *   <li><b>未装配</b>：宿主抛出「功能未开放」，而不是编译期依赖 Curator。</li>
 * </ul>
 *
 * <p><b>实现约定</b>：只接收 {@link ZookeeperManageTarget}，不查库、不依赖宿主 Spring 容器；
 * 远端异常一律翻译成 {@link IllegalStateException}（宿主转成业务错误码），
 * 节点不存在等语义异常保留原文案，不要返回演示数据。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface ZookeeperManageProvider {

    /**
     * SPI 名称
     */
    String SPI_NAME = "zookeeper";

    /**
     * 实现名，缺省即 {@link #SPI_NAME}。
     *
     * @return 名称，大小写不敏感
     */
    default String name() {
        return SPI_NAME;
    }

    /**
     * 测试连通性，顺带回传服务端版本与会话 ID。
     *
     * @param target 连接目标
     * @return 测试结果；连接失败时 {@code connected=false} 并带可读原因
     */
    ZookeeperTestResult test(ZookeeperManageTarget target);

    /**
     * 列出某个父路径下的一级子节点（不含数据），用于左侧树。
     *
     * @param target     连接目标
     * @param parentPath 父路径，空按根路径处理
     * @return 子节点列表
     */
    List<ZookeeperNodeInfo> tree(ZookeeperManageTarget target, String parentPath);

    /**
     * 列出某个父路径下的子节点及其数据。
     *
     * @param target     连接目标
     * @param parentPath 父路径，空按根路径处理
     * @return 子节点列表
     */
    List<ZookeeperChildNodeInfo> children(ZookeeperManageTarget target, String parentPath);

    /**
     * 取节点详情（Stat + 数据）。
     *
     * @param target 连接目标
     * @param path   节点路径
     * @return 节点详情
     * @throws IllegalStateException 节点不存在或读取失败
     */
    ZookeeperNodeDetail node(ZookeeperManageTarget target, String path);

    /**
     * 创建节点（自动补建父路径）。
     *
     * @param target    连接目标
     * @param path      节点路径
     * @param data      节点数据，空按零长度处理
     * @param ephemeral 是否临时节点
     * @return 创建成功返回 {@code true}
     */
    boolean create(ZookeeperManageTarget target, String path, String data, boolean ephemeral);

    /**
     * 覆盖节点数据。
     *
     * @param target 连接目标
     * @param path   节点路径
     * @param data   新数据，空按零长度处理
     * @return 更新成功返回 {@code true}
     */
    boolean update(ZookeeperManageTarget target, String path, String data);

    /**
     * 删除节点。
     *
     * @param target    连接目标
     * @param path      节点路径
     * @param recursive 是否连同子节点一起删除
     * @return 删除成功返回 {@code true}
     */
    boolean delete(ZookeeperManageTarget target, String path, boolean recursive);

    /**
     * 取节点 ACL。
     *
     * @param target 连接目标
     * @param path   节点路径
     * @return ACL 条目
     */
    List<ZookeeperAclInfo> acl(ZookeeperManageTarget target, String path);

    /**
     * 采集集群各节点的 {@code mntr} 指标。
     *
     * @param target 连接目标
     * @return 每节点一条；采集失败时带 {@code error}，不抛异常
     */
    List<ZookeeperClusterNode> cluster(ZookeeperManageTarget target);
}
