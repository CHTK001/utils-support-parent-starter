// [P3C A20 豁免] A20/A30/A31: 含算法名/模式名等协议常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM 编解码实现（落库 / 凭据存储用）。
 *
 * <p>每次加密生成 12 字节随机 IV，密文带 16 字节认证标签，输出为 {@code g1:} 前缀加
 * 十六进制（IV 在前、密文与标签在后）。认证标签使得「改一个字节也能解出半截明文」
 * 不再成立，篡改会直接抛异常。</p>
 *
 * <p>历史数据是 AES/CBC/PKCS5Padding 且 IV 由密钥摘要固定派生。为了不打断已有台账与凭据，
 * {@link #decodeHex(String)} 仍能读取这种无前缀的旧密文；新写入一律 GCM，
 * 旧 CBC 只保留读取能力。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi({"aes-gcm"})
public class AesGcmCodec implements Codec {

    /**
     * 新密文前缀（十六进制内容不会以 g 开头，前缀不会与旧数据混淆）
     */
    private static final String PREFIX = "g1:";

    /**
     * GCM 变换名
     */
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * 旧数据使用的 CBC 变换名（仅解密）
     */
    private static final String LEGACY_TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /**
     * GCM IV 长度（字节）
     */
    private static final int GCM_IV_LENGTH = 12;

    /**
     * GCM 认证标签长度（比特）
     */
    private static final int GCM_TAG_BITS = 128;

    /**
     * CBC IV 长度（字节）
     */
    private static final int CBC_IV_LENGTH = 16;

    /**
     * AES 算法名
     */
    private static final String AES_ALGORITHM = "AES";

    /**
     * 摘要算法名
     */
    private static final String SHA_256 = "SHA-256";

    /**
     * 随机数发生器
     */
    private static final SecureRandom RANDOM = new SecureRandom();

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
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, generateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Hex.encodeHexString(combined);
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
            if (hex.startsWith(PREFIX)) {
                return decryptGcm(Hex.decodeHex(hex.substring(PREFIX.length())));
            }
            return decryptLegacy(Hex.decodeHex(hex));
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密当前 GCM 密文。
     *
     * @param combined IV 与密文的拼接
     * @return 明文
     * @throws Exception 认证失败或格式非法
     */
    private String decryptGcm(byte[] combined) throws Exception {
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("GCM 密文长度不足");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, generateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH),
                StandardCharsets.UTF_8);
    }

    /**
     * 解密历史 CBC 密文（密文本身不含 IV，IV 由密钥摘要固定派生）。
     *
     * @param cipherText CBC 密文
     * @return 明文
     * @throws Exception 格式非法或密钥不匹配
     */
    private String decryptLegacy(byte[] cipherText) throws Exception {
        if (cipherText.length == 0 || cipherText.length % CBC_IV_LENGTH != 0) {
            throw new IllegalArgumentException("CBC 密文长度非法");
        }
        byte[] hash = MessageDigest.getInstance(SHA_256).digest(keyBytes);
        Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hash, AES_ALGORITHM),
                new IvParameterSpec(Arrays.copyOf(hash, CBC_IV_LENGTH)));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    /**
     * 由任意长度密钥材料派生 32 字节 AES 密钥。
     *
     * @return 密钥
     * @throws Exception 摘要算法不可用或密钥为空
     */
    private SecretKeySpec generateKey() throws Exception {
        if (keyBytes == null || keyBytes.length == 0) {
            throw new IllegalArgumentException("AES 密钥不能为空");
        }
        byte[] hash = MessageDigest.getInstance(SHA_256).digest(keyBytes);
        return new SecretKeySpec(hash, AES_ALGORITHM);
    }
}
