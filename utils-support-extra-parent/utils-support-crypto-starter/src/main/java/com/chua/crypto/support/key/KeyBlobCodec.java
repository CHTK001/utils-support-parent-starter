package com.chua.crypto.support.key;

import com.chua.crypto.support.CryptoException;
import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.KeyPolicy;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 密钥封装体编解码器（统一二进制格式）
 *
 * <p>所有密钥载体（密钥文件、打包内嵌密钥块）共用同一封装格式：
 * <pre>
 * [魔数4B][版本1B][策略标志1B][密钥ID8B][盐16B][IV12B][封装主密钥N字节][HMAC-SHA256 32B]
 * </pre>
 *
 * <p>主密钥以 KEK(AES-256-GCM) 封装，明文永不落盘；尾部 HMAC 覆盖除自身外全部字节防篡改。
 * 文件载体与本模块打包器({@code pack.JarEncryptor})均委托本类完成编解码。
 *
 * @author CH
 * @since 2026-08-26
 */
public final class KeyBlobCodec {

    /**
     * 载体格式版本号
     */
    public static final byte VERSION = 1;

    /**
     * 策略标志位：服务器绑定
     */
    public static final byte FLAG_SERVER_BOUND = 0x01;

    /**
     * 头部长度：魔数(4)+版本(1)+标志(1)
     */
    public static final int PREFIX_BYTES = 6;

    /**
     * 密钥 标识 长度（字节）
     */
    public static final int KEY_ID_BYTES = SecretKeyMaterial.KEY_ID_LENGTH_BYTES;

    /**
     * 固定段长度：头部(6)+密钥标识(8)+盐(16)+IV(12)
     */
    public static final int FIXED_HEADER_BYTES = PREFIX_BYTES + KEY_ID_BYTES
            + KeyProtector.SALT_BYTES + KeyProtector.GCM_IV_BYTES;

    /**
     * 尾部 HMAC 长度（字节）
     */
    public static final int TRAILING_MAC_BYTES = KeyProtector.MAC_BYTES;

    /**
     * 最小合法长度：固定段 + HMAC
     */
    public static final int MIN_LENGTH = FIXED_HEADER_BYTES + TRAILING_MAC_BYTES;

    /**
     * 随机源
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 私有构造
     */
    private KeyBlobCodec() {
    }

    /**
     * 将主密钥材料按当前策略封装为二进制密文块
     *
     * @param magic    4 字节魔数（如 CHKF）
     * @param material 主密钥材料
     * @param setting  加密配置（策略/口令/服务器标识）
     * @return 封装后的完整二进制块
     */
    public static byte[] encode(byte[] magic, SecretKeyMaterial material, CryptoSetting setting) {
        byte[] salt = new byte[KeyProtector.SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] iv = KeyProtector.randomIv();
        byte[] kek = KeyProtector.deriveKek(setting.getKeyPolicy(), setting, salt);

        byte[] header = new byte[PREFIX_BYTES];
        System.arraycopy(magic, 0, header, 0, Math.min(magic.length, 4));
        header[4] = VERSION;
        header[5] = setting.getKeyPolicy() == KeyPolicy.SERVER_BOUND ? FLAG_SERVER_BOUND : 0x00;

        byte[] keyId = material.keyId();
        byte[] wrapped = KeyProtector.wrap(kek, iv, material.copyKey());

        byte[] body = new byte[header.length + keyId.length + salt.length + iv.length + wrapped.length];
        int offset = append(body, 0, header);
        offset = append(body, offset, keyId);
        offset = append(body, offset, salt);
        offset = append(body, offset, iv);
        append(body, offset, wrapped);

        byte[] mac = KeyProtector.mac(kek, body);
        byte[] out = new byte[body.length + mac.length];
        System.arraycopy(body, 0, out, 0, body.length);
        System.arraycopy(mac, 0, out, body.length, mac.length);
        return out;
    }

    /**
     * 校验并解封二进制密钥块
     *
     * @param magic   期望魔数
     * @param blob    二进制密钥块
     * @param setting 加密配置（提供口令/服务器标识；策略以块内标志为准）
     * @return 解封出的主密钥材料
     */
    public static SecretKeyMaterial decode(byte[] magic, byte[] blob, CryptoSetting setting) {
        if (blob == null || blob.length < MIN_LENGTH) {
            throw new CryptoException("密钥块已损坏（长度不足）");
        }
        verifyHeader(magic, blob);

        boolean serverBound = (blob[5] & FLAG_SERVER_BOUND) != 0;
        KeyPolicy policy = serverBound ? KeyPolicy.SERVER_BOUND : KeyPolicy.CUSTOM;
        int cursor = PREFIX_BYTES;
        byte[] keyId = slice(blob, cursor, KEY_ID_BYTES);
        cursor += KEY_ID_BYTES;
        byte[] salt = slice(blob, cursor, KeyProtector.SALT_BYTES);
        cursor += KeyProtector.SALT_BYTES;
        byte[] iv = slice(blob, cursor, KeyProtector.GCM_IV_BYTES);
        cursor += KeyProtector.GCM_IV_BYTES;
        byte[] wrapped = slice(blob, cursor, blob.length - TRAILING_MAC_BYTES - cursor);

        byte[] kek = KeyProtector.deriveKek(policy, setting, salt);
        byte[] expectedMac = KeyProtector.mac(kek, Arrays.copyOfRange(blob, 0, blob.length - TRAILING_MAC_BYTES));
        byte[] actualMac = slice(blob, blob.length - TRAILING_MAC_BYTES, TRAILING_MAC_BYTES);
        if (!KeyProtector.verifyMac(expectedMac, actualMac)) {
            throw new CryptoException("密钥块完整性校验失败（可能被篡改或口令错误）");
        }
        return SecretKeyMaterial.of(keyId, KeyProtector.unwrap(kek, iv, wrapped));
    }

    /**
     * 判断数据是否以指定魔数开头
     *
     * @param data  数据
     * @param magic 魔数
     * @return true 表示匹配
     */
    public static boolean startsWithMagic(byte[] data, byte[] magic) {
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
     * 校验魔数与版本
     *
     * @param magic 期望魔数
     * @param blob  密钥块
     */
    private static void verifyHeader(byte[] magic, byte[] blob) {
        for (int i = 0; i < Math.min(magic.length, 4); i++) {
            if (blob[i] != magic[i]) {
                throw new CryptoException("密钥块格式不匹配（魔数校验失败）");
            }
        }
        if (blob[4] != VERSION) {
            throw new CryptoException("密钥块版本不受支持: " + blob[4]);
        }
    }

    /**
     * 追加字节数组到目标缓冲区
     *
     * @param target 目标缓冲区
     * @param offset 起始偏移
     * @param data   数据
     * @return 新偏移
     */
    private static int append(byte[] target, int offset, byte[] data) {
        System.arraycopy(data, 0, target, offset, data.length);
        return offset + data.length;
    }

    /**
     * 截取字节区间副本
     *
     * @param source 源数组
     * @param from   起始下标
     * @param length 长度
     * @return 副本
     */
    private static byte[] slice(byte[] source, int from, int length) {
        return Arrays.copyOfRange(source, from, from + length);
    }
}
