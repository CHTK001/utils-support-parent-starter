package com.chua.wechat.support.restore.jdbc;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.WechatDataRestore;
import com.chua.wechat.support.restore.WechatRestoreTestSupport;
import org.junit.jupiter.api.Assumptions;
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
 * {@link WechatJdbcExporter} 单元测试。
 *
 * <p>夹具为明文 SQLite（含 {@code MSG} 三行、{@code Contact} 一行），
 * 覆盖 CSV / SQL / EXCEL 三种导出格式，以及白名单、黑名单、条数上限与非法输入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatJdbcExporterTest {

    /**
     * 夹具库名（不含扩展名）
     */
    private static final String DB_BASE = "session";

    @TempDir
    Path tempDir;

    // ==================== CSV ====================

    /**
     * csv应当Export每个表转为Own文件。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void csvShouldExportEachTableToOwnFile() throws Exception {
        File db = newDb();
        File out = newDir("csv");

        DataRestoreResult result = WechatJdbcExporter.export(db, config(ExportFormat.CSV), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount());
        assertTrue(result.getTotalSize() > 0);

        File contact = new File(out, DB_BASE + "__Contact.csv");
        File msg = new File(out, DB_BASE + "__MSG.csv");
        assertTrue(contact.isFile(), "缺少 Contact 表导出文件");
        assertTrue(msg.isFile(), "缺少 MSG 表导出文件");

        List<String> msgLines = Files.readAllLines(msg.toPath(), StandardCharsets.UTF_8);
        assertEquals("id,talker,content,create_time", msgLines.getFirst());
        assertEquals(4, msgLines.size(), "表头 + 3 行数据");
        assertTrue(msgLines.get(1).contains("hello wechat"));
        assertTrue(msgLines.get(2).contains("你好，微信"));
        assertTrue(msgLines.get(3).contains("\"line1,with comma\""), "含逗号的字段应被双引号包裹");

        List<String> contactLines = Files.readAllLines(contact.toPath(), StandardCharsets.UTF_8);
        assertEquals("id,name", contactLines.getFirst());
        assertEquals("1,Alice", contactLines.get(1));
    }

    /**
     * csv应当Honour上限Option。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void csvShouldHonourLimitOption() throws Exception {
        File db = newDb();
        File out = newDir("csv-limit");

        Map<String, Object> options = new HashMap<>();
        options.put(WechatDataRestore.OPTION_LIMIT, 1);
        DataRestoreResult result = WechatJdbcExporter.export(db, config(ExportFormat.CSV, options), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        List<String> msgLines = Files.readAllLines(
                new File(out, DB_BASE + "__MSG.csv").toPath(), StandardCharsets.UTF_8);
        assertEquals(2, msgLines.size(), "limit=1 时只应导出 1 行数据");
    }

    /**
     * csv应当HonourWhitelistOption。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void csvShouldHonourWhitelistOption() throws Exception {
        File db = newDb();
        File out = newDir("csv-whitelist");

        Map<String, Object> options = new HashMap<>();
        options.put(WechatJdbcExporter.OPTION_TABLE_WHITELIST, "MSG");
        DataRestoreResult result = WechatJdbcExporter.export(db, config(ExportFormat.CSV, options), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(1, result.getFileCount());
        assertTrue(new File(out, DB_BASE + "__MSG.csv").isFile());
        assertFalse(new File(out, DB_BASE + "__Contact.csv").exists());
    }

    /**
     * csv应当HonourBlacklistOption。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void csvShouldHonourBlacklistOption() throws Exception {
        File db = newDb();
        File out = newDir("csv-blacklist");

        Map<String, Object> options = new HashMap<>();
        options.put(WechatJdbcExporter.OPTION_TABLE_BLACKLIST, "MSG");
        DataRestoreResult result = WechatJdbcExporter.export(db, config(ExportFormat.CSV, options), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(1, result.getFileCount());
        assertTrue(new File(out, DB_BASE + "__Contact.csv").isFile());
        assertFalse(new File(out, DB_BASE + "__MSG.csv").exists());
    }

    // ==================== SQL ====================

    /**
     * SQL应当发出创建And插入Statements。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void sqlShouldEmitCreateAndInsertStatements() throws Exception {
        File db = newDb();
        File out = newDir("sql");

        DataRestoreResult result = WechatJdbcExporter.export(db, config(ExportFormat.SQL), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount());

        String script = Files.readString(new File(out, DB_BASE + "__MSG.sql").toPath(), StandardCharsets.UTF_8);
        assertTrue(script.contains("CREATE TABLE IF NOT EXISTS `MSG` ("), "缺少建表语句");
        assertTrue(script.contains("INSERT INTO `MSG` ("), "缺少插入语句");
        assertTrue(script.contains("hello wechat"));
        assertTrue(script.contains("你好，微信"));
        assertEquals(3, script.split("INSERT INTO `MSG` \\(", -1).length - 1, "应有 3 条 INSERT");
    }

    /**
     * SQL应当SkipStructureWhenDisabled。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void sqlShouldSkipStructureWhenDisabled() throws Exception {
        File db = newDb();
        File out = newDir("sql-nostruct");

        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.SQL)
                .outputDir(out)
                .charset("UTF-8")
                .includeStructure(false)
                .options(new HashMap<>())
                .build();
        DataRestoreResult result = WechatJdbcExporter.export(db, config, out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        String script = Files.readString(new File(out, DB_BASE + "__MSG.sql").toPath(), StandardCharsets.UTF_8);
        assertFalse(script.contains("CREATE TABLE"), "关闭表结构后不应输出 DDL");
        assertTrue(script.contains("INSERT INTO `MSG`"));
    }

    // ==================== EXCEL ====================

    /**
     * excel应当ExportWorkbookWhen文件SystemAvailable。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void excelShouldExportWorkbookWhenFileSystemAvailable() throws Exception {
        Assumptions.assumeTrue(excelFileSystemAvailable(),
                "excel FileSystem 不可用（需要 utils-support-excel-starter + POI + Guava），跳过 EXCEL 导出用例");

        File db = newDb();
        File out = newDir("excel");

        DataRestoreResult result = WechatJdbcExporter.export(db, config(ExportFormat.EXCEL), out);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getFileCount());
        File msg = new File(out, DB_BASE + "__MSG.xlsx");
        assertTrue(msg.isFile());
        byte[] head = Files.readAllBytes(msg.toPath());
        assertTrue(head.length > 2 && head[0] == 'P' && head[1] == 'K', "xlsx 应为 ZIP 容器");
    }

    // ==================== 异常与边界 ====================

    /**
     * json格式化应当BeRejected。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void jsonFormatShouldBeRejected() throws Exception {
        File db = newDb();
        File out = newDir("json");

        assertThrows(UnsupportedOperationException.class,
                () -> WechatJdbcExporter.export(db, config(ExportFormat.JSON), out));
    }

    /**
     * missingDatabase应当BeRejected。
     */
    @Test
    void missingDatabaseShouldBeRejected() {
        File out = tempDir.resolve("missing-out").toFile();
        assertThrows(IllegalArgumentException.class,
                () -> WechatJdbcExporter.export(new File(tempDir.toFile(), "missing.db"),
                        config(ExportFormat.CSV), out));
    }

    /**
     * emptyDatabase应当Fail。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void emptyDatabaseShouldFail() throws Exception {
        File empty = new File(tempDir.toFile(), "empty.db");
        try (java.sql.Connection connection =
                     java.sql.DriverManager.getConnection("jdbc:sqlite:" + empty.getAbsolutePath());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE OnlyStructure (id INTEGER)");
        }
        File out = newDir("empty");

        DataRestoreResult result = WechatJdbcExporter.export(empty, config(ExportFormat.CSV), out);

        assertFalse(result.isSuccess(), "无数据表应返回失败");
        assertNotNull(result.getErrorMessage());
    }

    // ==================== 夹具 ====================

    /**
     * 构造明文测试库。
     *
     * @return 明文数据库文件
     * @throws Exception 建库失败
     */
    private File newDb() throws Exception {
        return WechatRestoreTestSupport.createSqliteDatabase(new File(tempDir.toFile(), DB_BASE + ".db"));
    }

    /**
     * 创建输出目录。
     *
     * @param name 目录名
     * @return 输出目录
     */
    private File newDir(String name) {
        File dir = tempDir.resolve(name).toFile();
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }

    /**
     * 构造默认配置。
     *
     * @param format 输出格式
     * @return 还原配置
     */
    private DataRestoreConfig config(ExportFormat format) {
        return config(format, new HashMap<>());
    }

    /**
     * 构造带扩展参数的配置。
     *
     * @param format  输出格式
     * @param options 扩展参数
     * @return 还原配置
     */
    private DataRestoreConfig config(ExportFormat format, Map<String, Object> options) {
        return DataRestoreConfig.builder()
                .format(format)
                .outputDir(tempDir.toFile())
                .charset("UTF-8")
                .includeStructure(true)
                .options(options)
                .build();
    }

    /**
     * 探测 excel FileSystem 是否可用。
     *
     * <p>读取 SPI 注册文件判断是否存在 {@code excel=} 实现，而不是调用
     * {@link FileSystem#create(String)}：后者对未知类型并不抛异常，无法作为可用性判据。
     * 缺失实现时（未引入 utils-support-excel-starter）跳过 EXCEL 用例。</p>
     *
     * <p>必须用 {@code getResources} 遍历<b>全部</b>注册文件：{@code ServiceProvider} 会把类路径上
     * 每个 jar 的同名文件合并，而 {@code getResourceAsStream} 只返回第一个命中——第一个通常是
     * {@code utils-support-common-starter} 那份（只注册 csv / json / xml / txt / zip / archive / tar），
     * 只看它就会在 excel 实际可用时误判为不可用，把本用例永久跳过。</p>
     *
     * @return 可用返回 true
     */
    private static boolean excelFileSystemAvailable() {
        String resource = "META-INF/extensions/" + FileSystem.class.getName();
        try {
            java.util.Enumeration<java.net.URL> urls =
                    WechatJdbcExporterTest.class.getClassLoader().getResources(resource);
            while (urls.hasMoreElements()) {
                try (java.io.InputStream in = urls.nextElement().openStream()) {
                    String registrations = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (registrations.lines().anyMatch(line -> line.trim().startsWith("excel="))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
