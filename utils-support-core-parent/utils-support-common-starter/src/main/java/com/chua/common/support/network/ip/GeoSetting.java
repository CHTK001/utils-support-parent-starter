package com.chua.common.support.network.ip;

/**
 * IP 定位配置
 *
 * <p>定义 IP 定位数据库路径、MaxMind 许可证等配置参数。
 *
 * @author CH
 * @since 1.0.0
 */
public class GeoSetting {

    /** 默认配置 */
    public static final GeoSetting DEFAULT = new GeoSetting("", "");

    /** 数据库文件路径 */
    private final String databaseFile;

    /** MaxMind License Key（用于自动下载 GeoLite2 数据库） */
    private final String licenseKey;

    public GeoSetting(String databaseFile, String licenseKey) {
        this.databaseFile = databaseFile;
        this.licenseKey = licenseKey;
    }

    public String getDatabaseFile() { return databaseFile; }
    public String getLicenseKey() { return licenseKey; }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String databaseFile = "";
        private String licenseKey = "";

        public Builder databaseFile(String databaseFile) { this.databaseFile = databaseFile; return this; }
        public Builder licenseKey(String licenseKey) { this.licenseKey = licenseKey; return this; }
        public GeoSetting build() { return new GeoSetting(databaseFile, licenseKey); }
    }
}
