package com.chua.common.support.network.server.dht;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Kademlia 160 位节点 ID。
 * <p>
 * 使用 SHA-1 哈希生成 160 位（20 字节）的节点标识符，
 * 支持 XOR 距离计算和 K-Bucket 索引定位，是 Kademlia 协议的核心数据结构。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KademliaNodeId implements Comparable<KademliaNodeId> {

    /**
     * 节点 ID 的总位数（160 位）
     */
    public static final int ID_LENGTH = 160;
    /**
     * 节点 ID 的原始字节数组（20 字节，克隆保护）。
     */
    private final byte[] id;

    /**
     * 使用指定的 20 字节数组构造节点 ID。
     *
     * @param id 20 字节的节点 ID 原始字节
     */
    public KademliaNodeId(byte[] id) {
        if (id.length != ID_LENGTH / 8) {
            throw new IllegalArgumentException("ID must be 20 bytes");
        }
        this.id = id.clone();
    }

    /**
     * 生成一个随机的节点 ID（基于 SHA-1 哈希当前纳秒时间和随机数）。
     *
     * @return 随机生成的 KademliaNodeId 实例
     */
    public static KademliaNodeId random() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] seed = (System.nanoTime() + "-" + Math.random()).getBytes(StandardCharsets.UTF_8);
            return new KademliaNodeId(md.digest(seed));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据字符串生成节点 ID（SHA-1 哈希字符串内容）。
     *
     * @param str 用于生成 ID 的源字符串
     * @return KademliaNodeId 实例
     */
    public static KademliaNodeId fromString(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return new KademliaNodeId(md.digest(str.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 从十六进制字符串解析节点 ID。
     *
     * @param hex 40 字符的十六进制字符串
     * @return KademliaNodeId 实例
     */
    public static KademliaNodeId fromHex(String hex) {
        byte[] bytes = new byte[20];
        for (int i = 0; i < 20; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new KademliaNodeId(bytes);
    }

    /**
     * 获取节点 ID 的原始字节数组（克隆副本）。
     *
     * @return 20 字节数组
     */
    public byte[] getBytes() {
        return id.clone();
    }

    /**
     * 将节点 ID 转换为 BigInteger 用于数值比较和距离计算。
     *
     * @return BigInteger 表示
     */
    public BigInteger getInt() {
        return new BigInteger(1, id);
    }

    /**
     * 计算当前节点与另一节点的 XOR 距离。
     *
     * @param other 另一个节点 ID
     * @return XOR 结果作为新的 KademliaNodeId
     */
    public KademliaNodeId xor(KademliaNodeId other) {
        byte[] result = new byte[id.length];
        for (int i = 0; i < id.length; i++) {
            result[i] = (byte) (id[i] ^ other.id[i]);
        }
        return new KademliaNodeId(result);
    }

    /**
     * 计算目标节点应落在哪个 K-Bucket 索引中。
     * <p>
     * 通过 XOR 后最高位的位置确定，范围 0 ~ 159。
     * </p>
     *
     * @param other 目标节点 ID
     * @return K-Bucket 索引（0 ~ 159）
     */
    public int getBucketIndex(KademliaNodeId other) {
        KademliaNodeId xored = this.xor(other);
        for (int i = 0; i < ID_LENGTH; i++) {
            int byteIdx = i / 8;
            int bitIdx = 7 - (i % 8);
            if ((xored.id[byteIdx] & (1 << bitIdx)) != 0) {
                return ID_LENGTH - 1 - i;
            }
        }
        return 0;
    }

    /**
     * 计算当前节点与另一节点的距离（XOR 距离的 bit 位数）。
     *
     * @param other 另一个节点 ID
     * @return XOR 距离的 bit 长度
     */
    public int getDistance(KademliaNodeId other) {
        BigInteger d = this.getInt().xor(other.getInt());
        return d.bitLength();
    }

    @Override
    public int compareTo(KademliaNodeId o) {
        return this.getInt().compareTo(o.getInt());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        KademliaNodeId that = (KademliaNodeId) o;
        return Arrays.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(id);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(40);
        for (byte b : id) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
