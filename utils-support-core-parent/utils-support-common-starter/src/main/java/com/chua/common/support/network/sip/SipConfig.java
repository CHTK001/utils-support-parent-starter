package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;

/**
 * SIP 传输配置。
 *
 * <p>包含压缩、加密、数据平面模式等配置开关。<br>
 * 实际使用时通过 {@link com.chua.common.support.spi.ServiceProvider} 加载具体实现。</p>
 *
 * <p>数据平面模式：{@code relay}（服务器中转，默认）或 {@code direct}（直连，需双方均有公网地址）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sip-config")
public class SipConfig {

    /** 默认端口 */
    public static final int DEFAULT_PORT = 19460;
    /** 默认 TCP 端口 */
    public static final int DEFAULT_TCP_PORT = 19461;

    /** 默认主机地址 */
    public static final String DEFAULT_HOST = "0.0.0.0";

    /** 默认认证令牌 */
    public static final String DEFAULT_TOKEN = "chua-sip-default-token";

    /** 数据平面中继模式 */
    public static final String MODE_RELAY = "relay";

    /** 数据平面直连模式 */
    public static final String MODE_DIRECT = "direct";

    /** 数据平面自动模式：先尝试直连，失败自动回退到中继 */
    public static final String MODE_AUTO = "auto";

    /** 是否开启压缩，默认开启 */
    private boolean compress;

    /** 是否开启加密，默认关闭 */
    private boolean encrypt;

    /**
     * 压缩器 SPI 名称，对应 {@link SipStreamCompressor} 的 SPI 标识
     */
    private String compressor;

    /**
     * 加密器 SPI 名称，对应 {@link com.chua.common.support.network.sip.cipher.SipCipher} 的 SPI 标识
     */
    private String cipher;

    /** 认证令牌 */
    private String token;

    /** 监听主机 */
    private String host;

    /** 监听端口 */
    private int port;

    /**
     * 数据平面模式：relay（中继）或 direct（直连），默认 relay
     */
    private String dataPlaneMode;

    /** 认证最小帧间隔（纳秒），用于限速 */
    private long minFrameIntervalNs; // 10ms

    /** 每分钟最大认证请求数（防暴力破解） */
    private int maxAuthPerIpPerMin;

    /** Token 文件路径（可选，优先于 token 字段） */
    private String tokenFile;

    /**
     * 创建默认配置。
     * 默认 KCP 监听端口
     */
    public static final int DEFAULT_KCP_PORT = 19461;

    /**
     * 默认数据平面监听端口
     */
    public static final int DEFAULT_DATA_PORT = 19462;

    /**
     * 是否启用 TCP 传输
     */
    private boolean tcpEnabled;

    /**
     * TCP 监听端口
     */
    private int tcpPort;

    /**
     * 是否启用 KCP 传输
     */
    private boolean kcpEnabled;

    /**
     * KCP 监听端口
     */
    private int kcpPort;

    /**
     * 是否启用 frp 数据平面
     */
    private boolean dataPlaneEnabled;

    /**
     * 数据平面监听端口
     */
    private int dataPort;

    /**
     * 创建一份独立的默认配置。
     *
     * @return 新的默认配置实例
     */
    public static SipConfig defaults() {
        return builder().build();
    }

    /**
     * 创建配置构建器。
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 是否Compress。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isCompress() {
        return compress;
    }

    /**
     * 设置Compress。
     *
     * @param compress compress（布尔开关）
     */
    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    /**
     * 是否Encrypt。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isEncrypt() {
        return encrypt;
    }

    /**
     * 设置Encrypt。
     *
     * @param encrypt encrypt（布尔开关）
     */
    public void setEncrypt(boolean encrypt) {
        this.encrypt = encrypt;
    }

    /**
     * 获取Compressor。
     *
     * @return 结果字符串
     */
    public String getCompressor() {
        return compressor;
    }

    /**
     * 设置Compressor。
     *
     * @param compressor 方法入参 compressor
     */
    public void setCompressor(String compressor) {
        this.compressor = compressor;
    }

    /**
     * 获取Cipher。
     *
     * @return 结果字符串
     */
    public String getCipher() {
        return cipher;
    }

    /**
     * 设置Cipher。
     *
     * @param cipher 方法入参 cipher
     */
    public void setCipher(String cipher) {
        this.cipher = cipher;
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
     * 获取数据Plane模式。
     *
     * @return 结果字符串
     */
    public String getDataPlaneMode() {
        return dataPlaneMode;
    }

    /**
     * 设置数据Plane模式。
     *
     * @param dataPlaneMode 数据Plane模式，不允许为 null
     */
    public void setDataPlaneMode(String dataPlaneMode) {
        this.dataPlaneMode = dataPlaneMode;
    }

    /**
     * 获取最小值Frame间隔Ns。
     *
     * @return 结果数值
     */
    public long getMinFrameIntervalNs() {
        return minFrameIntervalNs;
    }

    /**
     * 设置最小值Frame间隔Ns。
     *
     * @param minFrameIntervalNs 最小值Frame间隔Ns，不允许为 null
     */
    public void setMinFrameIntervalNs(long minFrameIntervalNs) {
        this.minFrameIntervalNs = minFrameIntervalNs;
    }

    /**
     * 获取最大值AuthPerIPPer最小值。
     *
     * @return 结果数值
     */
    public int getMaxAuthPerIpPerMin() {
        return maxAuthPerIpPerMin;
    }

    /**
     * 设置最大值AuthPerIPPer最小值。
     *
     * @param maxAuthPerIpPerMin 最大值AuthPerIPPer最小值，不允许为 null
     */
    public void setMaxAuthPerIpPerMin(int maxAuthPerIpPerMin) {
        this.maxAuthPerIpPerMin = maxAuthPerIpPerMin;
    }

    /**
     * 获取令牌文件。
     *
     * @return 结果字符串
     */
    public String getTokenFile() {
        return tokenFile;
    }

    /**
     * 设置令牌文件。
     *
     * @param tokenFile 令牌文件，不允许为 null
     */
    public void setTokenFile(String tokenFile) {
        this.tokenFile = tokenFile;
    }

    /**
     * 配置构建器。
     */
    public static class Builder {
        private final SipConfig config = new SipConfig();

        public Builder compress(boolean compress) {
            config.compress = compress;
            return this;
        }

        public Builder encrypt(boolean encrypt) {
            config.encrypt = encrypt;
            return this;
        }

        public Builder compressor(String compressor) {
            config.compressor = compressor;
            return this;
        }

        public Builder cipher(String cipher) {
            config.cipher = cipher;
            return this;
        }

        public Builder token(String token) {
            config.token = token;
            return this;
        }

        public Builder host(String host) {
            config.host = host;
            return this;
        }

        public Builder port(int port) {
            config.port = port;
            return this;
        }

        public Builder dataPlaneMode(String dataPlaneMode) {
            config.dataPlaneMode = dataPlaneMode;
            return this;
        }

        public Builder minFrameIntervalNs(long minFrameIntervalNs) {
            config.minFrameIntervalNs = minFrameIntervalNs;
            return this;
        }

        public Builder maxAuthPerIpPerMin(int maxAuthPerIpPerMin) {
            config.maxAuthPerIpPerMin = maxAuthPerIpPerMin;
            return this;
        }

        public Builder tokenFile(String tokenFile) {
            config.tokenFile = tokenFile;
            return this;
        }

        public SipConfig build() {
            return config;
        }
    }
}
