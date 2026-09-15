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
 * 目录源「一键还原」测试。
 *
 * <p>{@link com.chua.common.support.task.restore.AbstractDataRestore} 的 SPI 契约是
 * 「源必须是单个文件」，会以「还原源文件不存在或不是文件」直接拒绝目录；
 * 而微信的数据天然分散在 {@code db_storage} 下的二十来个库里，逐个传文件既繁琐又容易漏。
 * {@link WechatDataRestore} 因此重写了 {@code restore(File, DataRestoreConfig)} 为目录源
 * 另开一条等价入口 —— 本测试就是这条入口的回归防线。</p>
 *
 * <p>对齐的验收标准：与 {@code DataRestore.create("ibd").restore(x.ibd)} 一样，
 * 目录源也要能<b>零配置一行跑通</b>，所以下面刻意不传 {@code outputDir}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatDataRestoreDirectorySourceTest {

    /**
     * 正确的 32 字节密钥（hex）
     */
    private static final String KEY_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @TempDir
    Path tempDir;

    // ==================== 零配置一键还原 ====================

    @Test
    void directorySourceShouldRestorePlaintextDatabaseWithoutAnyConfig() throws Exception {
        File dataDir = newDir("data");
        WechatRestoreTestSupport.createSqliteDatabase(new File(dataDir, "session.db"));

        // 只给「目录 + 模式 + 密钥」，输出目录留空 —— 验证缺省值
        DataRestoreResult result = new WechatDataRestore().restore(dataDir, sqlCipherConfig(null));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount(), "MSG + Contact 两张表应各导出一个文件");

        // 输出目录缺省为 <源目录>/wechat-restore-out
        File out = new File(dataDir, "wechat-restore-out");
        assertTrue(out.isDirectory(), "缺省输出目录未创建: " + out.getAbsolutePath());
        File msgCsv = new File(out, "session__MSG.csv");
        assertTrue(msgCsv.isFile(), "缺省输出目录下缺少 MSG 导出文件");
        String csv = Files.readString(msgCsv.toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("id,talker,content,create_time"));
        assertTrue(csv.contains("你好，微信"), "CSV 正文应保留中文");
    }

    @Test
    void directorySourceShouldRestoreEncryptedDatabaseWithoutAnyConfig() throws Exception {
        File dataDir = newDir("data");
        File plain = WechatRestoreTestSupport.createReservedLayoutSqliteDatabase(new File(dataDir, "seed.db"));
        WechatRestoreTestSupport.encrypt(plain, new File(dataDir, "session.db"),
                SqlCipherDecryptor.parseHexKey(KEY_HEX), SqlCipherProfile.candidates().get(0));
        Files.deleteIfExists(plain.toPath());

        DataRestoreResult result = new WechatDataRestore().restore(dataDir, sqlCipherConfig(null));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        File out = new File(dataDir, "wechat-restore-out");
        assertTrue(new File(out, "session__MSG.csv").isFile());
        // 加密库必须真的被解开了，而不是把密文当明文导出
        String csv = Files.readString(new File(out, "session__MSG.csv").toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("hello wechat"));
    }

    @Test
    void directorySourceShouldHonourExplicitOutputDir() throws Exception {
        File dataDir = newDir("data");
        WechatRestoreTestSupport.createSqliteDatabase(new File(dataDir, "session.db"));
        File out = newDir("explicit-out");

        DataRestoreResult result = new WechatDataRestore().restore(dataDir, sqlCipherConfig(out));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertTrue(new File(out, "session__MSG.csv").isFile(), "显式 outputDir 应优先生效");
        assertFalse(new File(dataDir, "wechat-restore-out").exists(),
                "显式指定输出目录时不应再创建缺省目录");
    }

    @Test
    void repeatedRestoreShouldNotReconsumePreviousOutput() throws Exception {
        File dataDir = newDir("data");
        File plain = WechatRestoreTestSupport.createReservedLayoutSqliteDatabase(new File(dataDir, "seed.db"));
        WechatRestoreTestSupport.encrypt(plain, new File(dataDir, "session.db"),
                SqlCipherDecryptor.parseHexKey(KEY_HEX), SqlCipherProfile.candidates().get(0));
        Files.deleteIfExists(plain.toPath());

        WechatDataRestore restore = new WechatDataRestore();
        DataRestoreResult first = restore.restore(dataDir, sqlCipherConfig(null));
        DataRestoreResult second = restore.restore(dataDir, sqlCipherConfig(null));

        assertTrue(first.isSuccess(), first.getErrorMessage());
        assertTrue(second.isSuccess(), second.getErrorMessage());
        // 缺省输出目录位于数据源内部，里面还有 decrypted/session.db；
        // 若不剪枝，第二次会把上一次解出的明文库当数据源再导一遍
        assertEquals(2, first.getFileCount(), "首次还原应只导出源库的 2 张表");
        assertEquals(2, second.getFileCount(), "重复还原不应把上次的产物当数据源: "
                + second.getOutputFiles());
    }

    // ==================== 目录源不被「不是文件」拦下 ====================

    @Test
    void directorySourceShouldNotBeRejectedAsNonFile() throws Exception {
        File dataDir = newDir("data");
        Files.writeString(new File(dataDir, "note.txt").toPath(), "not a database");

        // tool 模式且未配 tool.path 必然失败，但必须是「还原失败」而不是
        // 基类那句「还原源文件不存在或不是文件」—— 后者说明目录源被拦在了入口
        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_TOOL);
        options.put(WechatDataRestore.OPTION_TOOL_PATH, "");
        DataRestoreResult result = new WechatDataRestore().restore(dataDir, config(options, null));

        assertNotNull(result);
        assertFalse(result.isSuccess());
        String message = String.valueOf(result.getErrorMessage());
        assertFalse(message.contains("不是文件"), "目录源被基类校验拦下了: " + message);
    }

    @Test
    void fileSourceShouldStillUseBaseClassValidation() {
        File missing = new File(tempDir.toFile(), "missing.db");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new WechatDataRestore().restore(missing, sqlCipherConfig(null)));

        assertTrue(error.getMessage().contains("不是文件"), "实际错误: " + error.getMessage());
    }

    @Test
    void directorySourceShouldFailWhenNoDatabaseFound() throws Exception {
        File dataDir = newDir("empty");
        Files.writeString(new File(dataDir, "note.txt").toPath(), "not a database");

        DataRestoreResult result = new WechatDataRestore().restore(dataDir, sqlCipherConfig(null));

        assertFalse(result.isSuccess());
        assertTrue(String.valueOf(result.getErrorMessage()).contains("未找到可还原的微信数据库文件"),
                "实际错误: " + result.getErrorMessage());
    }

    @Test
    void cliCollectDatabasesShouldSkipGeneratedOutputDir() throws Exception {
        File dataDir = newDir("data");
        WechatRestoreTestSupport.createSqliteDatabase(new File(dataDir, "session.db"));
        File decrypted = new File(new File(dataDir, WechatDataRestore.DIRECTORY_OUTPUT_NAME), "decrypted");
        assertTrue(decrypted.mkdirs() || decrypted.isDirectory());
        WechatRestoreTestSupport.createSqliteDatabase(new File(decrypted, "session.db"));

        List<File> found = WechatRestoreCli.collectDatabases(dataDir);

        assertEquals(1, found.size(), "产物目录里的库不应被当成数据源: " + found);
        assertEquals(dataDir.getAbsolutePath(), found.get(0).getParentFile().getAbsolutePath());
    }

    // ==================== 缺省配置补齐 ====================
    @Test
    void withDirectoryDefaultsShouldInjectDataDirAndOutputDir() {
        File dataDir = newDir("data");

        DataRestoreConfig effective = new WechatDataRestore().withDirectoryDefaults(dataDir, null);

        assertEquals(dataDir.getAbsolutePath(),
                String.valueOf(effective.getOptions().get(WechatDataRestore.OPTION_DATA_DIR)));
        assertEquals(new File(dataDir, "wechat-restore-out").getAbsolutePath(),
                effective.getOutputDir().getAbsolutePath());
        // 未指定格式时缺省 CSV，而不是把 null 传下去
        assertEquals(ExportFormat.CSV, effective.getFormat());
    }

    @Test
    void withDirectoryDefaultsShouldKeepExplicitValues() {
        File dataDir = newDir("data");
        File out = newDir("out");
        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_DATA_DIR, out.getAbsolutePath());
        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.EXCEL)
                .outputDir(out)
                .options(options)
                .build();

        DataRestoreConfig effective = new WechatDataRestore().withDirectoryDefaults(dataDir, config);

        assertEquals(out.getAbsolutePath(),
                String.valueOf(effective.getOptions().get(WechatDataRestore.OPTION_DATA_DIR)));
        assertEquals(out.getAbsolutePath(), effective.getOutputDir().getAbsolutePath());
        assertEquals(ExportFormat.EXCEL, effective.getFormat());
    }

    // ==================== 夹具 ====================

    /**
     * 构造 sqlcipher 模式配置。
     *
     * @param outputDir 输出目录，null 表示走缺省值
     * @return 配置
     */
    private DataRestoreConfig sqlCipherConfig(File outputDir) {
        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_SQLCIPHER);
        options.put(WechatDataRestore.OPTION_KEY, KEY_HEX);
        return config(options, outputDir);
    }

    /**
     * 构造配置。
     *
     * @param options   扩展参数
     * @param outputDir 输出目录，null 表示走缺省值
     * @return 配置
     */
    private DataRestoreConfig config(Map<String, Object> options, File outputDir) {
        return DataRestoreConfig.builder()
                .format(ExportFormat.CSV)
                .outputDir(outputDir)
                .charset("UTF-8")
                .includeStructure(true)
                .options(options)
                .build();
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
}
