package com.chua.common.support.network.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * HKDF-SHA256 密钥派生（RFC 5869）。
 *
 * <p>只做一件事：把一段共享根密钥（IKM）扩展成多把互不相同的子密钥。
 * 根密钥本身既不直接参与加解密，也不出现在线路上；换一个 {@code info} 标签，
 * 就得到一把独立的子密钥，用于隔离方向、协议版本或用途。</p>
 *
 * <p>安全性来自 HMAC-SHA256：只有持有根密钥的一侧能算出同样的输出，
 * 而输出与根密钥之间不存在可逆关系。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * byte[] rootKey = ...;                                  // 32 字节根密钥
 * byte[] salt = Hkdf.randomBytes(16);                    // 每次连接随机
 * byte[] c2s = Hkdf.derive(rootKey, salt, "chua/session/v1/c2s", 32);
 * byte[] s2c = Hkdf.derive(rootKey, salt, "chua/session/v1/s2c", 32);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class Hkdf {

    /**
     * HMAC 算法名
     */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * SHA-256 摘要长度（字节）
     */
    public static final int HASH_LENGTH = 32;

    /**
     * 单次调用可派生的最长字节数：255 * HashLen
     */
    private static final int MAX_OUTPUT_LENGTH = 255 * HASH_LENGTH;

    /**
     * 随机数发生器（线程安全）
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 工具类构造器，禁止实例化。
     */
    private Hkdf() {
    }

    /**
     * 生成密码学安全的随机字节（如每连接的盐）。
     *
     * @param length 字节数，必须大于 0
     * @return 随机字节
     */
    public static byte[] randomBytes(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("随机字节长度必须大于 0");
        }
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * HKDF-Extract：把盐与输入密钥材料压缩成固定长度伪随机密钥。
     *
     * @param salt 盐，为空时按 RFC 5869 使用 HashLen 个零字节
     * @param ikm  输入密钥材料（根密钥）
     * @return 32 字节伪随机密钥
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        if (ikm == null || ikm.length == 0) {
            throw new IllegalArgumentException("输入密钥材料不能为空");
        }
        byte[] realSalt = salt == null || salt.length == 0 ? new byte[HASH_LENGTH] : salt;
        return hmac(realSalt, ikm);
    }

    /**
     * HKDF-Expand：把伪随机密钥扩展到指定长度。
     *
     * @param prk    伪随机密钥（{@link #extract(byte[], byte[])} 的输出）
     * @param info   上下文标签，可为空
     * @param length 目标长度（字节），不超过 255 * 32
     * @return 派生结果
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (prk == null || prk.length == 0) {
            throw new IllegalArgumentException("伪随机密钥不能为空");
        }
        if (length <= 0 || length > MAX_OUTPUT_LENGTH) {
            throw new IllegalArgumentException("派生长度必须在 1 到 " + MAX_OUTPUT_LENGTH + " 之间");
        }
        byte[] realInfo = info == null ? new byte[0] : info;
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        for (int counter = 1; offset < length; counter++) {
            previous = hmac(prk, previous, realInfo, new byte[]{(byte) counter});
            int copy = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, copy);
            offset += copy;
        }
        Arrays.fill(previous, (byte) 0);
        return output;
    }

    /**
     * 一次性完成 Extract + Expand。
     *
     * @param ikm    输入密钥材料（根密钥）
     * @param salt   盐，为空时按 RFC 5869 使用 HashLen 个零字节
     * @param info   上下文标签，区分方向与用途
     * @param length 目标长度（字节）
     * @return 派生密钥
     */
    public static byte[] derive(byte[] ikm, byte[] salt, String info, int length) {
        byte[] prk = extract(salt, ikm);
        try {
            byte[] infoBytes = info == null ? new byte[0] : info.getBytes(StandardCharsets.UTF_8);
            return expand(prk, infoBytes, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /**
     * 对多段数据依次做 HMAC-SHA256。
     *
     * @param key   密钥
     * @param parts 数据段
     * @return 32 字节摘要
     */
    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
