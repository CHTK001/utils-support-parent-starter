package com.chua.crypto.support.launch;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
* 引导期加解密工具（打包注入专用，零第三方依赖）
*
* <p>与模块内 {@code DataCipher}/{@code KeyBlobCodec} 保持同一二进制格式：
* <ul>
*   <li>程序包条目：{@code [CHKJ][版本1B][IV12B][密文+GCM标签]}</li>
*   <li>密钥封装块：{@code [魔数4B][版本1B][标志1B][密钥ID8B][盐16B][IV12B][封装主密钥][HMAC32B]}</li>
* </ul>
*
* <p>本类刻意不复用主模块代码：引导器必须以零依赖形态注入加密包，
* 在应用类加载器建立之前即可执行（隔离性优先于代码复用，格式由测试保证一致）。
* 算法原语全部调用 JDK 标准 JCE，无任何自研密码学实现。
*
* @author CH
* @since 2026-08-26
 */
public final class PayloadCipher {

    /**
    * 程序包条目魔数：CHKJ
     */
    public static final byte[] MAGIC_ENTRY = {'C', 'H', 'K', 'J'};

    /**
    * 密钥封装块魔数：CHKF
     */
    public static final byte[] MAGIC_KEY_BLOB = {'C', 'H', 'K', 'F'};

    /**
    * 格式版本
     */
    private static final byte VERSION = 1;

    /**
    * 策略标志：服务器绑定
     */
    private static final byte FLAG_SERVER_BOUND = 0x01;

    /**
    * 头部长度：魔数4+版本1+标志1+密钥标识8+盐16+IV12
     */
    private static final int BLOB_PREFIX = 6 + 8 + 16 + 12;

    /**
    * 尾部 HMAC 长度
     */
    private static final int MAC_LEN = 32;

    /**
    * 盐长度
     */
    private static final int SALT_LEN = 16;

    /**
    * IV 长度
     */
    private static final int IV_LEN = 12;

    /**
    * 密钥 标识 长度
     */
    private static final int KEY_ID_LEN = 8;

    /**
    * PBKDF2 迭代次数（与主模块 键protector 一致）
     */
    private static final int PBKDF2_ITERATIONS = 210_000;

    /**
    * GCM 标签长度（位）
     */
    private static final int GCM_TAG_BITS = 128;

    /**
    * 私有构造
     */
    private PayloadCipher() {
    }

    /**
    * 判断条目是否为 CHKJ 加密数据
    *
    * @param data 条目原始字节
    * @return true 表示已加密
     */
    public static boolean isEncryptedEntry(byte[] data) {
        return startsWith(data, MAGIC_ENTRY);
    }

    /**
    * 解密 CHKJ 程序包条目
    *
    * @param master 主密钥
    * @param data   密文字节
    * @return 明文
     */
    public static byte[] decryptEntry(byte[] master, byte[] data) {
        requireMagic(data, MAGIC_ENTRY, "程序包条目缺少 CHKJ 标记");
        return gcm(master, Arrays.copyOfRange(data, MAGIC_ENTRY.length + 1,
                        MAGIC_ENTRY.length + 1 + IV_LEN),
                Arrays.copyOfRange(data, MAGIC_ENTRY.length + 1 + IV_LEN, data.length),
                Cipher.DECRYPT_MODE);
    }

    /**
    * 从密钥封装块解封主密钥（CHKF 格式，与打包内嵌块/私钥文件一致）
    *
    * @param expectedMagic 期望魔数
    * @param blob          封装块字节
    * @param pin           口令（习俗 必需；服务端_BOUND 可选作 pepper）
    * @param serverId      固定服务器标识（可空；覆盖自动指纹）
    * @return 32 字节主密钥
     */
    public static byte[] unwrapMaster(byte[] expectedMagic, byte[] blob, char[] pin, String serverId) {
        requireLength(blob, BLOB_PREFIX + MAC_LEN, "密钥块长度非法");
        requireMagic(blob, expectedMagic, "密钥块魔数不匹配");

        boolean serverBound = (blob[5] & FLAG_SERVER_BOUND) != 0;
        int cursor = 6;
        byte[] salt = slice(blob, cursor + KEY_ID_LEN, SALT_LEN);
        byte[] iv = slice(blob, cursor + KEY_ID_LEN + SALT_LEN, IV_LEN);
        byte[] wrapped = slice(blob, BLOB_PREFIX, blob.length - MAC_LEN - BLOB_PREFIX);

        byte[] kek = deriveKek(serverBound, pin, serverId, salt);
        byte[] expectedMac = hmac(kek, slice(blob, 0, blob.length - MAC_LEN));
        byte[] actualMac = slice(blob, blob.length - MAC_LEN, MAC_LEN);
        if (!MessageDigest.isEqual(expectedMac, actualMac)) {
            throw new IllegalStateException("密钥块完整性校验失败（口令错误或文件被篡改）");
        }
        return gcm(kek, iv, wrapped, Cipher.DECRYPT_MODE);
    }

