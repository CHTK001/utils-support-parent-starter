package com.chua.common.support.network.protocol;


/**
 * 客户端设置，包含连接参数。
 *
 * @author CH
 * @since 2026/07/27
 */
public class ClientSetting {

    /** 主机 */
    private final String host;
    /** 端口 */
    private final int port;
    /** Username */
    private final String username;
    /** 密码 */
    private final String password;
    /** Connect超时 */
    private final long connectTimeout;
    /** Read超时 */
    private final long readTimeout;
    /** Write超时 */
    private final long writeTimeout;

    /**
     * 创建 ClientSetting 实例
     * @param b b
     */
    private ClientSetting(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.writeTimeout = b.writeTimeout;
    }

    /**
     * 获取主机地址。
     *
     * @return 主机地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取端口号。
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 获取密码。
     *
     * @return 密码
     */
    public String getPassword() {
        return password;
    }

    /**
     * 获取连接超时（毫秒）。
     *
     * @return 连接超时
     */
    public long getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 获取读取超时（毫秒）。
     *
     * @return 读取超时
     */
    public long getReadTimeout() {
        return readTimeout;
    }

    /**
     * 获取写入超时（毫秒）。
     *
     * @return 写入超时
     */
    public long getWriteTimeout() {
        return writeTimeout;
    }

    /**
     * 创建新的 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构建器类。
     */
    public static class Builder {
        /** 主机 */
        private String host;
        /** 端口 */
        private int port = 5985;
        /** Username */
        private String username;
        /** 密码 */
        private String password;
        /** Connect超时 */
        private long connectTimeout = 15_000;
        /** Read超时 */
        private long readTimeout = 30_000;
        /** Write超时 */
        private long writeTimeout = 30_000;

        /** Host */
        public Builder host(String h) { host = h; return this; }
        /** Port */
        public Builder port(int p) { port = p; return this; }
        /** Username */
        public Builder username(String u) { username = u; return this; }
        /** Password */
        public Builder password(String p) { password = p; return this; }
        /** 连接Timeout */
        public Builder connectTimeout(long t) { connectTimeout = t; return this; }
        /** 读取Timeout */
        public Builder readTimeout(long t) { readTimeout = t; return this; }
        /** 写入Timeout */
        public Builder writeTimeout(long t) { writeTimeout = t; return this; }

        /** 构建 */
        public ClientSetting build() {
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (username == null || username.isEmpty()) {
                throw new IllegalArgumentException("username 不能为空");
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("password 不能为空");
            }
            return new ClientSetting(this);
        }
    }
}
