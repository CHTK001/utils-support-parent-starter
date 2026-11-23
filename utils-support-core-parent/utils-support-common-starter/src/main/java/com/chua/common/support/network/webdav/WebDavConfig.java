package com.chua.common.support.network.webdav;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebDAV 服务器配置。
 *
 * <p>包含监听地址、根目录、用户凭据表、匿名访问开关等。
 * 与 {@link com.chua.common.support.network.ftp.FtpConfig} 同风格：
 * 通过 {@link #builder()} 链式构建，{@link #defaults()} 返回默认配置。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class WebDavConfig {

    /**
     * 默认监听端口
     */
    public static final int DEFAULT_PORT = 8080;

    /**
     * 监听主机
     */
    private String host = "0.0.0.0";

    /**
     * 监听端口
     */
    private int port = DEFAULT_PORT;

    /**
     * 根目录（客户端可访问的根路径）
     */
    private File homeDirectory = new File("webdav-home");

    /**
     * 用户凭据表（用户名 -&gt; 密码）。非空时启用严格认证；
     * 为空时允许任意用户名+密码登录（仅建议开发/测试环境使用）。
     */
    private final Map<String, String> users = new LinkedHashMap<>();

    /**
     * 是否启用匿名访问（匿名用户只读）
     */
    private boolean anonymousEnabled = true;

    /**
     * 是否允许匿名写入（PUT/MKCOL/DELETE/COPY/MOVE/PROPPATCH/LOCK）
     */
    private boolean allowAnonymousWrite = false;

    /**
     * 读超时（秒）
     */
    private int readTimeout = 30;

    /**
     * 写超时（秒）
     */
    private int writeTimeout = 30;

    /**
     * HTTP 传输层类型（SPI 扩展名，默认 jdk）。
     *
     * <p>对应 {@code Server} 的 SPI 实现：{@code jdk} = JDK 内置 HttpServer，
     * 亦可按 SPI 注册名切换其它 HTTP 传输实现。</p>
     */
    private String serverType = "jdk";

    /**
     * 创建使用默认配置的 WebDAV 配置。
     *
     * @return 默认配置
     */
    public static WebDavConfig defaults() {
        return builder().build();
    }

    /**
     * 创建配置构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public File getHomeDirectory() {
        return homeDirectory;
    }

    public void setHomeDirectory(File homeDirectory) {
        this.homeDirectory = homeDirectory;
    }

    /**
     * get Users
     * @return 用户凭据表（用户名 -&gt; 密码）
     */
    public Map<String, String> getUsers() {
        return users;
    }

    public boolean isAnonymousEnabled() {
        return anonymousEnabled;
    }

    public void setAnonymousEnabled(boolean anonymousEnabled) {
        this.anonymousEnabled = anonymousEnabled;
    }

    public boolean isAllowAnonymousWrite() {
        return allowAnonymousWrite;
    }

    public void setAllowAnonymousWrite(boolean allowAnonymousWrite) {
        this.allowAnonymousWrite = allowAnonymousWrite;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(int writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    /**
     * get ServerType
     * @return HTTP 传输层类型（SPI 扩展名）
     */
    public String getServerType() {
        return serverType;
    }

    /**
     * set ServerType
     * @param serverType HTTP 传输层类型（SPI 扩展名，如 jdk）
     */
    public void setServerType(String serverType) {
        this.serverType = serverType;
    }

    /**
     * WebDAV 配置构建器。
     */
    public static class Builder {

        /**
         * 内部配置对象
         */
        private final WebDavConfig config = new WebDavConfig();

        /**
         * 设置监听主机。
         * @param host 主机地址
         * @return Builder 对象
         */
        public Builder host(String host) {
            config.host = host;
            return this;
        }

        /**
         * 设置监听端口。
         * @param port 端口
         * @return Builder 对象
         */
        public Builder port(int port) {
            config.port = port;
            return this;
        }

        /**
         * 设置根目录。
         * @param homeDirectory 根目录
         * @return Builder 对象
         */
        public Builder homeDirectory(File homeDirectory) {
            config.homeDirectory = homeDirectory;
            return this;
        }

        /**
         * 添加用户凭据（用户表非空时启用严格认证）。
         * @param username 用户名
         * @param password 密码
         * @return Builder 对象
         */
        public Builder user(String username, String password) {
            config.users.put(username, password);
            return this;
        }

        /**
         * 设置是否启用匿名访问。
         * @param anonymousEnabled 布尔值
         * @return Builder 对象
         */
        public Builder anonymousEnabled(boolean anonymousEnabled) {
            config.anonymousEnabled = anonymousEnabled;
            return this;
        }

        /**
         * 设置是否允许匿名写入。
         * @param allowAnonymousWrite 布尔值
         * @return Builder 对象
         */
        public Builder allowAnonymousWrite(boolean allowAnonymousWrite) {
            config.allowAnonymousWrite = allowAnonymousWrite;
            return this;
        }

        /**
         * 设置读超时。
         * @param readTimeout 秒
         * @return Builder 对象
         */
        public Builder readTimeout(int readTimeout) {
            config.readTimeout = readTimeout;
            return this;
        }

        /**
         * 设置写超时。
         * @param writeTimeout 秒
         * @return Builder 对象
         */
        public Builder writeTimeout(int writeTimeout) {
            config.writeTimeout = writeTimeout;
            return this;
        }

        /**
         * 设置 HTTP 传输层类型（SPI 扩展名，默认 jdk）。
         * @param serverType 传输层类型，如 jdk
         * @return Builder 对象
         */
        public Builder serverType(String serverType) {
            config.serverType = serverType;
            return this;
        }

        /**
         * 构建配置。
         * @return WebDAV 配置
         */
        public WebDavConfig build() {
            return config;
        }
    }
}
