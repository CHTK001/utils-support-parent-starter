package com.chua.wechat.support.restore;

import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.sqlcipher.SqlCipherDecryptor;
import com.chua.wechat.support.restore.sqlcipher.SqlCipherProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLCipher 直解路径端到端测试（加密库 → 解密 → 导出）。
 *
 * <p>走的是 {@link WechatDataRestore#restore(File, DataRestoreConfig)} 公开入口，
 * 覆盖 {@code mode=sqlcipher}、{@code key}、{@code decrypt.dir} 三个关键配置，
 * 以及密钥缺失、密钥错误两类失败场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatDataRestoreSqlCipherTest {

    /**
     * 正确的 32 字节密钥（hex）
     */
    private static final String KEY_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 错误的 32 字节密钥（hex）
     */
    private static final String WRONG_KEY_HEX =
            "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100";

    /**
     * 明文 SQLite 文件头
     */
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    @Test
    void sqlcipherModeShouldRestoreEncryptedDatabaseEndToEnd() throws Exception {
        File dataDir = newDir("data");
        File encrypted = encryptFixture(dataDir);
        File out = newDir("out");
        File plainDir = newDir("plain");

        DataRestoreResult result = restore(encrypted, options(KEY_HEX, plainDir, out), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount());
        assertEquals(2, result.getOutputFiles().size());

        File msgCsv = new File(out, "session__MSG.csv");
        assertTrue(msgCsv.isFile(), "缺少 MSG 导出文件");
        String csv = Files.readString(msgCsv.toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("id,talker,content,create_time"));
        assertTrue(csv.contains("你好，微信"));

        // decrypt.dir 生效：明文库应被保留且是合法 SQLite
        File decrypted = new File(plainDir, "session.db");
        assertTrue(decrypted.isFile(), "decrypt.dir 未保留明文库");
        byte[] head = Files.readAllBytes(decrypted.toPath());
        assertEquals(encrypted.length(), head.length);
        for (int i = 0; i < SQLITE_MAGIC.length; i++) {
            assertEquals(SQLITE_MAGIC[i], head[i], "第 " + i + " 字节不是 SQLite 文件头");
        }
    }

    @Test
    void sqlcipherModeShouldAcceptPlaintextDatabase() throws Exception {
        File dataDir = newDir("data");
        File plain = new File(dataDir, "session.db");
        WechatRestoreTestSupport.createSqliteDatabase(plain);
        File out = newDir("out");
        File plainDir = newDir("plain");

        DataRestoreResult result = restore(plain, options(KEY_HEX, plainDir, out), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount());
        assertEquals(0, listFiles(plainDir).size(), "明文库无需解密，不应写出明文副本");
    }

    @Test
    void sqlcipherModeShouldFailOnWrongKey() throws Exception {
        File dataDir = newDir("data");
        File encrypted = encryptFixture(dataDir);
        File out = newDir("out");
        File plainDir = newDir("plain");

        DataRestoreResult result = restore(encrypted, options(WRONG_KEY_HEX, plainDir, out), out);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("SQLCipher 直解未生成任何文件"),
                "实际错误: " + result.getErrorMessage());
    }

    @Test
    void sqlcipherModeShouldFailWithoutKey() throws Exception {
        File dataDir = newDir("data");
        File encrypted = encryptFixture(dataDir);
        File out = newDir("out");

        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_SQLCIPHER);
        DataRestoreResult result = restore(encrypted, options, out);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("密钥"), "实际错误: " + result.getErrorMessage());
    }

    @Test
    void sqlcipherModeShouldFailWhenNoDatabaseFound() throws Exception {
        File dataDir = newDir("data");
        File placeholder = new File(dataDir, "note.txt");
        Files.writeString(placeholder.toPath(), "not a database");
        File out = newDir("out");

        DataRestoreResult result = restore(placeholder, options(KEY_HEX, null, out), out);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到可还原的微信数据库文件"),
                "实际错误: " + result.getErrorMessage());
    }

    // ==================== auto 模式收敛 ====================

    @Test
    void autoModeShouldSucceedViaSqlCipherWhenKeyAvailable() throws Exception {
        File dataDir = newDir("data");
        File encrypted = encryptFixture(dataDir);
        File out = newDir("out");

        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_AUTO);
        options.put(WechatDataRestore.OPTION_KEY, KEY_HEX);

        DataRestoreResult result = new WechatDataRestore().doRestore(encrypted, autoConfig(options, out));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount());
    }

    @Test
    void autoModeShouldThrowWhenSqlCipherFailsAndNoToolFallback() throws Exception {
        File dataDir = newDir("data");
        File encrypted = encryptFixture(dataDir);
        File out = newDir("out");

        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_AUTO);
        options.put(WechatDataRestore.OPTION_KEY, WRONG_KEY_HEX);
        // 本机微信运行时内存明文页路径是可用的，会接管 auto 降级；
        // 这里显式禁用它，才能验证「所有路径都失败时抛异常」
        options.put(WechatDataRestore.OPTION_MEMORY_ENABLED, Boolean.FALSE);

        // 无 tool 兜底时必须抛出，而不是静默返回失败结果
        assertThrows(IllegalStateException.class,
                () -> new WechatDataRestore().doRestore(encrypted, autoConfig(options, out)));
    }

    @Test
    void autoModeShouldThrowWhenNothingConfigured() {
        File source = new File(tempDir.toFile(), "session.db");
        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_AUTO);
        // 同上：禁用内存路径，确保「什么都没配」时确实无路可走
        options.put(WechatDataRestore.OPTION_MEMORY_ENABLED, Boolean.FALSE);

        assertThrows(IllegalArgumentException.class,
                () -> new WechatDataRestore().doRestore(source, autoConfig(options, tempDir.toFile())));
    }

    // ==================== 夹具 ====================

    /**
     * 构造 auto 模式配置。
     *
     * @param options   扩展参数
     * @param outputDir 导出目录
     * @return 配置
     */
    private DataRestoreConfig autoConfig(Map<String, Object> options, File outputDir) {
        return DataRestoreConfig.builder()
                .format(ExportFormat.CSV)
                .outputDir(outputDir)
                .charset("UTF-8")
                .includeStructure(true)
                .options(options)
                .build();
    }

    /**
     * 在数据目录下生成加密的 {@code session.db}。
     *
     * @param dataDir 数据目录
     * @return 加密数据库文件
     * @throws Exception 生成失败
     */
    private File encryptFixture(File dataDir) throws Exception {
        File plain = WechatRestoreTestSupport.createReservedLayoutSqliteDatabase(new File(dataDir, "seed.db"));
        File encrypted = new File(dataDir, "session.db");
        WechatRestoreTestSupport.encrypt(plain, encrypted,
                SqlCipherDecryptor.parseHexKey(KEY_HEX), SqlCipherProfile.candidates().get(0));
        Files.deleteIfExists(plain.toPath());
        return encrypted;
    }

    /**
     * 构造 sqlcipher 模式配置。
     *
     * @param key         密钥
     * @param decryptDir  明文库保留目录，可为 null
     * @param outputDir   导出目录
     * @return 配置
     */
    private Map<String, Object> options(String key, File decryptDir, File outputDir) {
        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_SQLCIPHER);
        options.put(WechatDataRestore.OPTION_KEY, key);
        if (decryptDir != null) {
            options.put(WechatDataRestore.OPTION_DECRYPT_DIR, decryptDir.getAbsolutePath());
        }
        return options;
    }

    /**
     * 以公开入口执行还原。
     *
     * @param source    源文件
     * @param options   扩展参数
     * @param outputDir 导出目录
     * @return 还原结果
     * @throws Exception 执行异常
     */
    private DataRestoreResult restore(File source, Map<String, Object> options, File outputDir) throws Exception {
        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.CSV)
                .outputDir(outputDir)
                .charset("UTF-8")
                .includeStructure(true)
                .options(options)
                .build();
        return new WechatDataRestore().restore(source, config);
    }

    /**
     * 创建目录。
     *
     * @param name 目录名
     * @return 目录
     */
    private File newDir(String name) {
        File dir = tempDir.resolve(name).toFile();
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }

    /**
     * 列出目录下文件。
     *
     * @param dir 目录
     * @return 文件列表
     */
    private static List<File> listFiles(File dir) {
        File[] children = dir.listFiles();
        return children == null ? List.of() : List.of(children);
    }
}
