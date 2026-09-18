package com.chua.crypto.support.launch;

import java.security.SecureRandom;
import java.util.Arrays;

/**
* 密钥内存分片（抗 heap dump 特征扫描）
*
* <p>将主密钥拆为 N 片：前 N-1 片为安全随机数，末片为主密钥与前述各片的异或，
* 满足"全部片段拼合才可还原"。分片后堆中不存在连续 32 字节的完整密钥，
* 使 jmap/heapdump 后的密钥特征扫描失效；每次使用时临时拼合、用后即清零。
*
* @author CH
* @since 2026-08-26
 */
public final class KeyShard {

    /**
    * 分片数量
    */
    public static final int SHARDS = 3;

    /**
    * 私有构造
    */
    private KeyShard() {
    }

    /**
    * 拆分密钥（入参数组被清零）
    *
    * @param key 主密钥
    * @return 分片数组
    */
    public static byte[][] shard(byte[] key) {
        byte[][] shards = new byte[SHARDS][key.length];
        for (int i = 0; i < SHARDS - 1; i++) {
            RANDOM.nextBytes(shards[i]);
        }
        byte[] last = shards[SHARDS - 1];
        System.arraycopy(key, 0, last, 0, key.length);
        for (int i = 0; i < SHARDS - 1; i++) {
            for (int j = 0; j < last.length; j++) {
                last[j] ^= shards[i][j];
            }
        }
        wipe(key);
        return shards;
    }

    /**
    * 拼合还原密钥（调用方负责用后清零返回值与各分片）
    *
    * @param shards 分片数组
    * @return 还原的主密钥副本
    */
    public static byte[] join(byte[][] shards) {
        byte[] key = new byte[shards[0].length];
        for (byte[] shard : shards) {
            for (int j = 0; j < key.length; j++) {
                key[j] ^= shard[j];
            }
        }
        return key;
    }

    /**
    * 销毁全部分片
    *
    * @param shards 分片数组
    */
    public static void wipe(byte[][] shards) {
        for (byte[] shard : shards) {
            Arrays.fill(shard, (byte) 0);
        }
    }

    /**
    * 清零单个数组
    *
    * @param data 数组
    */
    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
    * 安全随机源
    */
    private static final SecureRandom RANDOM = new SecureRandom();
}
