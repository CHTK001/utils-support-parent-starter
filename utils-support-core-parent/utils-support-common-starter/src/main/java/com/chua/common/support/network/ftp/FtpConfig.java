package com.chua.common.support.network.ftp;

import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.util.List;

/**
 * FTP 服务器配置。
 *
 * <p>包含控制端口、数据端口范围、认证、匿名访问、SSL/TLS 等配置。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("ftp-config")
public class FtpConfig {

    /** 默认 FTP 控制端口 */
    public static final int DEFAULT_CONTROL_PORT = 21;

    /** 默认匿名访问用户名 */
    public static final String ANONYMOUS_USER = "anonymous";

    /** 控制端口 */
    private int controlPort = DEFAULT_CONTROL_PORT;

    /** 监听主机 */
    private String host = "0.0.0.0";

    /** 根目录（客户端可访问的根路径） */
    private File homeDirectory = new File("ftp-home");

    /** 是否启用匿名访问 */
    private boolean anonymousEnabled = true;

    /** 匿名用户是否可写 */
    private boolean anonymousWriteEnabled = false;

    /** 最大并发连接数，0 表示不限制 */
    private int maxConnections = 0;

    /** 被动模式端口范围起始（含） */
    private int passivePortMin = 49152;

    /** 被动模式端口范围结束（含） */
    private int passivePortMax = 65535;

    /** 是否启用被动模式（默认 true，主动模式兼容性差） */
    private boolean passiveMode = true;

    /** 是否开启 SSL/TLS（FTPS 模式） */
    private boolean sslEnabled = false;

    /** SSL 证书文件路径（PEM 格式） */
    private String certPath;

    /** SSL 私钥文件路径（PEM 格式） */
    private String keyPath;

    /** SSL 私钥密码 */
    private String keyPassword;

    /** 是否信任所有证书（开发环境） */
    private boolean trustAll = false;

    /** 一键自签（自动启用 SSL 并生成自签名证书） */
    private boolean selfSignedAuto = false;

    /** 控制连接超时（秒），0 表示不超时 */
    private int controlTimeout = 300;

    /** 数据连接超时（秒） */
    private int dataTimeout = 30;

    /** 是否允许匿名上传 */
    private boolean allowAnonymousUpload = false;

    /** 允许的匿名上传目录（相对于 homeDirectory） */
    private String anonymousUploadDir = "upload";

    /** 是否允许被动模式（安全考虑可禁用） */
    private boolean allowPassiveMode = true;

    /** 是否允许主动模式 */
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

        public Builder controlPort(int controlPort) {
            config.controlPort = controlPort;
            return this;
        }

        public Builder host(String host) {
            config.host = host;
            return this;
        }

        public Builder homeDirectory(File homeDirectory) {
            config.homeDirectory = homeDirectory;
            return this;
        }

        public Builder homeDirectory(String homeDirectory) {
            config.homeDirectory = new File(homeDirectory);
            return this;
        }

        public Builder anonymousEnabled(boolean anonymousEnabled) {
            config.anonymousEnabled = anonymousEnabled;
            return this;
        }

        public Builder anonymousWriteEnabled(boolean anonymousWriteEnabled) {
            config.anonymousWriteEnabled = anonymousWriteEnabled;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            config.maxConnections = maxConnections;
            return this;
        }

        public Builder passivePortRange(int min, int max) {
            config.passivePortMin = min;
            config.passivePortMax = max;
            return this;
        }

        public Builder passiveMode(boolean passiveMode) {
            config.passiveMode = passiveMode;
            return this;
        }

        public Builder sslEnabled(boolean sslEnabled) {
            config.sslEnabled = sslEnabled;
            return this;
        }

        public Builder certPath(String certPath) {
            config.certPath = certPath;
            return this;
        }

        public Builder keyPath(String keyPath) {
            config.keyPath = keyPath;
            return this;
        }

        public Builder keyPassword(String keyPassword) {
            config.keyPassword = keyPassword;
            return this;
        }

        public Builder trustAll(boolean trustAll) {
            config.trustAll = trustAll;
            return this;
        }

        public Builder selfSignedAuto(boolean selfSignedAuto) {
            config.selfSignedAuto = selfSignedAuto;
            return this;
        }

        public Builder controlTimeout(int controlTimeout) {
            config.controlTimeout = controlTimeout;
            return this;
        }

        public Builder dataTimeout(int dataTimeout) {
            config.dataTimeout = dataTimeout;
            return this;
        }

        public Builder allowAnonymousUpload(boolean allowAnonymousUpload) {
            config.allowAnonymousUpload = allowAnonymousUpload;
            return this;
        }

        public Builder anonymousUploadDir(String anonymousUploadDir) {
            config.anonymousUploadDir = anonymousUploadDir;
            return this;
        }

        public Builder allowPassiveMode(boolean allowPassiveMode) {
            config.allowPassiveMode = allowPassiveMode;
            return this;
        }

        public Builder allowActiveMode(boolean allowActiveMode) {
            config.allowActiveMode = allowActiveMode;
            return this;
        }

        public FtpConfig build() {
            return config;
        }
    }

    // ======================== Getters & Setters ========================

    public int getControlPort() { return controlPort; }
    public void setControlPort(int controlPort) { this.controlPort = controlPort; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public File getHomeDirectory() { return homeDirectory; }
    public void setHomeDirectory(File homeDirectory) { this.homeDirectory = homeDirectory; }

    public boolean isAnonymousEnabled() { return anonymousEnabled; }
    public void setAnonymousEnabled(boolean anonymousEnabled) { this.anonymousEnabled = anonymousEnabled; }

    public boolean isAnonymousWriteEnabled() { return anonymousWriteEnabled; }
    public void setAnonymousWriteEnabled(boolean anonymousWriteEnabled) { this.anonymousWriteEnabled = anonymousWriteEnabled; }

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

    public int getPassivePortMin() { return passivePortMin; }
    public void setPassivePortMin(int passivePortMin) { this.passivePortMin = passivePortMin; }

    public int getPassivePortMax() { return passivePortMax; }
    public void setPassivePortMax(int passivePortMax) { this.passivePortMax = passivePortMax; }

    public boolean isPassiveMode() { return passiveMode; }
    public void setPassiveMode(boolean passiveMode) { this.passiveMode = passiveMode; }

    public boolean isSslEnabled() { return sslEnabled; }
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }

    public String getCertPath() { return certPath; }
    public void setCertPath(String certPath) { this.certPath = certPath; }

    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath; }

    public String getKeyPassword() { return keyPassword; }
    public void setKeyPassword(String keyPassword) { this.keyPassword = keyPassword; }

    public boolean isTrustAll() { return trustAll; }
    public void setTrustAll(boolean trustAll) { this.trustAll = trustAll; }

    public boolean isSelfSignedAuto() { return selfSignedAuto; }
    public void setSelfSignedAuto(boolean selfSignedAuto) { this.selfSignedAuto = selfSignedAuto; }

    public int getControlTimeout() { return controlTimeout; }
    public void setControlTimeout(int controlTimeout) { this.controlTimeout = controlTimeout; }

    public int getDataTimeout() { return dataTimeout; }
    public void setDataTimeout(int dataTimeout) { this.dataTimeout = dataTimeout; }

    public boolean isAllowAnonymousUpload() { return allowAnonymousUpload; }
    public void setAllowAnonymousUpload(boolean allowAnonymousUpload) { this.allowAnonymousUpload = allowAnonymousUpload; }

    public String getAnonymousUploadDir() { return anonymousUploadDir; }
    public void setAnonymousUploadDir(String anonymousUploadDir) { this.anonymousUploadDir = anonymousUploadDir; }

    public boolean isAllowPassiveMode() { return allowPassiveMode; }
    public void setAllowPassiveMode(boolean allowPassiveMode) { this.allowPassiveMode = allowPassiveMode; }

    public boolean isAllowActiveMode() { return allowActiveMode; }
    public void setAllowActiveMode(boolean allowActiveMode) { this.allowActiveMode = allowActiveMode; }
}
