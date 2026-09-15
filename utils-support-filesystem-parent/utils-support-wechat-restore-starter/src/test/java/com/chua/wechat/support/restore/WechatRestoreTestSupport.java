package com.chua.wechat.support.restore;

import com.chua.wechat.support.restore.sqlcipher.SqlCipherProfile;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

/**
 * 微信还原测试夹具。
 *
 * <p>提供三项能力：</p>
 * <ol>
 *   <li>构造普通明文 SQLite 数据库（{@code reserved = 0}），供 JDBC 导出测试使用；</li>
 *   <li>构造 <b>SQLCipher 页布局</b>的明文数据库（{@code reserved = 80}），
 *       使每页末尾 80 字节成为真正的保留区，从而可以无损加密；</li>
 *   <li>按 SQLCipher 4 规范加密明文库。加密流程为<b>独立实现</b>，
 *       不复用 {@link com.chua.wechat.support.restore.sqlcipher.SqlCipherDecryptor}，
 *       避免「用被测代码验证被测代码」。</li>
 * </ol>
 *
 * <h3>为什么需要「SQLCipher 页布局」</h3>
 * <p>SQLCipher 固定保留每页末尾 80 字节存放 {@code IV(16) + HMAC(64)}，
 * 因此其明文库的可用区只有 {@code pageSize - 80} 字节，单元格内容永远不会落到末尾 80 字节。
 * 而普通 SQLite 库的 {@code reserved = 0}，单元格内容区恰好贴着页尾生长，
 * 直接按 SQLCipher 口径加密会丢掉末尾 80 字节的真实数据。</p>
 *
 * <p>SQLite 未提供运行时修改保留区的 PRAGMA，因此
 * {@link #createReservedLayoutSqliteDatabase(File)} 采用页重排：
 * 把每页 {@code [内容区起点, 页尾)} 整体上移 80 字节，并同步修正
 * 「内容区起点」字段与单元格指针数组，最后把文件头 offset 20 的保留区字段置为 80。
 * 重排后的文件仍是 SQLite 可正常打开的合法数据库。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class WechatRestoreTestSupport {

    /**
     * 测试用数据库页大小（与 SQLite 默认值一致）
     */
    private static final int PAGE_SIZE = 4096;

    /**
     * SQLCipher 保留区大小：IV(16) + HMAC-SHA512(64)
     */
    private static final int RESERVE_SIZE = 80;

    /**
     * 文件头「保留区字节数」字段偏移
     */
    private static final int OFFSET_RESERVED = 20;

    /**
     * 文件头「页大小」字段偏移
     */
    private static final int OFFSET_PAGE_SIZE = 16;

    /**
     * 首页文件头长度（b-tree 页头从 100 开始）
     */
    private static final int FIRST_PAGE_HEADER = 100;

    /**
     * 叶子页 b-tree 页头长度
     */
    private static final int LEAF_HEADER_SIZE = 8;

    /**
     * 内部页 b-tree 页头长度
     */
    private static final int INTERIOR_HEADER_SIZE = 12;

    /**
     * HMAC 密钥派生迭代次数（SQLCipher 固定值）
     */
    private static final int HMAC_KEY_ITERATIONS = 2;

    /**
     * HMAC salt 异或掩码
     */
    private static final byte HMAC_SALT_XOR = 0x3A;

    /**
     * 工具类禁止实例化。
     */
    private WechatRestoreTestSupport() {
    }

    /**
     * 构造普通明文 SQLite 数据库（{@code reserved = 0}），含 {@code MSG} 与 {@code Contact} 两张表。
     *
     * @param target 目标文件
     * @return 目标文件
     * @throws Exception 建库失败
     */
    public static File createSqliteDatabase(File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.deleteIfExists(target.toPath());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA page_size = " + PAGE_SIZE);
            statement.execute("CREATE TABLE MSG ("
                    + "id INTEGER PRIMARY KEY, talker TEXT, content TEXT, create_time INTEGER)");
            statement.execute("CREATE TABLE Contact (id INTEGER PRIMARY KEY, name TEXT)");
            statement.executeUpdate("INSERT INTO MSG (id, talker, content, create_time) VALUES "
                    + "(1, 'wxid_alice', 'hello wechat', 1700000000),"
                    + "(2, 'wxid_bob', '你好，微信', 1700000001),"
                    + "(3, 'wxid_alice', 'line1,with comma', 1700000002)");
            statement.executeUpdate("INSERT INTO Contact (id, name) VALUES (1, 'Alice')");
        }
        return target;
    }

    /**
     * 构造 SQLCipher 页布局的明文 SQLite 数据库（{@code reserved = 80}）。
     *
     * @param target 目标文件
     * @return 目标文件
     * @throws Exception 建库或页重排失败
     */
    public static File createReservedLayoutSqliteDatabase(File target) throws Exception {
        createSqliteDatabase(target);
        byte[] data = Files.readAllBytes(target.toPath());
        int pageSize = readPageSize(data);
        if (pageSize != PAGE_SIZE) {
            throw new IllegalStateException("测试库页大小为 " + pageSize + "，预期 " + PAGE_SIZE);
        }
        if (data.length == 0 || data.length % pageSize != 0) {
            throw new IllegalStateException("测试库未按页对齐: " + data.length);
        }
        // 文件头声明每页末尾 80 字节为保留区
        data[OFFSET_RESERVED] = (byte) RESERVE_SIZE;

        int pages = data.length / pageSize;
        for (int page = 0; page < pages; page++) {
            relocatePage(data, page, pageSize);
        }
        Files.write(target.toPath(), data);
        return target;
    }

    /**
     * 把单页的内容区整体上移保留区大小，并修正相关偏移。
     *
     * @param data     数据库字节
     * @param page     页序号（从 0 开始）
     * @param pageSize 页大小
     */
    private static void relocatePage(byte[] data, int page, int pageSize) {
        int base = page * pageSize;
        int headerRelative = page == 0 ? FIRST_PAGE_HEADER : 0;
        int header = base + headerRelative;
        int type = data[header] & 0xFF;
        boolean interior = type == 0x02 || type == 0x05;
        int freeblock = readU16(data, header + 1);
        int cells = readU16(data, header + 3);
        int contentStart = readU16(data, header + 5);
        int fragments = data[header + 7] & 0xFF;

        if (freeblock != 0 || fragments != 0) {
            throw new IllegalStateException("测试库第 " + page + " 页存在空洞，无法重排保留区");
        }
        if (contentStart <= 0) {
            throw new IllegalStateException("测试库第 " + page + " 页内容区起点异常: " + contentStart);
        }
        // 全部以「页内相对偏移」比较，避免首页 100 字节文件头带来的错位
        int pointerRelative = headerRelative + (interior ? INTERIOR_HEADER_SIZE : LEAF_HEADER_SIZE);
        if (contentStart - RESERVE_SIZE < pointerRelative + cells * 2) {
            throw new IllegalStateException("测试库第 " + page + " 页过满，无法腾出保留区");
        }

        // 内容区上移（向低地址复制，不会覆盖尚未读取的字节）
        for (int i = base + contentStart; i < base + pageSize; i++) {
            data[i - RESERVE_SIZE] = data[i];
        }
        Arrays.fill(data, base + pageSize - RESERVE_SIZE, base + pageSize, (byte) 0);

        writeU16(data, header + 5, contentStart - RESERVE_SIZE);
        for (int i = 0; i < cells; i++) {
            int pointer = readU16(data, base + pointerRelative + i * 2);
            writeU16(data, base + pointerRelative + i * 2, pointer - RESERVE_SIZE);
        }
    }

    /**
     * 按 SQLCipher 4 规范加密明文数据库。
     *
     * <p>页布局：首 16 字节明文 salt；每页 {@code [密文][IV 16][HMAC 64]}；
     * HMAC 覆盖 {@code 密文 || IV || LE32(页号)}。</p>
     *
     * <p>要求明文库为 {@link #createReservedLayoutSqliteDatabase(File)} 产出的
     * SQLCipher 页布局，即每页末尾 {@code reserveSize} 字节为全零保留区。</p>
     *
     * @param plainFile 明文数据库文件（须页对齐且末尾为保留区）
     * @param target    加密输出文件
     * @param key       32 字节加密密钥
     * @param profile   参数档案
     * @throws Exception 加密失败
     */
    public static void encrypt(File plainFile, File target, byte[] key, SqlCipherProfile profile) throws Exception {
        byte[] plain = Files.readAllBytes(plainFile.toPath());
        int pageSize = profile.pageSize();
        if (plain.length == 0 || plain.length % pageSize != 0) {
            throw new IllegalArgumentException("明文库必须页对齐，当前 " + plain.length + " 字节 / 页 " + pageSize);
        }
        int pages = plain.length / pageSize;
        int reserveSize = profile.reserveSize();
        requireZeroReserve(plain, pages, pageSize, reserveSize);

        byte[] salt = new byte[SqlCipherProfile.SALT_SIZE];
        new SecureRandom().nextBytes(salt);
        byte[] macSalt = new byte[salt.length];
        for (int i = 0; i < salt.length; i++) {
            macSalt[i] = (byte) (salt[i] ^ HMAC_SALT_XOR);
        }
        byte[] macKey = pbkdf2Sha512(key, macSalt, HMAC_KEY_ITERATIONS, SqlCipherProfile.KEY_SIZE);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length)) {
            for (int pageNumber = 1; pageNumber <= pages; pageNumber++) {
                int pageStart = (pageNumber - 1) * pageSize;
                int payloadStart = pageNumber == 1 ? SqlCipherProfile.SALT_SIZE : 0;
                byte[] payload = Arrays.copyOfRange(plain, pageStart + payloadStart, pageStart + pageSize - reserveSize);

                byte[] iv = new byte[SqlCipherProfile.IV_SIZE];
                new SecureRandom().nextBytes(iv);
                byte[] cipherText = aesEncrypt(key, iv, payload);

                if (pageNumber == 1) {
                    out.write(salt);
                }
                out.write(cipherText);
                out.write(iv);

                Mac mac = Mac.getInstance(profile.hmacAlgorithm());
                mac.init(new SecretKeySpec(macKey, profile.hmacAlgorithm()));
                mac.update(cipherText);
                mac.update(iv);
                mac.update(pageNumberLe(pageNumber));
                out.write(mac.doFinal());
            }
            Files.write(target.toPath(), out.toByteArray());
        }
    }

    /**
     * 校验每页末尾保留区是否为全零。
     *
     * @param plain       明文字节
     * @param pages       页数
     * @param pageSize    页大小
     * @param reserveSize 保留区大小
     */
    private static void requireZeroReserve(byte[] plain, int pages, int pageSize, int reserveSize) {
        for (int page = 0; page < pages; page++) {
            int end = (page + 1) * pageSize;
            for (int i = end - reserveSize; i < end; i++) {
                if (plain[i] != 0) {
                    throw new IllegalArgumentException("明文库第 " + page + " 页末尾 " + reserveSize
                            + " 字节不是保留区，请先调用 createReservedLayoutSqliteDatabase 重排页布局");
                }
            }
        }
    }

    /**
     * 读取文件头声明的页大小。
     *
     * @param data 数据库字节
     * @return 页大小
     */
    private static int readPageSize(byte[] data) {
        int declared = readU16(data, OFFSET_PAGE_SIZE);
        return declared == 1 ? 65536 : declared;
    }

    /**
     * 读取 2 字节大端无符号整数。
     *
     * @param data   字节数组
     * @param offset 偏移
     * @return 数值
     */
    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
     * 写入 2 字节大端无符号整数。
     *
     * @param data   字节数组
     * @param offset 偏移
     * @param value  数值
     */
    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    /**
     * 独立的 PBKDF2-HMAC-SHA512 实现（RFC 2898）。
     *
     * @param password   口令
     * @param salt       盐
     * @param iterations 迭代次数
     * @param length     派生长度
     * @return 派生密钥
     * @throws Exception 算法不可用
     */
    private static byte[] pbkdf2Sha512(byte[] password, byte[] salt, int iterations, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));
        int hLen = mac.getMacLength();
        int blocks = (length + hLen - 1) / hLen;
        byte[] derived = new byte[blocks * hLen];
        for (int block = 1; block <= blocks; block++) {
            byte[] input = new byte[salt.length + 4];
            System.arraycopy(salt, 0, input, 0, salt.length);
            input[salt.length] = (byte) (block >>> 24);
            input[salt.length + 1] = (byte) (block >>> 16);
            input[salt.length + 2] = (byte) (block >>> 8);
            input[salt.length + 3] = (byte) block;
            byte[] u = mac.doFinal(input);
            byte[] acc = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < hLen; j++) {
                    acc[j] ^= u[j];
                }
            }
            System.arraycopy(acc, 0, derived, (block - 1) * hLen, hLen);
        }
        return Arrays.copyOf(derived, length);
    }

    /**
     * AES-CBC 加密（无填充）。
     *
     * @param key   密钥
     * @param iv    初始化向量
     * @param plain 明文
     * @return 密文
     * @throws Exception 加密失败
     */
    private static byte[] aesEncrypt(byte[] key, byte[] iv, byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(plain);
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
     * 打开数据库执行简单查询（夹具自检用）。
     *
     * @param db  数据库文件
     * @param sql 查询语句
     * @return 首行首列
     * @throws Exception 查询失败
     */
    public static Object queryFirst(File db, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getObject(1) : null;
        }
    }
}
