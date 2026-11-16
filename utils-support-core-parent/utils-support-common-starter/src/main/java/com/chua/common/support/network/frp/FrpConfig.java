package com.chua.common.support.network.frp;

/**
 * FRP 传输配置。
 *
 * <p>仅保留 FRP 显式映射所需的核心项：认证令牌、监听主机、监听端口。
 * 实际使用时通过 {@link com.chua.common.support.spi.ServiceProvider} 加载具体实现。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class FrpConfig {

    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 19470;

    /**
     * 默认主机地址
     */
    public static final String DEFAULT_HOST = "0.0.0.0";

    /**
     * 默认认证令牌
     */
    public static final String DEFAULT_TOKEN = "chua-frp-default-token";

    /**
     * 认证令牌
     */
    private String token;

    /**
     * 监听主机
     */
    private String host;

    /**
     * 监听端口
     */
    private int port;

    /**
     * 创建一份独立的默认配置。
     *
     * @return 新的默认配置实例
     */
    public static FrpConfig defaults() {
        return builder().build();
    }

    /**
     * 创建配置构建器。
     *
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取令牌。
     *
     * @return 结果字符串
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置令牌。
     *
     * @param token 令牌，不允许为 null
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 获取主机。
     *
     * @return 结果字符串
     */
    public String getHost() {
        return host;
    }

    /**
     * 设置主机。
     *
     * @param host 主机，不允许为 null
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * 获取端口。
     *
     * @return 结果数值
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置端口。
     *
     * @param port 端口，不允许为 null
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 配置构建器。
     */
    public static class Builder {

        /**
         * 配置
         */
        private final FrpConfig config = new FrpConfig();

        /**
         * token
         *
         * @param token 字符串
         * @return 返回值
         */
        public Builder token(String token) {
            config.token = token;
            return this;
        }

        /**
         * 设置主机地址
         *
         * @param host 设置主机地址
         * @return 返回值
         */
        public Builder host(String host) {
            config.host = host;
            return this;
        }

        /**
         * 设置端口号
         *
         * @param port 设置端口号
         * @return 返回值
         */
        public Builder port(int port) {
            config.port = port;
            return this;
        }

        /**
         * 构建
         *
         * @return 返回值
         */
        public FrpConfig build() {
            return config;
        }
    }
}