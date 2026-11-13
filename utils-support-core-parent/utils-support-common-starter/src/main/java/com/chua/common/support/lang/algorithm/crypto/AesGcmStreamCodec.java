// [P3C A20 豁免] A20/A30/A31: 含算法名/模式名等协议常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.network.crypto.AesGcmUtils;
import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 预共享口令的 AES-256-GCM 编解码（Codec 二级接口实现）。
 *
 * <p>消息级（{@link Codec}）把单条消息按一帧 AesGcmUtils 帧处理；
 * 传输级（{@link StreamCodec}）直接包一层帧式加密流。两端配置同一口令，
 * 密钥由 {@link AesGcmUtils#deriveKey(String)} 派生，无需握手。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"aes-gcm", "aes"})
public class AesGcmStreamCodec implements StreamCodec {

    /**
     * 派生后的 AES 密钥
     */
    private SecretKeySpec keySpec;

    @Override
    public StreamCodec key(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("aes-gcm 传输加密缺少口令");
        }
        this.keySpec = AesGcmUtils.deriveKey(key);
        return this;
    }

    @Override
    public String encodeHex(String data) {
        if (data == null) {
            return null;
        }
        ensureKey();
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            OutputStream encrypting = AesGcmUtils.encrypting(sink, keySpec);
            encrypting.write(data.getBytes(StandardCharsets.UTF_8));
            encrypting.flush();
            return Hex.encodeHexString(sink.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("aes-gcm 加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String decodeHex(String hex) {
        if (hex == null) {
            return null;
        }
        ensureKey();
        try {
            InputStream decrypting = AesGcmUtils.decrypting(
                    new ByteArrayInputStream(Hex.decodeHex(hex)), keySpec);
            byte[] plain = decrypting.readAllBytes();
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SecurityException("aes-gcm 解密失败（口令不匹配或数据被篡改）", e);
        }
    }

    @Override
    public Streams open(InputStream in, OutputStream out, boolean initiator) {
        ensureKey();
        return new Streams(AesGcmUtils.decrypting(in, keySpec), AesGcmUtils.encrypting(out, keySpec));
    }

    /**
     * 校验密钥已绑定。
     */
    private void ensureKey() {
        if (keySpec == null) {
            throw new IllegalStateException("aes-gcm 编解码尚未绑定口令");
        }
    }
}
