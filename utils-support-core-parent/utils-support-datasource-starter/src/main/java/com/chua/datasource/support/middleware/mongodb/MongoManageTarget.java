package com.chua.datasource.support.middleware.mongodb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * MongoDB 管理侧连接目标：由宿主（数据源台账）解析后传入。
 *
 * <p>实现方<b>不要</b>自己去查库或依赖宿主的 Spring 容器 —— 拿到的就是这份不可变参数，
 * 因此实现可以直接跑在 OSGi bundle 里。</p>
 *
 * @param datasourceId 数据源主键，即席探测时为 0
 * @param name         数据源名称（仅用于失败文案）
 * @param host         主机地址
 * @param port         端口，缺省 27017
 * @param database     默认逻辑库，同时作为认证库；空按 {@code admin}
 * @param username     账号，空表示无认证
 * @param password     口令
 * @param versionToken 数据源配置版本标识（如更新时间），用于配置变更后换新会话
 * @author CH
 * @since 4.0.0.51
 */
public record MongoManageTarget(long datasourceId,
                               String name,
                               String host,
                               int port,
                               String database,
                               String username,
                               String password,
                               String versionToken) {

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 27017;

    /**
     * 默认逻辑库兼认证库
     */
    public static final String DEFAULT_DATABASE = "admin";

    /**
     * 规整空值，避免实现方到处判空。
     */
    public MongoManageTarget {
        name = name == null ? "" : name;
        host = host == null ? "" : host.trim();
        database = database == null ? "" : database.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        versionToken = versionToken == null ? "" : versionToken;
    }

    /**
     * 实际端口：未填写或非法时取默认 27017。
     *
     * @return 端口号
     */
    public int effectivePort() {
        return port <= 0 ? DEFAULT_PORT : port;
    }

    /**
     * 实际逻辑库：未填写时取默认 {@code admin}。
     *
     * @return 逻辑库名
     */
    public String effectiveDatabase() {
        return database.isEmpty() ? DEFAULT_DATABASE : database;
    }

    /**
     * 官方驱动的连接串。
     *
     * <p>认证库固定与默认逻辑库一致（数据源行的 {@code database} 即认证库），
     * 口令按 RFC 3986 编码（空格写 {@code %20} 而不是 {@code +}）。</p>
     *
     * @return 连接串
     * @throws IllegalStateException host 为空
     */
    public String connectionString() {
        if (host.isEmpty()) {
            throw new IllegalStateException("数据源 " + name + " 未填写主机地址");
        }
        String db = effectiveDatabase();
        StringBuilder value = new StringBuilder("mongodb://");
        if (!username.isEmpty()) {
            value.append(encode(username)).append(':').append(encode(password)).append('@');
        }
        value.append(host).append(':').append(effectivePort()).append('/').append(db);
        if (!username.isEmpty()) {
            value.append("?authSource=").append(db);
        }
        return value.toString();
    }

    /**
     * 连接串用户信息段 URL 编码。
     *
     * @param text 原文
     * @return 编码结果
     */
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
