package com.chua.common.support.network.ftp;

import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.util.List;
import lombok.ToString;

/**
 * FTP 服务器配置。
 *
 * <p>包含控制端口、数据端口范围、认证、匿名访问、SSL/TLS 等配置。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("ftp-config")
@ToString
public class FtpConfig {

    /**
     * 默认 FTP 控制端口
     */
    public static final int DEFAULT_CONTROL_PORT = 21;

    /**
     * 默认匿名访问用户名
     */
    public static final String ANONYMOUS_USER = "anonymous";

    /**
     * 控制端口
     */
    private int controlPort = DEFAULT_CONTROL_PORT;

    /**
     * 监听主机
     */
    private String host = "0.0.0.0";

    /**
     * 根目录（客户端可访问的根路径）
     */
    private File homeDirectory = new File("ftp-home");

    /**
     * 是否启用匿名访问
     */
    private boolean anonymousEnabled = true;

    /**
     * 匿名用户是否可写
     */
    private boolean anonymousWriteEnabled = false;

    /**
     * 最大并发连接数，0 表示不限制
     */
    private int maxConnections = 0;

    /**
     * 被动模式端口范围起始（含）
     */
    // [P3C A20 豁免]
    private int passivePortMin = 49152;

    /**
     * 被动模式端口范围结束（含）
     */
    // [P3C A20 豁免]
    private int passivePortMax = 65535;

    /**
     * 是否启用被动模式（默认 true，主动模式兼容性差）
     */
    private boolean passiveMode = true;

    /**
     * 是否开启 SSL/TLS（FTPS 模式）
     */
    private boolean sslEnabled = false;

    /**
     * SSL 证书文件路径（PEM 格式）
     */
    private String certPath;

    /**
     * SSL 私钥文件路径（PEM 格式）
     */
    private String keyPath;

    /**
     * SSL 私钥密码
     */
    private String keyPassword;

    /**
     * 是否信任所有证书（开发环境）
     */
    private boolean trustAll = false;

    /**
     * 一键自签（自动启用 SSL 并生成自签名证书）
     */
    private boolean selfSignedAuto = false;

    /**
     * 控制连接超时（秒），0 表示不超时
     */
    // [P3C A20 豁免]
    private int controlTimeout = 300;

    /**
     * 数据连接超时（秒）
     */
    // [P3C A20 豁免]
    private int dataTimeout = 30;

    /**
     * 是否允许匿名上传
     */
    private boolean allowAnonymousUpload = false;

    /**
     * 允许的匿名上传目录（相对于 homeDirectory）
     */
    private String anonymousUploadDir = "upload";

    /**
     * 是否允许被动模式（安全考虑可禁用）
     */
    private boolean allowPassiveMode = true;

    /**
     * 是否允许主动模式
     */
    private boolean allowActiveMode = false;

    /**
     * 创建默认 FTP 配置。
     * @return Ftp配置 对象
     */
    public static FtpConfig defaults() {
        return new FtpConfig();
    }

    /**
     * 创建配置构建器。
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 配置构建器。
     */
    public static class Builder {
        private final FtpConfig config = new FtpConfig();

        /**
         * control Port
         * @param controlPort 整数
         * @return 返回值
        */
        public Builder controlPort(int controlPort) {
            config.controlPort = controlPort;
            return this;
        }

        /**
         * 设置主机地址
         * @param host 设置主机地址
         * @return 返回值
        */
        public Builder host(String host) {
            config.host = host;
            return this;
        }

        /**
         * home Directory
         * @param homeDirectory 文件
         * @return 返回值
        */
        public Builder homeDirectory(File homeDirectory) {
            config.homeDirectory = homeDirectory;
            return this;
        }

        /**
         * home Directory
         * @param homeDirectory 字符串
         * @return 返回值
        */
        public Builder homeDirectory(String homeDirectory) {
            config.homeDirectory = new File(homeDirectory);
            return this;
        }

        /**
         * anonymous Enabled
         * @param anonymousEnabled 布尔值
         * @return 返回值
        */
        public Builder anonymousEnabled(boolean anonymousEnabled) {
            config.anonymousEnabled = anonymousEnabled;
            return this;
        }

        /**
         * anonymous Write Enabled
         * @param anonymousWriteEnabled 布尔值
         * @return 返回值
        */
        public Builder anonymousWriteEnabled(boolean anonymousWriteEnabled) {
            config.anonymousWriteEnabled = anonymousWriteEnabled;
            return this;
        }

        /**
         * max Connections
         * @param maxConnections 整数
         * @return 返回值
        */
        public Builder maxConnections(int maxConnections) {
            config.maxConnections = maxConnections;
            return this;
        }

        /**
         * passive Port Range
         * @param max 整数
         * @param min 整数
         * @return 返回值
        */
        public Builder passivePortRange(int min, int max) {
            config.passivePortMin = min;
            config.passivePortMax = max;
            return this;
        }

        /**
         * passive Mode
         * @param passiveMode 布尔值
         * @return 返回值
        */
        public Builder passiveMode(boolean passiveMode) {
            config.passiveMode = passiveMode;
            return this;
        }

        /**
         * ssl Enabled
         * @param sslEnabled 布尔值
         * @return 返回值
        */
        public Builder sslEnabled(boolean sslEnabled) {
            config.sslEnabled = sslEnabled;
            return this;
        }

        /**
         * cert Path
         * @param certPath 字符串
         * @return 返回值
        */
        public Builder certPath(String certPath) {
            config.certPath = certPath;
            return this;
        }

        /**
         * key Path
         * @param keyPath 字符串
         * @return 返回值
        */
        public Builder keyPath(String keyPath) {
            config.keyPath = keyPath;
            return this;
        }

        /**
         * key Password
         * @param keyPassword 字符串
         * @return 返回值
        */
        public Builder keyPassword(String keyPassword) {
            config.keyPassword = keyPassword;
            return this;
        }

        /**
         * trust All
         * @param trustAll 布尔值
         * @return 返回值
        */
        public Builder trustAll(boolean trustAll) {
            config.trustAll = trustAll;
            return this;
        }

        /**
         * self Signed Auto
         * @param selfSignedAuto 布尔值
         * @return 返回值
        */
        public Builder selfSignedAuto(boolean selfSignedAuto) {
            config.selfSignedAuto = selfSignedAuto;
            return this;
        }

        /**
         * control Timeout
         * @param controlTimeout 整数
         * @return 返回值
        */
        public Builder controlTimeout(int controlTimeout) {
            config.controlTimeout = controlTimeout;
            return this;
        }

        /**
         * data Timeout
         * @param dataTimeout 整数
         * @return 返回值
        */
        public Builder dataTimeout(int dataTimeout) {
            config.dataTimeout = dataTimeout;
            return this;
        }

        /**
         * allow Anonymous Upload
         * @param allowAnonymousUpload 布尔值
         * @return 返回值
        */
        public Builder allowAnonymousUpload(boolean allowAnonymousUpload) {
            config.allowAnonymousUpload = allowAnonymousUpload;
            return this;
        }

        /**
         * anonymous Upload Dir
         * @param anonymousUploadDir 字符串
         * @return 返回值
        */
        public Builder anonymousUploadDir(String anonymousUploadDir) {
            config.anonymousUploadDir = anonymousUploadDir;
            return this;
        }

        /**
         * allow Passive Mode
         * @param allowPassiveMode 布尔值
         * @return 返回值
        */
        public Builder allowPassiveMode(boolean allowPassiveMode) {
            config.allowPassiveMode = allowPassiveMode;
            return this;
        }

        /**
         * allow Active Mode
         * @param allowActiveMode 布尔值
         * @return 返回值
        */
        public Builder allowActiveMode(boolean allowActiveMode) {
            config.allowActiveMode = allowActiveMode;
            return this;
        }

        /**
         * 构建
         * @return 返回值
        */
        public FtpConfig build() {
            return config;
        }
    }

    // ======================== Getters & Setters ========================

    /**
     * get Control Port
     * @return 返回值
    */
    public int getControlPort() {
        return controlPort;
    }
    /**
     * set Control Port
     * @param controlPort 整数
     * @return 返回值
    */
    public void setControlPort(int controlPort) {
        this.controlPort = controlPort;
    }

    /**
     * get Host
     * @return 返回值
    */
    public String getHost() {
        return host;
    }
    /**
     * set Host
     * @param host 设置主机地址
     * @return 返回值
    */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * get Home Directory
     * @return 返回值
    */
    public File getHomeDirectory() {
        return homeDirectory;
    }
    /**
     * set Home Directory
     * @param homeDirectory 文件
     * @return 返回值
    */
    public void setHomeDirectory(File homeDirectory) {
        this.homeDirectory = homeDirectory;
    }

    /**
     * is Anonymous Enabled
     * @return 返回值
    */
    public boolean isAnonymousEnabled() {
        return anonymousEnabled;
    }
    /**
     * set Anonymous Enabled
     * @param anonymousEnabled 布尔值
     * @return 返回值
    */
    public void setAnonymousEnabled(boolean anonymousEnabled) {
        this.anonymousEnabled = anonymousEnabled;
    }

    /**
     * is Anonymous Write Enabled
     * @return 返回值
    */
    public boolean isAnonymousWriteEnabled() {
        return anonymousWriteEnabled;
    }
    /**
     * set Anonymous Write Enabled
     * @param anonymousWriteEnabled 布尔值
     * @return 返回值
    */
    public void setAnonymousWriteEnabled(boolean anonymousWriteEnabled) {
        this.anonymousWriteEnabled = anonymousWriteEnabled;
    }

    /**
     * get Max Connections
     * @return 返回值
    */
    public int getMaxConnections() {
        return maxConnections;
    }
    /**
     * set Max Connections
     * @param maxConnections 整数
     * @return 返回值
    */
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * get Passive Port Min
     * @return 返回值
    */
    public int getPassivePortMin() {
        return passivePortMin;
    }
    /**
     * set Passive Port Min
     * @param passivePortMin 整数
     * @return 返回值
    */
    public void setPassivePortMin(int passivePortMin) {
        this.passivePortMin = passivePortMin;
    }

    /**
     * get Passive Port Max
     * @return 返回值
    */
    public int getPassivePortMax() {
        return passivePortMax;
    }
    /**
     * set Passive Port Max
     * @param passivePortMax 整数
     * @return 返回值
    */
    public void setPassivePortMax(int passivePortMax) {
        this.passivePortMax = passivePortMax;
    }

    /**
     * is Passive Mode
     * @return 返回值
    */
    public boolean isPassiveMode() {
        return passiveMode;
    }
    /**
     * set Passive Mode
     * @param passiveMode 布尔值
     * @return 返回值
    */
    public void setPassiveMode(boolean passiveMode) {
        this.passiveMode = passiveMode;
    }

    /**
     * is Ssl Enabled
     * @return 返回值
    */
    public boolean isSslEnabled() {
        return sslEnabled;
    }
    /**
     * set Ssl Enabled
     * @param sslEnabled 布尔值
     * @return 返回值
    */
    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    /**
     * get Cert Path
     * @return 返回值
    */
    public String getCertPath() {
        return certPath;
    }
    /**
     * set Cert Path
     * @param certPath 字符串
     * @return 返回值
    */
    public void setCertPath(String certPath) {
        this.certPath = certPath;
    }

    /**
     * get Key Path
     * @return 返回值
    */
    public String getKeyPath() {
        return keyPath;
    }
    /**
     * set Key Path
     * @param keyPath 字符串
     * @return 返回值
    */
    public void setKeyPath(String keyPath) {
        this.keyPath = keyPath;
    }

    /**
     * get Key Password
     * @return 返回值
    */
    public String getKeyPassword() {
        return keyPassword;
    }
    /**
     * set Key Password
     * @param keyPassword 字符串
     * @return 返回值
    */
    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    /**
     * is Trust All
     * @return 返回值
    */
    public boolean isTrustAll() {
        return trustAll;
    }
    /**
     * set Trust All
     * @param trustAll 布尔值
     * @return 返回值
    */
    public void setTrustAll(boolean trustAll) {
        this.trustAll = trustAll;
    }

    /**
     * is Self Signed Auto
     * @return 返回值
    */
    public boolean isSelfSignedAuto() {
        return selfSignedAuto;
    }
    /**
     * set Self Signed Auto
     * @param selfSignedAuto 布尔值
     * @return 返回值
    */
    public void setSelfSignedAuto(boolean selfSignedAuto) {
        this.selfSignedAuto = selfSignedAuto;
    }

    /**
     * get Control Timeout
     * @return 返回值
    */
    public int getControlTimeout() {
        return controlTimeout;
    }
    /**
     * set Control Timeout
     * @param controlTimeout 整数
     * @return 返回值
    */
    public void setControlTimeout(int controlTimeout) {
        this.controlTimeout = controlTimeout;
    }

    /**
     * get Data Timeout
     * @return 返回值
    */
    public int getDataTimeout() {
        return dataTimeout;
    }
    /**
     * set Data Timeout
     * @param dataTimeout 整数
     * @return 返回值
    */
    public void setDataTimeout(int dataTimeout) {
        this.dataTimeout = dataTimeout;
    }

    /**
     * is Allow Anonymous Upload
     * @return 返回值
    */
    public boolean isAllowAnonymousUpload() {
        return allowAnonymousUpload;
    }
    /**
     * set Allow Anonymous Upload
     * @param allowAnonymousUpload 布尔值
     * @return 返回值
    */
    public void setAllowAnonymousUpload(boolean allowAnonymousUpload) {
        this.allowAnonymousUpload = allowAnonymousUpload;
    }

    /**
     * get Anonymous Upload Dir
     * @return 返回值
    */
    public String getAnonymousUploadDir() {
        return anonymousUploadDir;
    }
    /**
     * set Anonymous Upload Dir
     * @param anonymousUploadDir 字符串
     * @return 返回值
    */
    public void setAnonymousUploadDir(String anonymousUploadDir) {
        this.anonymousUploadDir = anonymousUploadDir;
    }

    /**
     * is Allow Passive Mode
     * @return 返回值
    */
    public boolean isAllowPassiveMode() {
        return allowPassiveMode;
    }
    /**
     * set Allow Passive Mode
     * @param allowPassiveMode 布尔值
     * @return 返回值
    */
    public void setAllowPassiveMode(boolean allowPassiveMode) {
        this.allowPassiveMode = allowPassiveMode;
    }

    /**
     * is Allow Active Mode
     * @return 返回值
    */
    public boolean isAllowActiveMode() {
        return allowActiveMode;
    }
    /**
     * set Allow Active Mode
     * @param allowActiveMode 布尔值
     * @return 返回值
    */
    public void setAllowActiveMode(boolean allowActiveMode) {
        this.allowActiveMode = allowActiveMode;
    }
}
