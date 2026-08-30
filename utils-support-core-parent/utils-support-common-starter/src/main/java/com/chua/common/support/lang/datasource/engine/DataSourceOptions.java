package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.network.tunnel.Tunnel;

import java.util.Map;

/**
 * 数据库数据源添加选项。
 *
 * <p>封装 JDBC 数据源的连接信息，用于替代多参数构造函数，
 * 同时支持可选的隧道穿透配置。</p>
 *
 * @param name          数据源名称
 * @param host          主机地址
 * @param port          端口号
 * @param database      数据库名
 * @param username      用户名
 * @param password      密码
 * @param tunnel        隧道（可为 null 表示直连）
 * @param jtdsUrlParams jTDS 驱动的额外 URL 参数（如 {@code selectMethod}、{@code domain}），可为 null
 * @author CH
 * @since 4.0.0.42
 */
public record DataSourceOptions(
        String name,
        String host,
        int port,
        String database,
        String username,
        String password,
        Tunnel tunnel,
        Map<String, String> jtdsUrlParams
) {
    public DataSourceOptions(String name, String host, int port, String database, String username, String password, Tunnel tunnel) {
        this(name, host, port, database, username, password, tunnel, null);
    }

    /**
     * 获取 jTDS URL 参数，null 或空时返回空 Map。
     */
    public Map<String, String> jtdsUrlParams() {
        return jtdsUrlParams != null ? jtdsUrlParams : Map.of();
    }
}
