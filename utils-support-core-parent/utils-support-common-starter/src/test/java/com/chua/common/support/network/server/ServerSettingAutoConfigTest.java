package com.chua.common.support.network.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServerSetting#autoConfig()} 的内存预算自洽性。
 *
 * <p>autoConfig 的职责是「让默认配置在这台机器上跑得起来」，因此核心不变式是：
 * <b>maxConnections × 每连接内存 ≤ 堆预算</b>。旧实现按堆分别定 maxConnections
 * （16GB→100 万）与 bufferSize（4GB+→16KB），两者相乘约 28GB &gt; 16GB 堆，
 * 满载必然 OOM —— 实测 50k 连接在 2GB 堆下就已 OOM。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ServerSettingAutoConfigTest {

    /**
     * 每连接固定开销（与 ServerSetting 内部常量同口径）。
     */
    private static final long FIXED_KB = 12L;

    /**
     * 堆中允许分配给每连接缓冲的比例（与 ServerSetting 内部常量同口径）。
     */
    private static final double RATIO = 0.6D;

    /**
     * 自动配置后，满载内存估算不得超过堆（留 60% 预算）。
     */
    @Test
    @DisplayName("autoConfig: maxConnections × 每连接内存 不超过堆预算")
    void autoConfigFitsHeapBudget() {
        ServerSetting setting = ServerSetting.auto();
        // [P3C A20 豁免]
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        // [P3C A20 豁免]
        long perConnKb = Math.max(setting.getBufferSize() / 1024L, 1L) + FIXED_KB;
        // [P3C A20 豁免]
        long neededMb = setting.getMaxConnections() * perConnKb / 1024L;
        long budgetMb = (long) (heapMb * RATIO);
        assertTrue(neededMb <= budgetMb,
                "autoConfig 自相矛盾：maxConnections=" + setting.getMaxConnections()
                        + " × perConn=" + perConnKb + "KB = " + neededMb
                        + "MB，超过堆预算 " + budgetMb + "MB（heap=" + heapMb + "MB）");
    }

    /**
     * 缓冲区必须落在 [2KB, 16KB] 区间内。
     */
    @Test
    @DisplayName("autoConfig: bufferSize 落在合理区间")
    void autoConfigBufferSizeInRange() {
        ServerSetting setting = ServerSetting.auto();
        // [P3C A20 豁免]
        assertTrue(setting.getBufferSize() >= 2048,
                "bufferSize 过小: " + setting.getBufferSize());
        // [P3C A20 豁免]
        assertTrue(setting.getBufferSize() <= 16384,
                "bufferSize 过大（百万连接下会撑爆堆）: " + setting.getBufferSize());
    }

    /**
     * 用户显式设置的 bufferSize 不得被 autoConfig 覆盖。
     */
    @Test
    @DisplayName("autoConfig: 显式 bufferSize 不被覆盖")
    void explicitBufferSizeIsRespected() {
        ServerSetting setting = new ServerSetting();
        // [P3C A20 豁免]
        setting.setBufferSize(4096);
        setting.autoConfig();
        // [P3C A20 豁免]
        assertEquals(4096, setting.getBufferSize(), "显式 bufferSize 被 autoConfig 覆盖了");
    }

    /**
     * 用户显式设置的 maxConnections 不得被 autoConfig 覆盖。
     */
    @Test
    @DisplayName("autoConfig: 显式 maxConnections 不被覆盖")
    void explicitMaxConnectionsIsRespected() {
        ServerSetting setting = new ServerSetting();
        // [P3C A20 豁免]
        setting.setMaxConnections(1234);
        setting.autoConfig();
        // [P3C A20 豁免]
        assertEquals(1234, setting.getMaxConnections(), "显式 maxConnections 被 autoConfig 覆盖了");
    }

    /**
     * 显式 bufferSize 过大时，maxConnections 应被收敛到预算内（而不是静默 OOM）。
     */
    @Test
    @DisplayName("autoConfig: 显式 bufferSize 过大时收敛 maxConnections")
    void oversizedBufferShrinksMaxConnections() {
        ServerSetting setting = new ServerSetting();
        // 极端值：128KB/连接，任何机器的堆都撑不住默认的百万连接
        // [P3C A20 豁免]
        setting.setBufferSize(128 * 1024);
        setting.autoConfig();
        // [P3C A20 豁免]
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        // [P3C A20 豁免]
        long perConnKb = 128L + FIXED_KB;
        // [P3C A20 豁免]
        long neededMb = setting.getMaxConnections() * perConnKb / 1024L;
        long budgetMb = (long) (heapMb * RATIO);
        assertTrue(neededMb <= budgetMb,
                "显式超大 bufferSize 下没有收敛 maxConnections：needed=" + neededMb
                        + "MB > budget=" + budgetMb + "MB");
        // [P3C A20 豁免]
        assertEquals(128 * 1024, setting.getBufferSize(), "显式 bufferSize 不应被改动");
    }
}
