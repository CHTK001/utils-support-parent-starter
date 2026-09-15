package com.chua.wechat.support.restore;

import org.junit.jupiter.api.Test;

import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.ExportFormat;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WechatDataRestoreTest {

    @Test
    void testConstructor() {
        WechatDataRestore restore = new WechatDataRestore();
        assertNotNull(restore);
    }

    @Test
    void testModeConstants() {
        assertEquals("auto", WechatDataRestore.MODE_AUTO);
        assertEquals("native", WechatDataRestore.MODE_NATIVE);
        assertEquals("tool", WechatDataRestore.MODE_TOOL);
    }

    @Test
    void testOptionKeys() {
        assertEquals("mode", WechatDataRestore.OPTION_MODE);
        assertEquals("runtime.dir", WechatDataRestore.OPTION_RUNTIME_DIR);
        assertEquals("key", WechatDataRestore.OPTION_KEY);
        assertEquals("key.file", WechatDataRestore.OPTION_KEY_FILE);
        assertEquals("auto.key", WechatDataRestore.OPTION_AUTO_KEY);
        assertEquals("tool.path", WechatDataRestore.OPTION_TOOL_PATH);
        assertEquals("data.dir", WechatDataRestore.OPTION_DATA_DIR);
        assertEquals("limit", WechatDataRestore.OPTION_LIMIT);
        assertEquals("whitelist", WechatDataRestore.OPTION_WHITELIST);
        assertEquals("blacklist", WechatDataRestore.OPTION_BLACKLIST);
        assertEquals("skip.groups", WechatDataRestore.OPTION_SKIP_GROUPS);
    }

    @Test
    void testDoRestoreWithToolModeMissingPath() {
        WechatDataRestore restore = new WechatDataRestore();
        File source = new File("nonexistent.db");
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "tool");
        options.put("tool.path", "");

        assertThrows(Exception.class, () -> restore.doRestore(source, createConfig(options)));
    }

    @Test
    void testDoRestoreWithNativeModeNotWindows() {
        WechatDataRestore restore = new WechatDataRestore();
        File source = new File("session.db");
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "native");
        options.put("runtime.dir", "/nonexistent");

        assertThrows(Exception.class, () -> restore.doRestore(source, createConfig(options)));
    }

    @Test
    void testDoRestoreAutoModeMissingConfig() {
        WechatDataRestore restore = new WechatDataRestore();
        File source = new File("session.db");
        Map<String, Object> options = new HashMap<>();
        // 内存明文页路径不需要任何配置，本机微信运行时它会接管 auto 降级；
        // 这里禁用它，才能验证「auto 模式下缺配置时必须抛异常」
        options.put(WechatDataRestore.OPTION_MEMORY_ENABLED, Boolean.FALSE);

        assertThrows(IllegalArgumentException.class, () -> restore.doRestore(source, createConfig(options)));
    }

    @Test
    void testDoRestoreWithInvalidRuntimeDir() {
        WechatDataRestore restore = new WechatDataRestore();
        File source = new File("session.db");
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "native");
        options.put("runtime.dir", "/nonexistent/runtime");

        assertThrows(IllegalArgumentException.class, () -> restore.doRestore(source, createConfig(options)));
    }

    @Test
    void testDoRestoreWithInvalidKey() {
        WechatDataRestore restore = new WechatDataRestore();
        File source = new File("session.db");
        Map<String, Object> options = new HashMap<>();
        options.put("mode", "native");
        options.put("runtime.dir", "/tmp");
        options.put("key", "invalid_key");

        assertThrows(Exception.class, () -> restore.doRestore(source, createConfig(options)));
    }

    private DataRestoreConfig createConfig(Map<String, Object> options) {
        return DataRestoreConfig.builder()
                .format(ExportFormat.CSV)
                .outputDir(new File(System.getProperty("java.io.tmpdir")))
                .charset("UTF-8")
                .includeStructure(false)
                .options(options)
                .build();
    }
}