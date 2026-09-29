package com.chua.wechat.support.restore.sqlcipher;

import java.util.List;

/**
 * SQLCipher 加密参数档案。
 *
 * <p>描述一类 SQLCipher 数据库的页布局与密钥派生参数，供 {@link SqlCipherDecryptor}
 * 按候选顺序逐个探测。字段与 SQLCipher 的 PRAGMA 配置一一对应：</p>
 * <ul>
 *   <li>{@code pageSize} — {@code PRAGMA page_size}，单页字节数</li>
 *   <li>{@code kdfAlgorithm} — 主密钥派生算法（配合 {@code PRAGMA kdf_iter} 使用）</li>
 *   <li>{@code kdfIterations} — 主密钥 PBKDF2 迭代次数</li>
 *   <li>{@code hmacAlgorithm} — 页面 HMAC 算法</li>
 *   <li>{@code hmacSize} — 每页尾部 HMAC 字节数</li>
 * </ul>
 *
 * <h3>页布局</h3>
 * <p>数据库首 16 字节为明文 salt（不加密），此后每页结构为：</p>
 * <pre>
 * [密文 (pageSize - reserveSize)] [IV 16B] [HMAC hmacSize B]
 * reserveSize = IV_SIZE + hmacSize
 * </pre>
 * <p>其中首页的密文长度再减去 16 字节 salt：{@code cipherSize(true) = cipherSize() - SALT_SIZE}。</p>
 *
 * <p><b>微信 4.x</b>使用 SQLCipher 4：AES-256-CBC、HMAC-SHA512、PBKDF2-HMAC-SHA512 256000 次、
 * 页大小 4096、每页保留 80 字节（IV 16 + HMAC 64）。</p>
 *
 * <p>紧凑构造器只校验数值组件的自洽性（见下），两个算法名字符串不做非空校验，
 * 实际取值由 {@link #candidates()} 的内置档案给定。</p>
 *
 * @param name 档案名称，仅用于探测日志与错误信息区分，不参与任何解密计算。
 *             内置档案取值为 {@code wechat-4}（微信 4.x 实测口径）与
 *             {@code sqlcipher-4}（同族 SQLCipher 4 的 1024 页变体）
 * @param pageSize 加密页字节数，对应 {@code PRAGMA page_size}，单位「字节/页」，
 *                 决定每页布局 {@code [密文 pageSize - 16 - hmacSize][IV 16B][HMAC hmacSize B]}。
 *                 合法区间为 512 ~ 65536，且须使密文长度与首页密文长度都为
 *                 {@link #AES_BLOCK_SIZE} 字节（16 字节）的整数倍且大于 0；微信 4.x 为 4096
 * @param kdfAlgorithm 主密钥派生算法的 JCA 名称，对应 {@code PRAGMA kdf_algorithm}，
 *                     语义上与 {@link #hmacAlgorithm} 保持一致（PBKDF2 系列的底层摘要相同）。
 *                     取值 {@code PBKDF2WithHmacSHA512}。注意：本 record 的
 *                     {@code SqlCipherDecryptor} 当前不读取该组件，密钥由微信自身口令路径给出，
 *                     该字段仅作档案自描述与将来接入 {@code PRAGMA} 时的预留
 * @param kdfIterations 主密钥 PBKDF2 迭代次数，对应 {@code PRAGMA kdf_iter}，单位「次」，
 *                      取值来源为实测口径 256000，SQLCipher 4 规范值，不做范围校验。
 *                      同样地，当前 {@code SqlCipherDecryptor} 未读取该组件
 * @param hmacAlgorithm 页面 HMAC 的 JCA 算法名，传入 {@code Mac#getInstance}，
 *                      同时作为派生 HMAC 密钥时 PBKDF2 的 PBE 算法名使用。
 *                      取值 {@code HmacSHA512}（输出 512 位，即 {@link #hmacSize} 64 字节）
 * @param hmacSize 每页尾部 HMAC 校验和字节数，单位「字节/页」，与 {@link #IV_SIZE} 共同构成
 *                 每页保留区 {@link #reserveSize()}。合法区间为 0 ~ 64；0 表示不启用
 *                 页面校验（{@link #hmacEnabled()} 返回 {@code false}）。微信 4.x 为 64
 * @author CH
 * @since 4.0.0.42
 */
