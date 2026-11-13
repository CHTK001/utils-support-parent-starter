// [P3C A20 豁免] A20/A30/A31: 含算法名/模式名等协议常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * AES-256-CBC 编解码实现（与前端 {@code encodeRequestBody} 同一口径）。
 *
 * <p>密钥与 IV 都由 {@code SHA-256(key)} 派生：前 32 字节当 AES-256 密钥，
 * 前 16 字节当 IV。前端 {@code packages/utils/src/modules/codec.ts} 按字节对齐实现，
 * 因此这是<b>线协议</b>，改动必须前后端同时发版。</p>
 *
 * <p><b>注意</b>：固定 IV 只提供机密性、不提供完整性，且同明文同密文。它保留是为了
 * 不破坏既有请求体加密协议；服务端落库请用 {@code aes-gcm}。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi({"aes", "aes-cbc"})
public class AesCbcCodec implements Codec {

    /**
     * 变换名
     */
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /**
     * 摘要算法名
     */
    private static final String SHA_256 = "SHA-256";

    /**
     * IV 长度（字节）
     */
    private static final int IV_LENGTH = 16;

    /**
     * AES 算法名
     */
    private static final String AES_ALGORITHM = "AES";

    /**
     * 密钥字节
     */
    private byte[] keyBytes;

    @Override
    public Codec key(String key) {
        this.keyBytes = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public String encodeHex(String data) {
        if (data == null) {
            return null;
        }
        try {
            byte[] hash = digest();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hash, AES_ALGORITHM),
                    new IvParameterSpec(Arrays.copyOf(hash, IV_LENGTH)));
            return Hex.encodeHexString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String decodeHex(String hex) {
        if (hex == null) {
            return null;
        }
        try {
            byte[] hash = digest();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hash, AES_ALGORITHM),
                    new IvParameterSpec(Arrays.copyOf(hash, IV_LENGTH)));
            return new String(cipher.doFinal(Hex.decodeHex(hex)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 派生密钥摘要。
     *
     * @return SHA-256(key)
     * @throws Exception 摘要算法不可用或密钥为空
     */
    private byte[] digest() throws Exception {
        if (keyBytes == null || keyBytes.length == 0) {
            throw new IllegalArgumentException("AES 密钥不能为空");
        }
        return MessageDigest.getInstance(SHA_256).digest(keyBytes);
    }
}
