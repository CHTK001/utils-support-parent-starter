package com.chua.crypto.support.key;

import com.chua.crypto.support.CryptoException;
import com.chua.crypto.support.KeyPolicy;
import com.chua.crypto.support.CryptoSetting;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
* 密钥保护器（KEK 派生与密钥封装）
*
* <p>实现"密钥隐私存储"的核心机制：
* <ol>
*   <li><b>KEK 派生</b> — 依据密钥策略派生密钥加密密钥(KEK)：
* 习俗 策略由口令 PBKDF2 派生；服务端_BOUND 策略由服务器指纹 PBKDF2 派生（口令可选叠加为 pepper）</li>
*   <li><b>密钥封装/解封</b> — 主密钥永远以 AES-GCM 密文形态存在于载体（密钥文件/打包内嵌块），明文仅存于内存</li>
*   <li><b>完整性校验</b> — HmacSHA256 防篡改，比较采用常量时间算法防时序侧信道</li>
* </ol>
*
* @author CH
* @since 2026-08-26
 */
public final class KeyProtector {

    /**
    * KEK/主密钥位数
    */
    public static final int KEY_BITS = 256;

    /**
    * PBKDF2 迭代次数（OWASP 2024 推荐 60 万，兼顾启动耗时取 21 万）
    */
    public static final int PBKDF2_ITERATIONS = 210_000;

    /**
    * GCM IV 长度（字节）
    */
    public static final int GCM_IV_BYTES = 12;

    /**
    * GCM 认证标签长度（位）
    */
    public static final int GCM_TAG_BITS = 128;

    /**
    * 盐长度（字节）
    */
    public static final int SALT_BYTES = 16;

    /**
    * HMAC 输出长度（字节）
    */
    public static final int MAC_BYTES = 32;

    /**
    * 盐值随机源（每次封装独立加盐）
    */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
    * 口令拼接分隔符（服务端_BOUND 策略下指纹与 pepper 的连接符）
    */
    private static final String SEP = "::";

    /**
    * 私有构造
    */
    private KeyProtector() {
    }

    /**
    * 依据密钥策略派生 KEK
    *
    * @param policy 密钥策略
    * @param setting 加密配置（提供口令与服务器标识覆盖项）
    * @param salt    盐
    * @return 32 字节 KEK
    */
    public static byte[] deriveKek(KeyPolicy policy, CryptoSetting setting, byte[] salt) {
        char[] password = buildPassword(policy, setting);
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new CryptoException("KEK 派生失败", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
    * 构造 PBKDF2 口令输入：习俗 为纯口令；服务端_BOUND 为指纹+可选 pepper
    *
    * @param policy  密钥策略
    * @param setting 加密配置
    * @return 口令字符数组（调用后由派生方清零）
    */
    private static char[] buildPassword(KeyPolicy policy, CryptoSetting setting) {
        if (policy == KeyPolicy.CUSTOM) {
            requireSecret(setting);
            return Arrays.copyOf(setting.getSecret(), setting.getSecret().length);
        }
        String serverId = setting.getServerId();
        ServerFingerprint fingerprint = (serverId != null && !serverId.isBlank())
                ? ServerFingerprint.of(serverId)
                : ServerFingerprint.capture();
        StringBuilder sb = new StringBuilder(fingerprint.value());
        if (setting.getSecret() != null && setting.getSecret().length > 0) {
            // 口令作为 pepper 叠加：即使指纹被仿造，仍需口令才能解锁
            sb.append(SEP).append(new String(setting.getSecret()));
        }
        return sb.toString().toCharArray();
    }

    /**
    * 校验 习俗 策略必须提供口令
    *
    * @param setting 加密配置
    */
    private static void requireSecret(CryptoSetting setting) {
        if (setting.getSecret() == null || setting.getSecret().length == 0) {
            throw new CryptoException("CUSTOM 密钥策略要求提供自定义口令(secret)");
        }
    }

    /**
    * 生成随机盐
    *
    * @return 16 字节盐
    */
    public static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /**
    * 生成随机 GCM IV
    *
    * @return 12 字节 IV
    */
    public static byte[] randomIv() {
        byte[] iv = new byte[GCM_IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    /**
    * 封装主密钥：以 KEK 做 AES-GCM 加密
    *
    * @param kek      KEK
    * @param iv       GCM IV
    * @param masterKey 明文主密钥
    * @return 密文(含认证标签)
    */
    public static byte[] wrap(byte[] kek, byte[] iv, byte[] masterKey) {
        return gcm(kek, iv, masterKey, Cipher.ENCRYPT_MODE);
    }

    /**
    * 解封主密钥：以 KEK 做 AES-GCM 解密（认证失败即抛异常）
    *
    * @param kek KEK
    * @param iv  GCM IV
    * @param wrapped 密文
    * @return 明文主密钥
    */
    public static byte[] unwrap(byte[] kek, byte[] iv, byte[] wrapped) {
        return gcm(kek, iv, wrapped, Cipher.DECRYPT_MODE);
    }

    /**
    * AES-GCM 加解密统一入口
    *
    * @param kek  密钥
    * @param iv   IV
    * @param data 数据
    * @param mode Cipher 模式
    * @return 结果字节
    */
    private static byte[] gcm(byte[] kek, byte[] iv, byte[] data, int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new CryptoException("密钥封装/解封失败（密钥策略不匹配或载体被篡改）", e);
        }
    }

    /**
    * 计算 hmacsha256 完整性摘要
    *
    * @param kek  作为 HMAC 密钥的 KEK
    * @param data 待校验数据
    * @return 32 字节摘要
    */
    public static byte[] mac(byte[] kek, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(kek, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new CryptoException("完整性摘要计算失败", e);
        }
    }

    /**
    * 常量时间摘要比对，防止时序攻击
    *
    * @param expected 期望摘要
    * @param actual   实际摘要
    * @return true 表示一致
    */
    public static boolean verifyMac(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }

}
