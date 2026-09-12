package com.chua.crypto.support.key;

import com.chua.crypto.support.CryptoException;

import java.security.SecureRandom;
import java.util.Arrays;

/**
* 主密钥材料
*
* <p>持有 AES-256 主密钥与其唯一标识，实现 {@link AutoCloseable}：
* {@link #close()} 会将内存中的密钥字节清零（防残留），清零后任何读取都会抛出异常。
* 密钥材料仅允许存活于进程内存，禁止序列化/日志输出。
*
* @author CH
* @since 2026-08-26
 */
public final class SecretKeyMaterial implements AutoCloseable {

    /**
    * 主密钥长度（字节）：256 位
     */
    public static final int KEY_LENGTH_BYTES = 32;

    /**
    * 密钥标识长度（字节）
     */
    public static final int KEY_ID_LENGTH_BYTES = 8;

    /**
    * 安全随机源
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
    * 密钥标识（用于轮换识别，非敏感）
     */
    private final byte[] keyId;

    /**
    * 主密钥字节
     */
    private volatile byte[] key;

    /**
    * 构造密钥材料
    *
    * @param keyId 密钥标识
    * @param key   主密钥字节
     */
    private SecretKeyMaterial(byte[] keyId, byte[] key) {
        this.keyId = keyId;
        this.key = key;
    }

    /**
    * 生成随机主密钥材料
    *
    * @return 新的密钥材料
     */
    public static SecretKeyMaterial generate() {
        byte[] keyId = new byte[KEY_ID_LENGTH_BYTES];
        byte[] key = new byte[KEY_LENGTH_BYTES];
        RANDOM.nextBytes(keyId);
        RANDOM.nextBytes(key);
        return new SecretKeyMaterial(keyId, key);
    }

    /**
    * 使用已有数据构造密钥材料（载体加载场景）
    *
    * @param keyId 密钥标识
    * @param key   主密钥字节
    * @return 密钥材料
     */
    public static SecretKeyMaterial of(byte[] keyId, byte[] key) {
        if (key == null || key.length != KEY_LENGTH_BYTES) {
            throw new CryptoException("主密钥长度非法，期望 " + KEY_LENGTH_BYTES + " 字节");
        }
        return new SecretKeyMaterial(
                keyId != null ? Arrays.copyOf(keyId, keyId.length) : new byte[KEY_ID_LENGTH_BYTES],
                Arrays.copyOf(key, key.length));
    }

    /**
    * 获取密钥标识副本
    *
    * @return 8 字节标识副本
     */
    public byte[] keyId() {
        requireAlive();
        return Arrays.copyOf(keyId, keyId.length);
    }

    /**
    * 获取主密钥字节副本。调用方负责妥善保管，禁止落盘明文。
    *
    * @return 32 字节密钥副本
     */
    public byte[] copyKey() {
        requireAlive();
        return Arrays.copyOf(key, key.length);
    }

    /**
    * 密钥是否仍可用
    *
    * @return true 表示未销毁
     */
    public boolean isDestroyed() {
        return key == null;
    }

    /**
    * 校验密钥是否已销毁
     */
    private void requireAlive() {
        if (key == null) {
            throw new CryptoException("密钥材料已销毁，无法继续使用");
        }
    }

    /**
    * 销毁密钥：内存清零，不可逆
     */
    @Override
    public void close() {
        byte[] current = key;
        if (current != null) {
            Arrays.fill(current, (byte) 0);
            key = null;
        }
        Arrays.fill(keyId, (byte) 0);
    }
}
