package com.chua.wechat.support.restore.sqlcipher;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * SQLCipher 数据库直解器。
 *
 * <p>不依赖微信运行时、WCDB.dll 或任何外部工具，仅用 JDK 自带密码学能力把 SQLCipher 4
 * 加密的数据库还原为标准明文 SQLite 文件。算法与微信 4.x 的加密口径严格对齐：</p>
 *
 * <ul>
 *   <li>加密算法：AES-256-CBC（无填充），密钥即 32 字节 raw enc_key</li>
 *   <li>页布局：首 16 字节明文 salt；每页尾保留 {@code IV(16) + HMAC(64)}，共 80 字节</li>
 *   <li>HMAC 密钥：{@code PBKDF2-HMAC-SHA512(enc_key, salt ^ 0x3A, 2, 32)}</li>
 *   <li>页面校验：{@code HMAC-SHA512(mac_key, page[16:4032] || LE32(pgno))} 等于页尾 64 字节</li>
 * </ul>
 *
 * <h3>密钥形态</h3>
 * <p>支持两种输入：64 位十六进制（32 字节 enc_key），以及微信内存中缓存的
 * {@code x'<64hex_enc_key><32hex_salt>'} 形态（96 位十六进制，取前 64 位）。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * byte[] key = SqlCipherDecryptor.parseHexKey("0123...cdef");
 * if (!SqlCipherDecryptor.isKeyValid(new File("session.db"), key)) {
 *     throw new IllegalStateException("密钥不匹配");
 * }
 * File plain = SqlCipherDecryptor.decryptToTemp(new File("session.db"), key);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SqlCipherDecryptor {

    /**
    * 明文 SQLite 文件头魔数
    */
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    /**
    * AES-CBC 无填充变换名
    */
    private static final String AES_CBC_NO_PADDING = "AES/CBC/NoPadding";

    /**
    * HMAC 密钥派生时的 PBKDF2 迭代次数（SQLCipher 固定为 2）
    */
    private static final int HMAC_KEY_ITERATIONS = 2;

    /**
    * HMAC salt 与文件 salt 的异或掩码
    */
    private static final byte HMAC_SALT_XOR = 0x3A;

    /**
    * 文件头中「页大小」字段的偏移
    */
    private static final int OFFSET_PAGE_SIZE = 16;

    /**
    * 文件头中「保留区字节数」字段的偏移
    */
    private static final int OFFSET_RESERVED = 20;

    /**
    * 文件头读取长度（覆盖到保留区字段）
    */
    private static final int HEADER_PROBE_LENGTH = 32;

    /**
    * 页大小字段为 1 时表示 65536
    */
    private static final int PAGE_SIZE_64K = 65536;

    /**
    * 写缓冲大小
    */
    private static final int BUFFER_SIZE = 1 << 20;

    /**
    * 工具类禁止实例化。
    */
    private SqlCipherDecryptor() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    // ==================== 探测与校验 ====================

    /**
    * 判断文件是否已是明文 SQLite（无需解密）。
    *
    * @param source 待判断文件
    * @return 以 SQLite 文件头开头返回 true
    */
    public static boolean isPlainSqlite(File source) {
        if (source == null || !source.isFile() || source.length() < SqlCipherProfile.SALT_SIZE) {
            return false;
        }
        try {
            return startsWithMagic(readPage(source, 0, SqlCipherProfile.SALT_SIZE));
        } catch (IOException e) {
            log.debug("读取 SQLite 文件头失败: {}", e.getMessage());
            return false;
        }
    }

    /**
    * 建立密钥探针，用于批量试密钥时避免重复读取页面。
    *
    * @param source 加密数据库文件
    * @return 密钥探针
    * @throws IOException 读取文件失败
    */
    public static KeyProbe keyProbe(File source) throws IOException {
        return keyProbe(source, SqlCipherProfile.candidates().getFirst());
    }

    /**
    * 建立密钥探针（指定参数档案）。
    *
    * @param source  加密数据库文件
    * @param profile SQLCipher 参数档案
    * @return 密钥探针
    * @throws IOException 读取文件失败或文件不足一页
    */
    public static KeyProbe keyProbe(File source, SqlCipherProfile profile) throws IOException {
        requireReadable(source);
        Objects.requireNonNull(profile, "SQLCipher 参数档案不能为空");
        if (source.length() < profile.pageSize()) {
            throw new IOException("数据库文件不足一页，无法建立密钥探针: " + source.getAbsolutePath());
        }
        byte[] page = readPage(source, 0, profile.pageSize());
        return new KeyProbe(Arrays.copyOf(page, SqlCipherProfile.SALT_SIZE), page, profile);
    }

    /**
    * 校验密钥是否可解密该数据库（明文 SQLite 视为通过）。
    *
    * @param source 数据库文件
    * @param encKey 32 字节加密密钥，可为 null
    * @return 密钥可用返回 true
    */
    public static boolean isKeyValid(File source, byte[] encKey) {
        if (source == null || !source.isFile()) {
            return false;
        }
        if (isPlainSqlite(source)) {
            return true;
        }
        return detectProfile(source, encKey) != null;
    }

    /**
    * 探测数据库的加密形态与密钥有效性。
    *
    * @param source 数据库文件
    * @param encKey 32 字节加密密钥，为 null 时仅判断是否明文
    * @return 探测结果
    * @throws IOException 读取文件失败
    */
    public static Detection detect(File source, byte[] encKey) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("数据库文件不存在: "
                    + (source == null ? "null" : source.getAbsolutePath()));
        }
        long size = source.length();
        if (isPlainSqlite(source)) {
            int pageSize = readPlainPageSize(source);
            int pageCount = pageSize > 0 ? (int) Math.ceil((double) size / pageSize) : 0;
            return new Detection(true, true, null, pageSize, pageCount);
        }
        SqlCipherProfile profile = detectProfile(source, encKey);
        if (profile == null) {
            return new Detection(false, false, null, 0, 0);
        }
        return new Detection(false, true, profile, profile.pageSize(),
                (int) Math.ceil((double) size / profile.pageSize()));
    }

    // ==================== 密钥解析 ====================

    /**
    * 解析十六进制密钥文本为 32 字节加密密钥。
    *
    * <p>兼容 {@code x'...'} 包裹、{@code 0x} 前缀、空白字符，以及携带 salt 的
    * 96 位十六进制形态（取前 64 位）。</p>
    *
    * @param raw 密钥文本
    * @return 32 字节加密密钥
    * @throws IllegalArgumentException 密钥为空、含非十六进制字符或长度不合法
    */
    public static byte[] parseHexKey(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("数据库密钥不能为空");
        }
        String text = raw.trim();
        if (text.length() > 4 && text.regionMatches(true, 0, "x'", 0, 2) && text.endsWith("'")) {
            text = text.substring(2, text.length() - 1);
        } else if (text.regionMatches(true, 0, "0x", 0, 2)) {
            text = text.substring(2);
        }
        text = text.replaceAll("\\s", "");
        if (text.isEmpty() || text.length() % 2 != 0) {
            throw new IllegalArgumentException("密钥必须是偶数长度的十六进制字符串，当前长度 " + text.length());
        }
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("密钥包含非十六进制字符: "
                        + text.charAt(i * 2) + text.charAt(i * 2 + 1));
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        if (bytes.length == SqlCipherProfile.KEY_SIZE) {
            return bytes;
        }
        if (bytes.length == SqlCipherProfile.KEY_SIZE + SqlCipherProfile.SALT_SIZE) {
            log.debug("密钥携带 salt（{} 字节），仅取前 {} 字节作为加密密钥",
                    bytes.length, SqlCipherProfile.KEY_SIZE);
            return Arrays.copyOf(bytes, SqlCipherProfile.KEY_SIZE);
        }
        throw new IllegalArgumentException("密钥长度不合法：期望 " + SqlCipherProfile.KEY_SIZE
                + " 字节（64 位 hex，允许携带 16 字节 salt 的 96 位 hex），实际 " + bytes.length + " 字节");
    }

    // ==================== 解密 ====================

    /**
    * 解密数据库到指定文件（自动探测参数档案并校验密钥）。
    *
    * @param source 加密数据库文件
    * @param encKey 32 字节加密密钥
    * @param target 解密输出文件
    * @return 解密输出文件
    * @throws IOException              读写失败
    * @throws GeneralSecurityException 密码学操作失败
    */
    public static File decrypt(File source, byte[] encKey, File target)
            throws IOException, GeneralSecurityException {
        if (isPlainSqlite(source)) {
            copyPlain(source, target);
            return target;
        }
        requireKey(encKey);
        SqlCipherProfile profile = detectProfile(source, encKey);
        if (profile == null) {
            throw new IllegalArgumentException("密钥与数据库不匹配：首页 HMAC 校验未通过，"
                    + "或页布局不在候选档案内。数据库: " + source.getAbsolutePath());
        }
        return decrypt(source, encKey, profile, true, target);
    }

    /**
    * 解密数据库到指定文件（显式指定参数档案）。
    *
    * @param source  加密数据库文件
    * @param encKey  32 字节加密密钥
    * @param profile SQLCipher 参数档案
    * @param verify  是否在写出前校验首页结构
    * @param target  解密输出文件
    * @return 解密输出文件
    * @throws IOException              读写失败
    * @throws GeneralSecurityException 密码学操作失败
    */
    public static File decrypt(File source, byte[] encKey, SqlCipherProfile profile, boolean verify, File target)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(source, "数据库文件不能为空");
        Objects.requireNonNull(target, "解密输出文件不能为空");
        Objects.requireNonNull(profile, "SQLCipher 参数档案不能为空");
        if (!source.isFile()) {
            throw new IllegalArgumentException("数据库文件不存在: " + source.getAbsolutePath());
        }
        if (isPlainSqlite(source)) {
            copyPlain(source, target);
            return target;
        }
        requireKey(encKey);
        long size = source.length();
        if (size < profile.pageSize()) {
            throw new IOException("数据库文件不足一页（" + size + " < " + profile.pageSize() + "）: "
                    + source.getAbsolutePath());
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建解密输出目录失败: " + parent.getAbsolutePath());
        }

        long totalPages = (size + profile.pageSize() - 1) / profile.pageSize();
        byte[] page = new byte[profile.pageSize()];
        ByteBuffer buffer = ByteBuffer.allocate(profile.pageSize());
        try (FileChannel channel = FileChannel.open(source.toPath(), StandardOpenOption.READ);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()), BUFFER_SIZE)) {
            for (long pageNumber = 1; pageNumber <= totalPages; pageNumber++) {
                buffer.clear();
                int read = readFully(channel, buffer);
                if (read <= 0) {
                    break;
                }
                // 末页不足时零填充，保持 SQLite 页对齐
                Arrays.fill(page, (byte) 0);
                System.arraycopy(buffer.array(), 0, page, 0, read);
                byte[] plain = pageNumber == 1
                        ? decryptFirstPage(page, encKey, profile)
                        : decryptPage(page, encKey, profile);
                if (pageNumber == 1 && verify && !startsWithMagic(plain)) {
                    throw new GeneralSecurityException("首页解密后未得到 SQLite 文件头，密钥或页布局不正确: "
                            + source.getAbsolutePath());
                }
                out.write(plain);
            }
        }
        log.info("SQLCipher 解密完成: {} -> {} ({} 页, 档案 {})",
                source.getName(), target.getName(), totalPages, profile.name());
        return target;
    }

    /**
    * 解密到系统临时目录下的临时文件（自动探测档案）。
    *
    * @param source 加密数据库文件
    * @param encKey 32 字节加密密钥
    * @return 临时明文数据库文件
    * @throws IOException              读写失败
    * @throws GeneralSecurityException 密码学操作失败
    */
    public static File decryptToTemp(File source, byte[] encKey) throws IOException, GeneralSecurityException {
        File temp = Files.createTempFile("wechat-sqlcipher-", "-" + source.getName()).toFile();
        try {
            return decrypt(source, encKey, temp);
        } catch (IOException | GeneralSecurityException e) {
            Files.deleteIfExists(temp.toPath());
            throw e;
        }
    }

    // ==================== 密钥派生与页面解密 ====================

    /**
    * PBKDF2 密钥派生（RFC 2898，手动实现以避免 JDK 内部实现差异）。
    *
    * @param macAlgorithm HMAC 算法名，如 {@code HmacSHA512}
    * @param password     口令字节
    * @param salt         盐字节
    * @param iterations   迭代次数
    * @param length       派生密钥长度（字节）
    * @return 派生密钥
    * @throws GeneralSecurityException 算法不可用
    */
    static byte[] pbkdf2(String macAlgorithm, byte[] password, byte[] salt, int iterations, int length)
            throws GeneralSecurityException {
        if (iterations < 1) {
            throw new IllegalArgumentException("PBKDF2 迭代次数必须大于 0");
        }
        Mac mac = initMac(macAlgorithm, password);
        int hLen = mac.getMacLength();
        int blocks = (length + hLen - 1) / hLen;
        byte[] derived = new byte[blocks * hLen];
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        for (int i = 1; i <= blocks; i++) {
            block[salt.length] = (byte) (i >>> 24);
            block[salt.length + 1] = (byte) (i >>> 16);
            block[salt.length + 2] = (byte) (i >>> 8);
            block[salt.length + 3] = (byte) i;
            mac.update(block);
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = mac.doFinal(u);
                for (int k = 0; k < hLen; k++) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, derived, (i - 1) * hLen, hLen);
        }
        return Arrays.copyOf(derived, length);
    }

    /**
    * 派生页面 HMAC 密钥。
    *
    * @param encKey  32 字节加密密钥
    * @param salt    文件头 16 字节 salt
    * @param profile 参数档案
    * @return HMAC 密钥
    * @throws GeneralSecurityException 算法不可用
    */
    private static byte[] deriveHmacKey(byte[] encKey, byte[] salt, SqlCipherProfile profile)
            throws GeneralSecurityException {
        byte[] macSalt = new byte[salt.length];
        for (int i = 0; i < salt.length; i++) {
            macSalt[i] = (byte) (salt[i] ^ HMAC_SALT_XOR);
        }
        return pbkdf2(profile.hmacAlgorithm(), encKey, macSalt,
                HMAC_KEY_ITERATIONS, SqlCipherProfile.KEY_SIZE);
    }

    /**
    * 初始化 HMAC。
    *
    * @param algorithm HMAC 算法名
    * @param key       密钥字节
    * @return 已初始化的 Mac 实例
    * @throws GeneralSecurityException 算法不可用
    */
    private static Mac initMac(String algorithm, byte[] key) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac;
    }

    /**
    * 解密首页：跳过明文 salt，补回 SQLite 文件头。
    *
    * @param page    原始加密页
    * @param encKey  加密密钥
    * @param profile 参数档案
    * @return 明文页
    * @throws GeneralSecurityException 解密失败
    */
    private static byte[] decryptFirstPage(byte[] page, byte[] encKey, SqlCipherProfile profile)
            throws GeneralSecurityException {
        byte[] plain = aesDecrypt(encKey, ivOf(page, profile),
                Arrays.copyOfRange(page, SqlCipherProfile.SALT_SIZE, profile.cipherSize()));
        byte[] result = new byte[profile.pageSize()];
        System.arraycopy(SQLITE_MAGIC, 0, result, 0, SqlCipherProfile.SALT_SIZE);
        System.arraycopy(plain, 0, result, SqlCipherProfile.SALT_SIZE, plain.length);
        return result;
    }

    /**
    * 解密非首页。
    *
    * @param page    原始加密页
    * @param encKey  加密密钥
    * @param profile 参数档案
    * @return 明文页
    * @throws GeneralSecurityException 解密失败
    */
    private static byte[] decryptPage(byte[] page, byte[] encKey, SqlCipherProfile profile)
            throws GeneralSecurityException {
        byte[] plain = aesDecrypt(encKey, ivOf(page, profile),
                Arrays.copyOfRange(page, 0, profile.cipherSize()));
        byte[] result = new byte[profile.pageSize()];
        System.arraycopy(plain, 0, result, 0, plain.length);
        return result;
    }

    /**
    * AES-256-CBC 解密。
    *
    * @param encKey     密钥
    * @param iv         初始化向量
    * @param cipherText 密文
    * @return 明文
    * @throws GeneralSecurityException 解密失败
    */
    private static byte[] aesDecrypt(byte[] encKey, byte[] iv, byte[] cipherText) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_CBC_NO_PADDING);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(cipherText);
    }

    /**
    * 取页面 IV（位于密文区之后、HMAC 之前）。
    *
    * @param page    页面字节
    * @param profile 参数档案
    * @return 16 字节 IV
    */
    private static byte[] ivOf(byte[] page, SqlCipherProfile profile) {
        int offset = profile.pageSize() - profile.reserveSize();
        return Arrays.copyOfRange(page, offset, offset + SqlCipherProfile.IV_SIZE);
    }

    /**
    * 校验页面 HMAC。
    *
    * @param page       页面字节
    * @param macKey     HMAC 密钥
    * @param profile    参数档案
    * @param firstPage  是否首页
    * @param pageNumber 页号（从 1 开始）
    * @return 校验通过返回 true
    * @throws GeneralSecurityException HMAC 初始化失败
    */
    private static boolean hmacMatches(byte[] page, byte[] macKey, SqlCipherProfile profile,
                                      boolean firstPage, int pageNumber) throws GeneralSecurityException {
        return hmacMatches(page, initMac(profile.hmacAlgorithm(), macKey), profile, firstPage, pageNumber);
    }

    /**
    * 校验页面 HMAC（复用已初始化的 Mac）。
    *
    * @param page       页面字节
    * @param mac        已初始化的 Mac
    * @param profile    参数档案
    * @param firstPage  是否首页
    * @param pageNumber 页号
    * @return 校验通过返回 true
    */
    private static boolean hmacMatches(byte[] page, Mac mac, SqlCipherProfile profile,
                                      boolean firstPage, int pageNumber) {
        int offset = firstPage ? SqlCipherProfile.SALT_SIZE : 0;
        int length = profile.pageSize() - profile.hmacSize() - offset;
        int hmacOffset = profile.pageSize() - profile.hmacSize();
        if (offset + length > page.length || hmacOffset + profile.hmacSize() > page.length) {
            return false;
        }
        mac.update(page, offset, length);
        mac.update(pageNumberLe(pageNumber));
        byte[] expected = Arrays.copyOfRange(page, hmacOffset, profile.pageSize());
        return MessageDigest.isEqual(mac.doFinal(), expected);
    }

    /**
    * 按候选档案探测参数与密钥是否匹配。
    *
    * @param source 数据库文件
    * @param encKey 32 字节密钥，为 null 时返回 null
    * @return 命中的参数档案，未命中返回 null
    */
    private static SqlCipherProfile detectProfile(File source, byte[] encKey) {
        if (source == null || encKey == null || encKey.length != SqlCipherProfile.KEY_SIZE) {
            return null;
        }
        long size = source.length();
        for (SqlCipherProfile profile : SqlCipherProfile.candidates()) {
            if (size < profile.pageSize()) {
                continue;
            }
            try {
                byte[] page = readPage(source, 0, profile.pageSize());
                byte[] salt = Arrays.copyOf(page, SqlCipherProfile.SALT_SIZE);
                if (!hmacMatches(page, deriveHmacKey(encKey, salt, profile), profile, true, 1)) {
                    continue;
                }
                if (!structureMatches(decryptFirstPage(page, encKey, profile), profile)) {
                    continue;
                }
                return profile;
            } catch (IOException | GeneralSecurityException e) {
                log.debug("SQLCipher 档案 {} 探测失败: {}", profile.name(), e.getMessage());
            }
        }
        return null;
    }

    /**
    * 校验解密后的首页结构是否符合参数档案。
    *
    * @param plainPage 解密后的首页
    * @param profile   参数档案
    * @return 结构一致返回 true
    */
    private static boolean structureMatches(byte[] plainPage, SqlCipherProfile profile) {
        if (plainPage.length <= OFFSET_RESERVED || !startsWithMagic(plainPage)) {
            return false;
        }
        int declared = ((plainPage[OFFSET_PAGE_SIZE] & 0xFF) << 8) | (plainPage[OFFSET_PAGE_SIZE + 1] & 0xFF);
        int pageSize = declared == 1 ? PAGE_SIZE_64K : declared;
        if (pageSize != profile.pageSize()) {
            return false;
        }
        int reserved = plainPage[OFFSET_RESERVED] & 0xFF;
        if (reserved != profile.reserveSize()) {
            log.debug("首页 reserved={} 与档案 {} 保留区 {} 不一致", reserved, profile.name(), profile.reserveSize());
        }
        return true;
    }

    /**
    * 判断字节数组是否以 SQLite 文件头开头。
    *
    * @param bytes 字节数组
    * @return 是明文 SQLite 返回 true
    */
    private static boolean startsWithMagic(byte[] bytes) {
        if (bytes == null || bytes.length < SQLITE_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < SQLITE_MAGIC.length; i++) {
            if (bytes[i] != SQLITE_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
    * 读取明文 SQLite 的页大小。
    *
    * @param source 明文数据库文件
    * @return 页大小，读取失败返回 0
    */
    private static int readPlainPageSize(File source) {
        try {
            byte[] head = readPage(source, 0, HEADER_PROBE_LENGTH);
            if (!startsWithMagic(head)) {
                return 0;
            }
            int declared = ((head[OFFSET_PAGE_SIZE] & 0xFF) << 8) | (head[OFFSET_PAGE_SIZE + 1] & 0xFF);
            return declared == 1 ? PAGE_SIZE_64K : declared;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
    * 页号的 4 字节小端表示。
    *
    * @param pageNumber 页号
    * @return 小端字节数组
    */
    private static byte[] pageNumberLe(int pageNumber) {
        return new byte[]{
                (byte) pageNumber,
                (byte) (pageNumber >>> 8),
                (byte) (pageNumber >>> 16),
                (byte) (pageNumber >>> 24)
        };
    }

    /**
    * 校验密钥长度。
    *
    * @param encKey 加密密钥
    */
    private static void requireKey(byte[] encKey) {
        if (encKey == null || encKey.length != SqlCipherProfile.KEY_SIZE) {
            throw new IllegalArgumentException("加密密钥必须是 " + SqlCipherProfile.KEY_SIZE
                    + " 字节，当前为 " + (encKey == null ? "null" : encKey.length));
        }
    }

    /**
    * 校验文件可读。
    *
    * @param source 文件
    */
    private static void requireReadable(File source) {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("数据库文件不存在: "
                    + (source == null ? "null" : source.getAbsolutePath()));
        }
    }

    /**
    * 复制明文数据库。
    *
    * @param source 源文件
    * @param target 目标文件
    * @throws IOException 复制失败
    */
    private static void copyPlain(File source, File target) throws IOException {
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建输出目录失败: " + parent.getAbsolutePath());
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
    * 从文件读取指定长度的页面（不足部分零填充）。
    *
    * @param source 文件
    * @param offset 起始偏移
    * @param length 读取长度
    * @return 页面字节
    * @throws IOException 读取失败
    */
    private static byte[] readPage(File source, long offset, int length) throws IOException {
        try (FileChannel channel = FileChannel.open(source.toPath(), StandardOpenOption.READ)) {
            return readPage(channel, offset, length);
        }
    }

    /**
    * 从通道读取指定长度的页面（不足部分零填充）。
    *
    * @param channel 文件通道
    * @param offset  起始偏移
    * @param length  读取长度
    * @return 页面字节
    * @throws IOException 读取失败
    */
    private static byte[] readPage(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        return buffer.array();
    }

    /**
    * 读满缓冲区（顺序读）。
    *
    * @param channel 文件通道
    * @param buffer  目标缓冲区
    * @return 实际读取字节数
    * @throws IOException 读取失败
    */
    private static int readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
    * 数据库加密形态探测结果。
    *
    * @param plaintext 是否已是明文 SQLite
    * @param keyValid  密钥是否可用
    * @param profile   命中的参数档案，明文或未命中时为 null
    * @param pageSize  页大小
    * @param pageCount 页数
    */
    public record Detection(boolean plaintext, boolean keyValid, SqlCipherProfile profile,
                            int pageSize, int pageCount) {
    }

    /**
    * 密钥探针：缓存首页与 salt，用于批量校验候选密钥。
    *
    * @param salt    文件头 16 字节 salt
    * @param page    首页原始字节
    * @param profile 参数档案
    * @return 结果值
    */
    public record KeyProbe(byte[] salt, byte[] page, SqlCipherProfile profile) {

        /**
        * 校验候选密钥是否通过首页 HMAC 校验。
        *
        * @param encKey 32 字节候选密钥
        * @return 通过返回 true
        */
        public boolean accepts(byte[] encKey) {
            if (encKey == null || encKey.length != SqlCipherProfile.KEY_SIZE) {
                return false;
            }
            try {
                return hmacMatches(page, deriveHmacKey(encKey, salt, profile), profile, true, 1);
            } catch (GeneralSecurityException e) {
                log.debug("密钥探针校验失败: {}", e.getMessage());
                return false;
            }
        }
    }
}
