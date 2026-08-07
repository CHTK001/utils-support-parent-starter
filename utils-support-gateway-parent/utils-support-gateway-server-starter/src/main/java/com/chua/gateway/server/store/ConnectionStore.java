package com.chua.gateway.server.store;

import java.util.List;
import java.util.Optional;

/**
 * 连接存储 SPI 接口。
 *
 * <p>提供两种查询方式：
 *   1. {@link #findByKey(String)} — 服务端预配置 key（推荐主流程）
 *   2. {@link #upsertByTarget(String, String, int, String, String)} — 自定义客户端模式
 *      页面提交 host/port/user/password，服务端记录并返回连接
 * </p>
 *
 * <p>实现类位于同级包或子包内，通过 {@code @Spi} 注解标记，可由
 * {@code ServiceProvider.of(ConnectionStore.class).collect()} 发现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ConnectionStore {

    /**
     * 根据 key 查找预配置连接。
     *
     * @param key 连接标识（如 "server01-key-aaa"）
     * @return 命中则返回连接；不存在则返回 {@link Optional#empty()}
     */
    Optional<Connection> findByKey(String key);

    /**
     * 自定义客户端模式：将 host/port/user/password 写入存储并返回连接。
     *
     * <p>对相同 (protocol,host,port) 视为同一连接，复用 user/password。</p>
     *
     * @param protocol 协议类型
     * @param host     主机
     * @param port     端口
     * @param user     用户名
     * @param password 密码
     * @return 写入或已存在的连接
     */
    Connection upsertByTarget(String protocol, String host, int port, String user, String password);

    /**
     * 列出全部预配置 key（供前端在登录页面挑选）。
     *
     * @return key 列表（顺序由实现决定）
     */
    List<String> listKeys();

    /**
     * 初始化存储（建表、迁移等）。
     *
     * <p>在服务启动时调用一次，运行期间不要重复调用。</p>
     */
    void init();
}
