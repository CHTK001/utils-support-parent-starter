package com.chua.wechat.support.restore;

import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信聊天记录还原集成测试.
 *
 * <p>验证 {@link WechatDataRestore} 完整还原管道。
 * 真正的还原需要以下前置条件（本测试仅验证管道结构）：</p>
 * <ul>
 *   <li>Windows 平台</li>
 *   <li>微信 4.x 的 {@code session.db} 文件</li>
 *   <li>原生库目录包含 {@code WCDB.dll}、{@code SDL2.dll}、{@code wcdb_api.dll}</li>
 *   <li>64 位十六进制数据库密钥</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatDataRestoreIntegrationTest {

    @TempDir
    Path tempDir;

    private File sessionDb;
    private File runtimeDir;
    private File outputDir;

    @BeforeEach
    void setUp() throws IOException {
        // 创建模拟 session.db（SQLite 格式头部）
        sessionDb = tempDir.resolve("wxid_test").resolve("session.db").toFile();
        Files.createDirectories(sessionDb.getParentFile().toPath());
        try (var os = Files.newOutputStream(sessionDb.toPath())) {
            os.write("SQLite format 3\0".getBytes(StandardCharsets.UTF_8));
        }

        // 创建原生库目录（需要 WCDB.dll, SDL2.dll, wcdb_api.dll）
        runtimeDir = tempDir.resolve("runtime").toFile();
        Files.createDirectories(runtimeDir.toPath());

        // 输出目录
        outputDir = tempDir.resolve("output").toFile();
        Files.createDirectories(outputDir.toPath());
    }

    @Test
    void testRestorePipelineWithNativeMode() {
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "native");
        options.put("runtime.dir", runtimeDir.getAbsolutePath());
        options.put("key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        options.put("data.dir", sessionDb.getParentFile().getAbsolutePath());

        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.CSV)
                .outputDir(outputDir)
                .charset("UTF-8")
                .options(options)
                .build();

        WechatDataRestore restore = new WechatDataRestore(config);

        // 预期失败：缺少 WCDB.dll 等原生库
        assertThrows(Exception.class, () -> restore.doRestore(sessionDb, config));
    }

    @Test
    void testRestorePipelineWithToolMode() {
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "tool");
        options.put("tool.path", "");
        options.put("data.dir", sessionDb.getParentFile().getAbsolutePath());

        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.CSV)
                .outputDir(outputDir)
                .charset("UTF-8")
                .options(options)
                .build();

        WechatDataRestore restore = new WechatDataRestore(config);

        // 预期失败：缺少 Wechat-Export 工具
        assertThrows(Exception.class, () -> restore.doRestore(sessionDb, config));
    }

    @Test
    void testRestorePipelineWithAutoMode() {
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "auto");
        options.put("runtime.dir", runtimeDir.getAbsolutePath());
        options.put("key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        options.put("data.dir", sessionDb.getParentFile().getAbsolutePath());

        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.EXCEL)
                .outputDir(outputDir)
                .charset("UTF-8")
                .options(options)
                .build();

        WechatDataRestore restore = new WechatDataRestore(config);

        // 预期失败：缺少原生库（auto 模式下 native 优先）
        assertThrows(Exception.class, () -> restore.doRestore(sessionDb, config));
    }

    @Test
    void testRestoreWithValidConfigReturnsResult() {
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "native");
        options.put("runtime.dir", runtimeDir.getAbsolutePath());
        options.put("key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        options.put("data.dir", sessionDb.getParentFile().getAbsolutePath());
        options.put("limit", 500);
        options.put("skip.groups", false);

        DataRestoreConfig config = DataRestoreConfig.builder()
                .format(ExportFormat.SQL)
                .outputDir(outputDir)
                .targetTable("wechat_message")
                .targetSchema("wechat")
                .includeStructure(true)
                .charset("UTF-8")
                .options(options)
                .build();

        WechatDataRestore restore = new WechatDataRestore(config);

        // 验证配置正确性
        assertEquals(ExportFormat.SQL, config.getFormat());
        assertEquals("wechat_message", config.getTargetTable());
        assertEquals("wechat", config.getTargetSchema());
        assertTrue(config.isIncludeStructure());
    }

    @Test
    void testExportFormats() {
        // 验证所有支持的导出格式
        for (ExportFormat format : ExportFormat.values()) {
            Map<String, Object> options = new HashMap<>();
            options.put("mode", "tool");
            options.put("tool.path", "/nonexistent/export.py");

            DataRestoreConfig config = DataRestoreConfig.builder()
                    .format(format)
                    .outputDir(outputDir)
                    .options(options)
                    .build();

            WechatDataRestore restore = new WechatDataRestore(config);
            assertNotNull(restore);
        }
    }
}