    /**
    * 派生 KEK：服务端_BOUND 由服务器指纹派生（口令作 pepper），否则由口令直接派生。
    * 与主模块 {@code KeyProtector.deriveKek} 完全对齐。
    *
    * @param serverBound 是否服务器绑定策略
    * @param secret      口令
    * @param serverId    固定服务器标识（可空）
    * @param salt        盐
    * @return 32 字节 KEK
     */
    public static byte[] deriveKek(boolean serverBound, char[] secret, String serverId, byte[] salt) {
        String password;
        if (serverBound) {
            StringBuilder sb = new StringBuilder(fingerprint(serverId));
            if (secret != null && secret.length > 0) {
                sb.append("::").append(secret);
            }
            password = sb.toString();
        } else {
            if (secret == null || secret.length == 0) {
                throw new IllegalStateException("该程序包采用自定义口令策略，请通过 -Dchua.crypto.pin=xxx 提供口令");
            }
            password = new String(secret);
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("KEK 派生失败", e);
        }
    }

    /**
    * 计算服务器指纹（与主模块 服务端fingerprint 特征集合一致）：
    * OS、架构、CPU 核数、主机名、非回环物理网卡 MAC（排序）
    *
    * @param pinned 固定标识（非空时直接哈希该值）
    * @return SHA-256 十六进制串
     */
    public static String fingerprint(String pinned) {
        if (pinned != null && !pinned.isBlank()) {
            return sha256Hex("pinned|" + pinned.trim());
        }
        List<String> parts = new ArrayList<>();
        parts.add(System.getProperty("os.name", "unknown-os"));
        parts.add(System.getProperty("os.arch", "unknown-arch"));
        parts.add(String.valueOf(Runtime.getRuntime().availableProcessors()));
        try {
            parts.add(InetAddress.getLocalHost().getHostName());
        } catch (Exception ignored) {
            parts.add("unknown-host");
        }
        List<String> macs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                byte[] hardware = ni.getHardwareAddress();
                if (hardware == null || hardware.length == 0) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : hardware) {
                    sb.append(String.format("%02x", b));
                }
                macs.add(sb.toString());
            }
        } catch (Exception ignored) {
            // 受限环境跳过网卡特征
        }
        Collections.sort(macs);
        parts.addAll(macs);
        return sha256Hex(String.join("|", parts));
    }

    /**
    * AES-GCM 加解密
    *
    * @param key  密钥
    * @param iv   IV
    * @param data 数据
    * @param mode 模式
    * @return 结果
     */
    private static byte[] gcm(byte[] key, byte[] iv, byte[] data, int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 运算失败（密钥不匹配或数据被篡改）", e);
        }
    }

    /**
    * hmacsha256
    *
    * @param key  密钥
    * @param data 数据
    * @return 摘要
     */
    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /**
    * SHA-256 十六进制摘要
    *
    * @param data 原文
    * @return 十六进制串
     */
    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }

    /**
    * 前缀匹配
    * @param data 数据
    * @param magic 魔法
    * @return 启动with的结果
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
    * 校验魔数
    * @param data 数据
    * @param magic 魔法
    * @param message 消息
     */
    private static void requireMagic(byte[] data, byte[] magic, String message) {
        if (!startsWith(data, magic)) {
            throw new IllegalStateException(message);
        }
    }

    /**
    * 校验最小长度
    * @param data 数据
    * @param min 最小
    * @param message 消息
     */
    private static void requireLength(byte[] data, int min, String message) {
        if (data == null || data.length < min) {
            throw new IllegalStateException(message);
        }
    }

    /**
    * 截取副本
    * @param source 源
    * @param from 从
    * @param length 长度
    * @return slice的结果
     */
    private static byte[] slice(byte[] source, int from, int length) {
        return Arrays.copyOfRange(source, from, from + length);
    }
}
