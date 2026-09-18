package com.chua.wechat.support.restore.sqlcipher;

import com.chua.wechat.support.restore.WechatRestoreTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlCipherDecryptor} 单元测试。
 *
 * <p>加解密往返所用的加密器来自 {@link WechatRestoreTestSupport}，是 SQLCipher 4 的
 * <b>独立实现</b>，与被测解密器不共享任何代码，因此往返测试具备真正的验证意义。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class SqlCipherDecryptorTest {

    /**
     * 正确的 32 字节密钥（hex）
     */
    private static final String KEY_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 随密钥携带的 16 字节 salt（hex）
     */
    private static final String SALT_HEX = "aabbccddeeff00112233445566778899";

    /**
     * 错误的 32 字节密钥（hex）
     */
    private static final String WRONG_KEY_HEX =
            "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100";

    /**
     * 明文 SQLite 文件头
     */
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * SQLCipher 首页默认页大小
     */
    private static final int PAGE_SIZE = 4096;

    @TempDir
    Path tempDir;

    // ==================== 密钥解析 ====================

    /**
     * 解析Hex键应当解码Plain64Hex。
     */
    @Test
    void parseHexKeyShouldDecodePlain64Hex() {
        byte[] key = SqlCipherDecryptor.parseHexKey(KEY_HEX);
        assertEquals(32, key.length);
        assertEquals(0x00, key[0] & 0xFF);
        assertEquals(0x1F, key[31] & 0xFF);
    }

    /**
     * 解析Hex键应当AcceptUppercaseAndHex前缀。
     */
    @Test
    void parseHexKeyShouldAcceptUppercaseAndHexPrefix() {
        assertArrayEquals(SqlCipherDecryptor.parseHexKey(KEY_HEX),
                SqlCipherDecryptor.parseHexKey("0x" + KEY_HEX.toUpperCase()));
    }

    /**
     * 解析Hex键应当AcceptXQuoteWrapper。
     */
    @Test
    void parseHexKeyShouldAcceptXQuoteWrapper() {
        assertArrayEquals(SqlCipherDecryptor.parseHexKey(KEY_HEX),
                SqlCipherDecryptor.parseHexKey("x'" + KEY_HEX + "'"));
    }

    /**
     * 解析Hex键应当IgnoreWhitespace。
     */
    @Test
    void parseHexKeyShouldIgnoreWhitespace() {
        String spaced = KEY_HEX.substring(0, 32) + " \n\t" + KEY_HEX.substring(32);
        assertArrayEquals(SqlCipherDecryptor.parseHexKey(KEY_HEX),
                SqlCipherDecryptor.parseHexKey(spaced));
    }

    /**
     * 解析Hex键应当StripTrailingSaltFrom96Hex。
     */
    @Test
    void parseHexKeyShouldStripTrailingSaltFrom96Hex() {
        byte[] withSalt = SqlCipherDecryptor.parseHexKey(KEY_HEX + SALT_HEX);
        assertEquals(32, withSalt.length);
        assertArrayEquals(SqlCipherDecryptor.parseHexKey(KEY_HEX), withSalt);
    }

    /**
     * 解析Hex键应当RejectIllegalInput。
     */
    @Test
    void parseHexKeyShouldRejectIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> SqlCipherDecryptor.parseHexKey(null));
        assertThrows(IllegalArgumentException.class, () -> SqlCipherDecryptor.parseHexKey("   "));
        assertThrows(IllegalArgumentException.class, () -> SqlCipherDecryptor.parseHexKey("abc"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlCipherDecryptor.parseHexKey("zz" + KEY_HEX.substring(2)));
        assertThrows(IllegalArgumentException.class, () -> SqlCipherDecryptor.parseHexKey("00112233"));
    }

    // ==================== 明文识别与密钥校验 ====================

    /**
     * 是否PlainSqlite应当DetectPlainAndEncrypted。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void isPlainSqliteShouldDetectPlainAndEncrypted() throws Exception {
        assertTrue(SqlCipherDecryptor.isPlainSqlite(newDb("plain.db")));
        assertTrue(SqlCipherDecryptor.isPlainSqlite(newReservedDb("seed.db")));
        assertFalse(SqlCipherDecryptor.isPlainSqlite(encrypt(newReservedDb("seed.db"), "session.db")));
        assertFalse(SqlCipherDecryptor.isPlainSqlite(new File(tempDir.toFile(), "missing.db")));
    }

    /**
     * 是否键Valid应当AcceptCorrect键AndRejectWrong键。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void isKeyValidShouldAcceptCorrectKeyAndRejectWrongKey() throws Exception {
        File encrypted = encrypt(newReservedDb("seed.db"), "session.db");
        assertTrue(SqlCipherDecryptor.isKeyValid(encrypted, SqlCipherDecryptor.parseHexKey(KEY_HEX)));
        assertFalse(SqlCipherDecryptor.isKeyValid(encrypted, SqlCipherDecryptor.parseHexKey(WRONG_KEY_HEX)));
        assertFalse(SqlCipherDecryptor.isKeyValid(encrypted, null));
    }

    /**
     * 是否键Valid应当TreatPlainDatabaseAsValid。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void isKeyValidShouldTreatPlainDatabaseAsValid() throws Exception {
        assertTrue(SqlCipherDecryptor.isKeyValid(newDb("plain.db"), null));
    }

    // ==================== 探测 ====================

    /**
     * detect应当ReportPlaintextDatabase。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void detectShouldReportPlaintextDatabase() throws Exception {
        File plain = newDb("plain.db");
        SqlCipherDecryptor.Detection detection = SqlCipherDecryptor.detect(plain, null);

        assertTrue(detection.plaintext());
        assertTrue(detection.keyValid());
        assertNull(detection.profile());
        assertEquals(PAGE_SIZE, detection.pageSize());
        assertEquals((int) Math.ceil((double) plain.length() / PAGE_SIZE), detection.pageCount());
    }

    /**
     * detect应当ReportEncryptedDatabaseWithProfile。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void detectShouldReportEncryptedDatabaseWithProfile() throws Exception {
        File encrypted = encrypt(newReservedDb("seed.db"), "session.db");
        SqlCipherDecryptor.Detection detection =
                SqlCipherDecryptor.detect(encrypted, SqlCipherDecryptor.parseHexKey(KEY_HEX));

        assertFalse(detection.plaintext());
        assertTrue(detection.keyValid());
        assertNotNull(detection.profile());
        assertEquals("wechat-4", detection.profile().name());
        assertEquals(PAGE_SIZE, detection.pageSize());
        assertEquals(encrypted.length() / PAGE_SIZE, detection.pageCount());
    }

    /**
     * detect应当ReportFailureForWrong键。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void detectShouldReportFailureForWrongKey() throws Exception {
        File encrypted = encrypt(newReservedDb("seed.db"), "session.db");
        SqlCipherDecryptor.Detection detection =
                SqlCipherDecryptor.detect(encrypted, SqlCipherDecryptor.parseHexKey(WRONG_KEY_HEX));

        assertFalse(detection.plaintext());
        assertFalse(detection.keyValid());
        assertNull(detection.profile());
    }

    /**
     * detect应当RejectMissing文件。
     */
    @Test
    void detectShouldRejectMissingFile() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlCipherDecryptor.detect(new File(tempDir.toFile(), "missing.db"), null));
    }

    // ==================== 密钥探针 ====================

    /**
     * 键Probe应当AcceptOnlyCorrect键。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void keyProbeShouldAcceptOnlyCorrectKey() throws Exception {
        File encrypted = encrypt(newReservedDb("seed.db"), "session.db");
        SqlCipherDecryptor.KeyProbe probe = SqlCipherDecryptor.keyProbe(encrypted);

        assertTrue(probe.accepts(SqlCipherDecryptor.parseHexKey(KEY_HEX)));
        assertFalse(probe.accepts(SqlCipherDecryptor.parseHexKey(WRONG_KEY_HEX)));
        assertFalse(probe.accepts(new byte[8]));
        assertFalse(probe.accepts(null));
        assertEquals(SqlCipherProfile.SALT_SIZE, probe.salt().length);
        assertEquals(PAGE_SIZE, probe.page().length);
    }

    /**
     * 键Probe应当Reject文件SmallerThanOne页。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void keyProbeShouldRejectFileSmallerThanOnePage() throws Exception {
        File tiny = new File(tempDir.toFile(), "tiny.db");
        Files.write(tiny.toPath(), new byte[100]);

        assertThrows(IOException.class, () -> SqlCipherDecryptor.keyProbe(tiny));
    }

    // ==================== 解密 ====================

    /**
     * decrypt应当RestorePlaintextSqlite。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void decryptShouldRestorePlaintextSqlite() throws Exception {
        File plain = newReservedDb("seed.db");
        File encrypted = encrypt(plain, "session.db");
        byte[] key = SqlCipherDecryptor.parseHexKey(KEY_HEX);

        File decrypted = SqlCipherDecryptor.decryptToTemp(encrypted, key);
        try {
            assertEquals(plain.length(), decrypted.length(), "解密后长度应与原始明文库一致");
            byte[] bytes = Files.readAllBytes(decrypted.toPath());
            assertArrayEquals(SQLITE_MAGIC, Arrays.copyOf(bytes, SQLITE_MAGIC.length),
                    "解密后应以 SQLite 文件头开头");
            assertEquals(0x10, bytes[16] & 0xFF, "文件头页大小高位应为 0x10（4096）");
            assertEquals(0x00, bytes[17] & 0xFF);
            assertEquals(80, bytes[20] & 0xFF, "解密后应保留 SQLCipher 的 80 字节保留区声明");
            assertArrayEquals(Files.readAllBytes(plain.toPath()), bytes, "解密结果应与原始明文库逐字节一致");

            assertEquals(3, countRows(decrypted, "MSG"));
            assertEquals(1, countRows(decrypted, "Contact"));
            assertEquals("你好，微信", queryContent(decrypted, 2));
            assertEquals("line1,with comma", queryContent(decrypted, 3));
        } finally {
            Files.deleteIfExists(decrypted.toPath());
        }
    }

    /**
     * decrypt应当Restore转为Explicit目标。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void decryptShouldRestoreToExplicitTarget() throws Exception {
        File plain = newReservedDb("seed.db");
        File encrypted = encrypt(plain, "session.db");
        File target = new File(tempDir.toFile(), "nested/out.db");

        File result = SqlCipherDecryptor.decrypt(encrypted,
                SqlCipherDecryptor.parseHexKey(KEY_HEX), target);

        assertEquals(target, result);
        assertTrue(target.isFile());
        assertEquals(plain.length(), target.length());
    }

    /**
     * decrypt应当RejectWrong键。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void decryptShouldRejectWrongKey() throws Exception {
        File encrypted = encrypt(newReservedDb("seed.db"), "session.db");
        File target = new File(tempDir.toFile(), "out.db");

        assertThrows(IllegalArgumentException.class, () -> SqlCipherDecryptor.decrypt(encrypted,
                SqlCipherDecryptor.parseHexKey(WRONG_KEY_HEX), target));
    }

    /**
     * decrypt应当RejectShort键。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void decryptShouldRejectShortKey() throws Exception {
        File encrypted = encrypt(newReservedDb("seed.db"), "session.db");
        File target = new File(tempDir.toFile(), "out.db");

        assertThrows(IllegalArgumentException.class,
                () -> SqlCipherDecryptor.decrypt(encrypted, new byte[16], target));
    }

    /**
     * decrypt应当复制PlainDatabaseVerbatim。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void decryptShouldCopyPlainDatabaseVerbatim() throws Exception {
        File plain = newDb("plain.db");
        File target = new File(tempDir.toFile(), "copy.db");

        File result = SqlCipherDecryptor.decrypt(plain, null, target);

        assertEquals(target, result);
        assertArrayEquals(Files.readAllBytes(plain.toPath()), Files.readAllBytes(target.toPath()));
    }

    // ==================== 密钥派生 ====================

    /**
     * pbkdf2应当MatchSingleIterationHmac。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void pbkdf2ShouldMatchSingleIterationHmac() throws Exception {
        byte[] password = SqlCipherDecryptor.parseHexKey(KEY_HEX);
        byte[] salt = "test-salt".getBytes(StandardCharsets.US_ASCII);

        byte[] derived = SqlCipherDecryptor.pbkdf2("HmacSHA512", password, salt, 1, 32);

        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));
        mac.update(salt);
        mac.update(new byte[]{0, 0, 0, 1});
        assertArrayEquals(Arrays.copyOf(mac.doFinal(), 32), derived,
                "迭代 1 次时 PBKDF2 应等于 HMAC(password, salt || BE32(1))");
    }

    /**
     * pbkdf2应当RejectNonPositiveIterations。
     */
    @Test
    void pbkdf2ShouldRejectNonPositiveIterations() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlCipherDecryptor.pbkdf2("HmacSHA512", new byte[32], new byte[16], 0, 32));
    }

    // ==================== 夹具 ====================

    /**
     * 构造明文测试库。
     *
     * @param name 文件名
     * @return 明文数据库文件
     * @throws Exception 建库失败
     */
    private File newDb(String name) throws Exception {
        return WechatRestoreTestSupport.createSqliteDatabase(new File(tempDir.toFile(), name));
    }

    /**
     * 构造 SQLCipher 页布局（保留区 80 字节）的明文测试库。
     *
     * @param name 文件名
     * @return 明文数据库文件
     * @throws Exception 建库失败
     */
    private File newReservedDb(String name) throws Exception {
        return WechatRestoreTestSupport.createReservedLayoutSqliteDatabase(new File(tempDir.toFile(), name));
    }

    /**
     * 用正确密钥加密明文库。
     *
     * @param plain 明文数据库
     * @param name  加密输出文件名
     * @return 加密数据库文件
     * @throws Exception 加密失败
     */
    private File encrypt(File plain, String name) throws Exception {
        File target = new File(tempDir.toFile(), name);
        WechatRestoreTestSupport.encrypt(plain, target, SqlCipherDecryptor.parseHexKey(KEY_HEX),
                SqlCipherProfile.candidates().getFirst());
        return target;
    }

    /**
     * 统计表行数。
     *
     * @param db    数据库文件
     * @param table 表名
     * @return 行数
     * @throws Exception 查询失败
     */
    private static int countRows(File db, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    /**
     * 查询指定 id 的 content 列。
     *
     * @param db 数据库文件
     * @param id 主键
     * @return content 值
     * @throws Exception 查询失败
     */
    private static String queryContent(File db, int id) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT content FROM MSG WHERE id = " + id)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
