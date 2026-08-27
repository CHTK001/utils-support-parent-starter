package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;

/**
 * SIP 传输配置。
 *
 * <p>包含压缩、加密等数据面配置开关。<br>
 * 实际使用时通过 {@link com.chua.common.support.spi.ServiceProvider} 加载具体实现。</p>
 *
 * <p>组合顺序：socket → encrypt → compress → app</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("sip-config")
public class SipConfig {

    /** 是否开启压缩，默认开启 */
    private boolean compress = true;

    /** 是否开启加密，默认关闭 */
    private boolean encrypt = false;

    /** 压缩器 SPI 名称，对应 {@link SipStreamCompressor} 的 SPI 标识 */
    private String compressor = "gzip";

    /** 加密器 SPI 名称，对应 {@link com.chua.common.support.network.sip.cipher.SipCipher} 的 SPI 标识 */
    private String cipher = "aes-gcm";

    public boolean isCompress() {
        return compress;
    }

    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    public boolean isEncrypt() {
        return encrypt;
    }

    public void setEncrypt(boolean encrypt) {
        this.encrypt = encrypt;
    }

    public String getCompressor() {
        return compressor;
    }

    public void setCompressor(String compressor) {
        this.compressor = compressor;
    }

    public String getCipher() {
        return cipher;
    }

    public void setCipher(String cipher) {
        this.cipher = cipher;
    }
}