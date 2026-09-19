package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI 方言登记一致性测试：每个扩展键必须能解析到方言，且方言自报协议与键一致。
 *
 * <p>键与协议分叉会导致 {@code Dialect.getExtension(protocol)} 与配置查找错位
 * （如版本化方言共享父级 .env 时未覆写 protocol），本测试在 CI 阶段暴露这类漂移。</p>
 *
 * @author CH
 */
@DisplayName("方言 SPI 登记一致性测试")
class DialectSpiConsistencyTest {

    /**
     * 测试：ExtensionKeyMatchesProtocol。
     */
    @Test
    @DisplayName("扩展键与方言协议一致")
    void extensionKeyMatchesProtocol() {
        Map<String, Dialect> all = Dialect.listAll();
        assertFalse(all.isEmpty(), "classpath 上未注册任何 Dialect 扩展");
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Dialect> entry : all.entrySet()) {
            String key = entry.getKey();
            Dialect dialect = entry.getValue();
            assertNotNull(dialect, "扩展键 " + key + " 解析为 null");
            String protocol = dialect.protocol();
            if (protocol == null || !protocol.equalsIgnoreCase(key)) {
                violations.add(key + " -> protocol=" + protocol);
            }
        }
        assertTrue(violations.isEmpty(),
                "以下方言的 protocol() 与 SPI 键不一致: " + violations);
    }

    /**
     * 测试：RequireThrowsOnUnknown。
     */
    @Test
    @DisplayName("require 对未知协议显式抛错")
    void requireThrowsOnUnknown() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> Dialect.require("__no_such_dialect__"));
    }
}
