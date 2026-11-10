
// [P3C A20 豁免]
// [P3C E1 豁免]

// [P3C E1 豁免]
package com.chua.common.support.host;

import com.chua.common.support.utils.SystemUtils;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主机身份 ID 生成与校验：生成、解析、匹配、换硬件降温、占位值剔除。
 *
 * @author CH
 * @since 4.0.0.50
 */
class SystemUtilsUnitTest {

    @Test
    void testGenerateAndParse() {
        String id = SystemUtils.uniqueId();
        assertNotNull(id);
        System.out.println("generated id = " + id);
        assertTrue(id.startsWith("CH1-"));
        // 去掉 "CH1-" 前缀后是 8 组 x 4 字符 = 32 个 Base32 字符 + 7 个组间连字符 = 39
        assertEquals(39, id.length() - 4);
        assertEquals(9, id.split("-").length);

        HostIdentity.Parsed parsed = SystemUtils.parseId(id);
        assertTrue(parsed.valid());
        System.out.println("parsed anchors = " + parsed.anchors());
        assertEquals(1, parsed.version());
        assertEquals(parsed.anchors().size(), parsed.anchors().size());
    }

    @Test
    void testVerifySameId() {
        String id = SystemUtils.uniqueId();
        System.out.println("verifySameId: id = " + id);
        HostIdentity.Match match = SystemUtils.verifyId(id);
        assertTrue(match.same(), match.reason());
        // 同机自校验：本机采集到的全部一级锚点都应匹配（本机有 4 项，非 1 项）
        assertTrue(match.matched() >= 1, "应至少匹配 1 项");
        assertEquals(match.matched(), match.comparable(), "可比项应全部匹配");
        assertEquals(1.0, match.ratio());
        assertTrue(match.matchedAnchors().contains(HostAnchor.MACHINE_ID));
    }

    @Test
    void testVerifyChangedMacs() {
        String id = SystemUtils.uniqueId();
        // 换网卡只改易变锚点 primaryMac，不改任何一级锚点，不应影响同一台机器判定。
        // 必须基于真实快照覆盖 MAC，而不是用仅含假 machineId 的 fakeFacts。
        Map<HostAnchor, String> values = new EnumMap<>(HostAnchor.class);
        values.putAll(SystemUtils.hostFacts().values());
        values.put(HostAnchor.PRIMARY_MAC, "AA:BB:CC:DD:EE:FF");
        HostFacts facts = new HostFacts(values, SystemUtils.hostFacts().channel());
        System.out.println("verifyChangedMac: id = " + id + " facts = " + facts);
        HostIdentity.Match match = SystemUtils.verifyId(id, facts);
        assertTrue(match.same(), match.reason());
    }

    @Test
    void testVerifyChangedSystemUuid() {
        String id = SystemUtils.uniqueId();
        HostFacts facts = fakeFacts(HostAnchor.SYSTEM_UUID, "00000000-0000-0000-0000-000000000000");
        System.out.println("verifyChangedUuid: id = " + id + " facts = " + facts);
        HostIdentity.Match match = SystemUtils.verifyId(id, facts);
        assertFalse(match.same(), match.reason());
    }

    @Test
    void testVerifyInvalidId() {
        HostIdentity.Parsed parsed = SystemUtils.parseId("CH1-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertFalse(parsed.valid());
        System.out.println("invalidId reason = " + parsed.reason());

        HostIdentity.Parsed p2 = SystemUtils.parseId(null);
        assertFalse(p2.valid());

        HostIdentity.Parsed p3 = SystemUtils.parseId("X-1234-5678");
        assertFalse(p3.valid());
    }

    @Test
    void testWhiteAndPlaceholders() {
        // 占位串不算有效值
        assertEquals("", HostIdentity.normalize("  TO BE FILLED BY O.E.M.  "));
        assertEquals("", HostIdentity.normalize("To be filled by OEM"));
        assertEquals("", HostIdentity.normalize("NA"));
        assertEquals("", HostIdentity.normalize("N/A"));
        assertEquals("", HostIdentity.normalize("UNKNOWN"));
        assertEquals("", HostIdentity.normalize("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"));
        assertEquals("", HostIdentity.normalize("00000000-0000-0000-0000-000000000000"));
        // 去空白处理
        assertEquals("ABCD-EFGH", HostIdentity.normalize("   ABCD-EFGH\n"));
        assertEquals("ABCD-EFGH", HostIdentity.normalize("A B C D-EFGH"));
    }

    @Test
    void testImmutableInfoAndMutableInfo() {
        Map<String, String> immutable = SystemUtils.immutableInfo();
        Map<String, String> mutable = SystemUtils.mutableInfo();
        System.out.println("immutable    = " + immutable);
        System.out.println("mutable      = " + mutable);
        assertTrue(SystemUtils.hasImmutableInfo());
        System.out.println("channel      = " + SystemUtils.hostInfoChannel());

        for (String mac : immutable.values()) {
            assertFalse(mac.contains("AA:BB:CC:DD:EE:FF"));
        }
        assertTrue(mutable.containsKey("primaryMac") || mutable.containsKey("hostname"));
    }

    private static HostFacts fakeFacts(HostAnchor state, String value) {
        Map<HostAnchor, String> values = new EnumMap<>(HostAnchor.class);
        values.put(HostAnchor.MACHINE_ID, "abcd-1234");
        values.put(state, value);
        return new HostFacts(values, "test-channel");
    }
}
