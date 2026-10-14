package com.chua.datasource.support.middleware;

/**
 * 中间件连接参数：宿主从「数据源管理」的数据源行解析后透传给实现方。
 *
 * <p>实现方<b>不要</b>自己去查库或依赖宿主服务 —— 拿到的就是这个不可变对象，
 * 这样实现方可以在 OSGi bundle 里独立运行（宿主的 Spring 容器对 bundle 不可见）。</p>
 *
 * <p>字段口径与数据源行一致：{@code host}/{@code port} 为服务地址，
 * {@code database} 为默认逻辑库（MongoDB 的鉴权库、InfluxDB 的默认 Bucket 等），
 * {@code username}/{@code password} 为凭据，留空用户名表示无认证。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareConnectRequest {

    /** 数据源行主键，实现方可用于日志与缓存键 */
    private final long datasourceId;

    /** 数据源名称（展示用） */
    private final String datasourceName;

    /** 服务地址 */
    private final String host;

    /** 服务端口 */
    private final int port;

    /** 默认逻辑库 / 桶 / 主题（视中间件而定），可为空 */
    private final String database;

    /** 账号，空表示无认证 */
    private final String username;

    /** 密码 */
    private final String password;

    /**
     * 全字段构造。
     *
     * @param datasourceId   数据源主键
     * @param datasourceName 数据源名称
     * @param host           服务地址
     * @param port           服务端口
     * @param database       默认逻辑库
     * @param username       账号
     * @param password       密码
     */
    public MiddlewareConnectRequest(long datasourceId, String datasourceName, String host, int port,
                                    String database, String username, String password) {
        this.datasourceId = datasourceId;
        this.datasourceName = datasourceName;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    /**
     * 紧凑构造：不带数据源主键与名称。
     *
     * @param host     服务地址
     * @param port     服务端口
     * @param database 默认逻辑库
     * @param username 账号
     * @param password 密码
     * @return 请求对象
     */
    public static MiddlewareConnectRequest of(String host, int port, String database,
                                              String username, String password) {
        return new MiddlewareConnectRequest(0L, "", host, port, database, username, password);
    }

    /**
     * @return 数据源主键
     */
    public long datasourceId() {
        return datasourceId;
    }

    /**
     * @return 数据源名称
     */
    public String datasourceName() {
        return datasourceName;
    }

    /**
     * @return 服务地址，永不为 {@code null}
     */
    public String host() {
        return host == null ? "" : host;
    }

    /**
     * @return 服务端口
     */
    public int port() {
        return port;
    }

    /**
     * @return 默认逻辑库，未配置时为空串
     */
    public String database() {
        return database == null ? "" : database;
    }

    /**
     * @return 账号，无认证时为空串
     */
    public String username() {
        return username == null ? "" : username;
    }

    /**
     * @return 密码，未配置时为空串
     */
    public String password() {
        return password == null ? "" : password;
    }

    /**
     * 地址串，日志用。
     *
     * @return {@code host:port}
     */
    public String address() {
        return host() + ":" + port;
    }

    @Override
    public String toString() {
        return "MiddlewareConnectRequest{id=" + datasourceId + ", address=" + address()
                + ", database=" + database() + ", username=" + username() + '}';
    }

    // ==== Jackson getters (generated) ====
    // 本类用 fluent 访问器（xxx()）对外，Jackson 默认只认 getXxx()/isXxx()，
    // 因此这里补一层 getter 别名；既有调用方不受影响。

    /**
     * Jackson getter，对应 {@link #datasourceId()}。
     *
     * @return datasourceId
     */
    public long getDatasourceId() {
        return datasourceId();
    }

    /**
     * Jackson getter，对应 {@link #datasourceName()}。
     *
     * @return datasourceName
     */
    public String getDatasourceName() {
        return datasourceName();
    }

    /**
     * Jackson getter，对应 {@link #host()}。
     *
     * @return host
     */
    public String getHost() {
        return host();
    }

    /**
     * Jackson getter，对应 {@link #port()}。
     *
     * @return port
     */
    public int getPort() {
        return port();
    }

    /**
     * Jackson getter，对应 {@link #database()}。
     *
     * @return database
     */
    public String getDatabase() {
        return database();
    }

    /**
     * Jackson getter，对应 {@link #username()}。
     *
     * @return username
     */
    public String getUsername() {
        return username();
    }

    /**
     * Jackson getter，对应 {@link #password()}。
     *
     * @return password
     */
    public String getPassword() {
        return password();
    }

    /**
     * Jackson getter，对应 {@link #address()}。
     *
     * @return address
     */
    public String getAddress() {
        return address();
    }
}
