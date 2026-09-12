package com.chua.crypto.support.codec;

import com.chua.crypto.support.CryptoException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
* 数据加解密器（AES-256-GCM）
*
* <p>使用主密钥对业务数据进行认证加密，密文格式：
* <pre>[版本1B][随机IV 12B][密文+GCM认证标签]</pre>
*
* <p>每次加密均生成独立随机 IV；字符串 API 输出/输入为标准 Base64。
* GCM 自带完整性校验：密文被篡改或密钥不匹配时解密直接失败。
*
* <p>{@link #encryptTagged(byte[], byte[])} 系列在密文头部追加 4 字节魔数 {@code CHKJ}，
* 用于程序包加密场景（fatjar 内的 类/依赖 jar/配置条目），运行期引导器按魔数识别并透明解密。
*
* @author CH
* @since 2026-08-26
 */
public final class DataCipher {

    /**
    * 程序包加密条目魔数：Chua 键 Jar
     */
    public static final byte[] MAGIC_TAGGED = {'C', 'H', 'K', 'J'};

    /**
    * 密文格式版本号
     */
    private static final byte FORMAT_VERSION = 1;

    /**
    * IV 长度（字节）
     */
    private static final int IV_BYTES = 12;

    /**
    * GCM 认证标签长度（位）
     */
    private static final int TAG_BITS = 128;

    /**
    * 随机源
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
    * 私有构造
     */
    private DataCipher() {
    }

    /**
    * 加密字节并附加 CHKJ 魔数标记（程序包条目格式）
    *
    * @param key       主密钥（32 字节）
    * @param plaintext 明文
    * @return [CHKJ][版本][IV][密文]
     */
    public static byte[] encryptTagged(byte[] key, byte[] plaintext) {
        byte[] body = encrypt(key, plaintext);
        byte[] out = new byte[MAGIC_TAGGED.length + body.length];
        System.arraycopy(MAGIC_TAGGED, 0, out, 0, MAGIC_TAGGED.length);
        System.arraycopy(body, 0, out, MAGIC_TAGGED.length, body.length);
        return out;
    }

    /**
    * 解密带 CHKJ 魔数标记的密文（程序包条目格式）
    *
    * @param key       主密钥
    * @param encrypted [CHKJ][版本][IV][密文]
    * @return 明文
     */
    public static byte[] decryptTagged(byte[] key, byte[] encrypted) {
        if (encrypted == null || !startsWith(encrypted, MAGIC_TAGGED)) {
            throw new CryptoException("密文缺少 CHKJ 标记");
        }
        return decrypt(key, Arrays.copyOfRange(encrypted, MAGIC_TAGGED.length, encrypted.length));
    }

    /**
    * 判断数据是否为带 CHKJ 标记的密文
    *
    * @param data 数据
    * @return true 表示已加密
     */
    public static boolean isTagged(byte[] data) {
        return startsWith(data, MAGIC_TAGGED);
    }

    /**
    * 前缀匹配
    *
    * @param data  数据
    * @param magic 前缀
    * @return true 表示匹配
     */
    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data == null || data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
    * 加密字节
    *
    * @param key      主密钥（32 字节）
    * @param plaintext 明文
    * @return [版本][IV][密文] 二进制
     */
    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        if (plaintext == null) {
            throw new CryptoException("待加密数据不能为空");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);

            byte[] out = new byte[1 + IV_BYTES + encrypted.length];
            out[0] = FORMAT_VERSION;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(encrypted, 0, out, 1 + IV_BYTES, encrypted.length);
            return out;
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("数据加密失败", e);
        }
    }

    /**
    * 解密字节
    *
    * @param key      主密钥（32 字节）
    * @param encrypted [版本][IV][密文] 二进制
    * @return 明文
     */
    public static byte[] decrypt(byte[] key, byte[] encrypted) {
        if (encrypted == null || encrypted.length < 1 + IV_BYTES + 16) {
            throw new CryptoException("密文格式非法或已损坏");
        }
        if (encrypted[0] != FORMAT_VERSION) {
            throw new CryptoException("密文版本不受支持: " + encrypted[0]);
        }
        try {
            byte[] iv = Arrays.copyOfRange(encrypted, 1, 1 + IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(Arrays.copyOfRange(encrypted, 1 + IV_BYTES, encrypted.length));
        } catch (Exception e) {
            throw new CryptoException("数据解密失败（密钥不匹配或密文被篡改）", e);
        }
    }

    /**
    * 加密并编码为 基础64 字符串
    *
    * @param key       主密钥
    * @param plaintext 明文字符串（UTF-8）
    * @return Base64 密文
     */
    public static String encryptToString(byte[] key, String plaintext) {
        return Base64.getEncoder()
                .encodeToString(encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    /**
    * 解密 基础64 密文字符串
    *
    * @param key       主密钥
    * @param ciphertext 基础64 密文
    * @return 明文字符串
     */
    public static String decryptToString(byte[] key, String ciphertext) {
        return new String(decrypt(key, Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
    }
}