public record SqlCipherProfile(String name, int pageSize, String kdfAlgorithm, int kdfIterations,
                               String hmacAlgorithm, int hmacSize) {

    /**
     * salt 字节数（数据库首部明文区）
     */
    public static final int SALT_SIZE = 16;

    /**
     * AES-CBC 初始化向量字节数
     */
    public static final int IV_SIZE = 16;

    /**
     * AES-256 密钥字节数
     */
    public static final int KEY_SIZE = 32;

    /**
     * AES 分组字节数
     */
    public static final int AES_BLOCK_SIZE = 16;

    /**
     * 最小合法页大小
     */
    private static final int MIN_PAGE_SIZE = 512;

    /**
     * 最大合法页大小
     */
    private static final int MAX_PAGE_SIZE = 65536;

    /**
     * 候选档案（按探测优先级排列）。
     *
     * <p>第一项为微信 4.x 实测口径；第二项为同族 SQLCipher 4 的 1024 页变体，
     * 仅作兼容探测，命中与否以页面 HMAC 校验结果为准。</p>
     */
    private static final List<SqlCipherProfile> CANDIDATES = List.of(
            new SqlCipherProfile("wechat-4", 4096, "PBKDF2WithHmacSHA512", 256000, "HmacSHA512", 64),
            new SqlCipherProfile("sqlcipher-4", 1024, "PBKDF2WithHmacSHA512", 256000, "HmacSHA512", 64)
    );

    /**
     * 紧凑构造器：校验参数自洽性。
     *
     * @throws IllegalArgumentException 页大小越界或密文长度非 AES 分组整数倍时抛出
     */
    public SqlCipherProfile {
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("非法页大小: " + pageSize);
        }
        if (hmacSize < 0 || hmacSize > 64) {
            throw new IllegalArgumentException("非法 HMAC 长度: " + hmacSize);
        }
        int cipherSize = pageSize - IV_SIZE - hmacSize;
        if (cipherSize <= 0 || cipherSize % AES_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("密文长度必须是 " + AES_BLOCK_SIZE
                    + " 的整数倍，当前为 " + cipherSize + "（页大小 " + pageSize + "，HMAC " + hmacSize + "）");
        }
        int firstCipherSize = cipherSize - SALT_SIZE;
        if (firstCipherSize <= 0 || firstCipherSize % AES_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("首页密文长度必须是 " + AES_BLOCK_SIZE
                    + " 的整数倍，当前为 " + firstCipherSize);
        }
    }

    /**
     * 每页保留区字节数（IV + HMAC）。
     *
     * @return 保留区字节数，微信 4.x 为 80
     */
    public int reserveSize() {
        return IV_SIZE + hmacSize;
    }

    /**
     * 非首页的密文区字节数。
     *
     * @return 密文区字节数，微信 4.x 为 4016
     */
    public int cipherSize() {
        return pageSize - reserveSize();
    }

    /**
     * 指定页的密文区字节数。
     *
     * @param firstPage 是否首页（首页需额外扣除 16 字节 salt）
     * @return 密文区字节数，微信 4.x 首页为 4000、其余页为 4016
     */
    public int cipherSize(boolean firstPage) {
        return firstPage ? cipherSize() - SALT_SIZE : cipherSize();
    }

    /**
     * 是否启用页面 HMAC 校验。
     *
     * @return HMAC 长度大于 0 返回 true
     */
    public boolean hmacEnabled() {
        return hmacSize > 0;
    }

    /**
     * 获取候选档案列表（按探测优先级）。
     *
     * @return 不可变候选列表
     */
    public static List<SqlCipherProfile> candidates() {
        return CANDIDATES;
    }
}
