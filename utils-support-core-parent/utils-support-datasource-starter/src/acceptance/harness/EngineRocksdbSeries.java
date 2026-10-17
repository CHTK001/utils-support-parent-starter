import com.chua.rocksdb.support.engine.RocksDbSeriesEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 引擎验收运行器 —— 阶段十一：RocksDbSeriesEngine（嵌入式 RocksDB，真实读写）。
 *
 * <p>{@code rocksdb} 是 {@code META-INF/extensions/…SeriesEngine} 里登记的一个 SPI 实现，
 * 但在本轮之前**从未被验收覆盖**（交付报告误记为「SeriesEngine 4 个全覆盖」）。
 * 它是本机可完整验证的：RocksDB 是嵌入式库，数据落在本地目录，无需任何外部服务。</p>
 *
 * <p>本阶段不使用任何桩：真实打开 RocksDB、真实写点、真实前缀扫描、真实删除，
 * 因此覆盖的是键布局、时间戳编码、索引维护、降采样与清理语义的端到端行为。</p>
 */
public final class EngineRocksdbSeries {

    /** 本轮唯一数据目录（每次运行新目录，避免上轮 LOCK/残留干扰）。 */
    private static Path root;

    private EngineRocksdbSeries() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十一：RocksDbSeriesEngine（真实嵌入式 RocksDB）");
        EngineFunctional.emit("=".repeat(78));

        try {
            root = Files.createTempDirectory("acc-rocksdb-series-");
            EngineFunctional.emit("  [data] " + root.toAbsolutePath());
        } catch (Throwable t) {
            EngineFunctional.recordFail("无法创建数据目录: " + EngineFunctional.chain(t));
            EngineFunctional.finishAndExit("EngineRocksdbSeries-report.txt");
            return;
        }

        runQuietly("config", EngineRocksdbSeries::configSuite);
        runQuietly("guard", EngineRocksdbSeries::guardSuite);
        runQuietly("write-read", EngineRocksdbSeries::writeReadSuite);
        runQuietly("metrics", EngineRocksdbSeries::metricsSuite);
        runQuietly("purge", EngineRocksdbSeries::purgeSuite);
        runQuietly("persistence", EngineRocksdbSeries::persistenceSuite);
        runQuietly("lifecycle", EngineRocksdbSeries::lifecycleSuite);

        EngineFunctional.finishAndExit("EngineRocksdbSeries-report.txt");
    }

    // ==================== 装配 ====================

    static void configSuite() {
        EngineFunctional.emit("--- rocksdb-series:config (装配判定) ---");
        String p = "config.";

        EngineFunctional.check(p + "engine() 标识与 path 必填", () -> {
            RocksDbSeriesEngine ok = new RocksDbSeriesEngine(sub("cfg-ok"), "default");
            if (!"rocksdb".equals(ok.engine())) {
                throw new EngineFunctional.HarnessFailure("engine(): " + ok.engine());
            }
            if (!ok.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("有效 path 应可用");
            }
            ok.close();

            if (new RocksDbSeriesEngine((String) null, "default").isAvailable()
                    || new RocksDbSeriesEngine("   ", "default").isAvailable()
                    || new RocksDbSeriesEngine(new Properties()).isAvailable()) {
                throw new EngineFunctional.HarnessFailure("path 缺失时不应可用");
            }
            return "rocksdb / 可用性判定正确";
        });

        EngineFunctional.check(p + "不可用时写抛 ISE、读空、清理 0", () -> {
            RocksDbSeriesEngine e = new RocksDbSeriesEngine((String) null, "default");
            expectType(IllegalStateException.class, () -> e.writePoint("cpu", 1L, "cpu_usage", 1.0, 1L));
            if (!e.metrics("cpu", 1L).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("metrics 应为空");
            }
            if (!e.series("cpu", 1L, "cpu_usage", 1, "5m").isEmpty()) {
                throw new EngineFunctional.HarnessFailure("series 应为空");
            }
            if (e.purge("cpu", 1L, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("purge 应为 0");
            }
            return "写 ISE / 读空 / 清理 0";
        });
    }

    // ==================== 守卫 ====================

    static void guardSuite() {
        EngineFunctional.emit("--- rocksdb-series:guard (参数与白名单) ---");
        String p = "guard.";

        EngineFunctional.check(p + "必填参数缺失被拒", () -> {
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("guard-req"), "default")) {
                expectType(IllegalArgumentException.class, () -> e.writePoint(null, 1L, "cpu", 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", null, "cpu", 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", 1L, null, 1.0, 1L));
                expectType(IllegalArgumentException.class, () -> e.series(null, 1L, "cpu", 1, "5m"));
                expectType(IllegalArgumentException.class, () -> e.series("cpu", null, "cpu", 1, "5m"));
                expectType(IllegalArgumentException.class, () -> e.metrics(null, 1L));
                expectType(IllegalArgumentException.class, () -> e.purge(null, 1L, 1L));
                expectType(IllegalArgumentException.class, () -> e.series("cpu", 1L, "cpu", 0, "5m"));
                expectType(IllegalArgumentException.class, () -> e.series("cpu", 1L, "cpu", -5, "5m"));
                return "全部拒绝";
            }
        });

        EngineFunctional.check(p + "非法标识符被白名单拒绝且不落盘", () -> {
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("guard-id"), "default")) {
                for (String bad : new String[]{"a-b", "a:b", "a;drop", "a b", "", "a.b"}) {
                    expectType(IllegalArgumentException.class, () -> e.writePoint(bad, 1L, "cpu", 1.0, 1L));
                    expectType(IllegalArgumentException.class, () -> e.writePoint("cpu", 1L, bad, 1.0, 1L));
                }
                expectType(IllegalArgumentException.class, () -> e.metrics("a-b", 1L));
                expectType(IllegalArgumentException.class, () -> e.series("a-b", 1L, "cpu", 1, "5m"));
                expectType(IllegalArgumentException.class, () -> e.purge("a-b", 1L, 1L));
                if (!e.metrics("cpu", 1L).isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("守卫失败后不应留下索引");
                }
                return "拒绝且零写入";
            }
        });
    }

    // ==================== 真实写入 / 读取 ====================

    static void writeReadSuite() {
        EngineFunctional.emit("--- rocksdb-series:write-read (真实写点与曲线) ---");
        String p = "wr.";

        EngineFunctional.check(p + "写点后按窗口降采样，时间升序、桶起点对齐", () -> {
            long base = minuteStart(System.currentTimeMillis()) - 10 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("wr-series"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 10.0, base);
                e.writePoint("cpu", 7L, "cpu_usage", 20.0, base + 1_000L);
                e.writePoint("cpu", 7L, "cpu_usage", 30.0, base + 60_000L);

                List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (curve.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("桶数错误: " + curve);
                }
                if (!eqNum(curve.get(0).get(0), base) || !eqNum(curve.get(0).get(1), 15.0)) {
                    throw new EngineFunctional.HarnessFailure("首桶错误: " + curve.get(0));
                }
                if (!eqNum(curve.get(1).get(0), base + 60_000L) || !eqNum(curve.get(1).get(1), 30.0)) {
                    throw new EngineFunctional.HarnessFailure("次桶错误: " + curve.get(1));
                }
                return curve.toString();
            }
        });

        EngineFunctional.check(p + "同一时间戳重复写入为覆盖（键唯一）", () -> {
            long base = minuteStart(System.currentTimeMillis()) - 5 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("wr-overwrite"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 10.0, base);
                e.writePoint("cpu", 7L, "cpu_usage", 99.5, base);
                List<List<Object>> curve = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (curve.size() != 1 || !eqNum(curve.get(0).get(1), 99.5)) {
                    throw new EngineFunctional.HarnessFailure("应为覆盖后的单点: " + curve);
                }
                return "99.5";
            }
        });

        EngineFunctional.check(p + "超出回溯范围的点被裁掉，范围内保留", () -> {
            long now = System.currentTimeMillis();
            long recent = minuteStart(now) - 2 * 60_000L;
            long old = minuteStart(now) - 3 * 3600_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("wr-range"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, old);
                e.writePoint("cpu", 7L, "cpu_usage", 2.0, recent);
                List<List<Object>> oneHour = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (oneHour.size() != 1 || !eqNum(oneHour.get(0).get(1), 2.0)) {
                    throw new EngineFunctional.HarnessFailure("1 小时窗口: " + oneHour);
                }
                List<List<Object>> fourHours = e.series("cpu", 7L, "cpu_usage", 4, "1m");
                if (fourHours.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("4 小时窗口应含 2 点: " + fourHours);
                }
                return "1h=1点, 4h=2点";
            }
        });

        EngineFunctional.check(p + "不同 monitor 的点互不串曲线", () -> {
            long base = minuteStart(System.currentTimeMillis()) - 3 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("wr-monitor"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 11.0, base);
                e.writePoint("cpu", 8L, "cpu_usage", 88.0, base);
                List<List<Object>> m7 = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                List<List<Object>> m8 = e.series("cpu", 8L, "cpu_usage", 1, "1m");
                if (m7.size() != 1 || !eqNum(m7.get(0).get(1), 11.0)) {
                    throw new EngineFunctional.HarnessFailure("monitor=7: " + m7);
                }
                if (m8.size() != 1 || !eqNum(m8.get(0).get(1), 88.0)) {
                    throw new EngineFunctional.HarnessFailure("monitor=8: " + m8);
                }
                return "7->11.0, 8->88.0";
            }
        });

        EngineFunctional.check(p + "无数据返回空曲线（不是异常）", () -> {
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("wr-empty"), "default")) {
                if (!e.series("cpu", 7L, "cpu_usage", 1, "1m").isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("应为空曲线");
                }
                if (!e.metrics("cpu", 7L).isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("metrics 应为空");
                }
                return "空";
            }
        });
    }

    // ==================== 指标发现 ====================

    static void metricsSuite() {
        EngineFunctional.emit("--- rocksdb-series:metrics (索引维护) ---");
        String p = "metrics.";

        EngineFunctional.check(p + "按 monitor 收窄并字典序升序", () -> {
            long base = minuteStart(System.currentTimeMillis()) - 4 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("mt-list"), "default")) {
                e.writePoint("cpu", 7L, "mem_usage", 1.0, base);
                e.writePoint("cpu", 7L, "cpu_usage", 2.0, base);
                e.writePoint("cpu", 8L, "net_usage", 3.0, base);
                List<String> m7 = e.metrics("cpu", 7L);
                if (!List.of("cpu_usage", "mem_usage").equals(m7)) {
                    throw new EngineFunctional.HarnessFailure("monitor=7: " + m7);
                }
                List<String> all = e.metrics("cpu", null);
                if (!List.of("cpu_usage", "mem_usage", "net_usage").equals(all)) {
                    throw new EngineFunctional.HarnessFailure("全部 monitor: " + all);
                }
                return "7 -> " + m7 + " ; all -> " + all;
            }
        });

        EngineFunctional.check(p + "metricName 为空且唯一时自动推断", () -> {
            long base = minuteStart(System.currentTimeMillis()) - 4 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("mt-single"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 42.0, base);
                List<List<Object>> curve = e.series("cpu", 7L, 1, "1m");
                if (curve.size() != 1 || !eqNum(curve.get(0).get(1), 42.0)) {
                    throw new EngineFunctional.HarnessFailure("自动推断失败: " + curve);
                }
                return "自动推断 cpu_usage";
            }
        });

        EngineFunctional.check(p + "metricName 为空且多指标时拒绝合并", () -> {
            long base = minuteStart(System.currentTimeMillis()) - 4 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("mt-multi"), "default")) {
                e.writePoint("cpu", 7L, "mem_usage", 1.0, base);
                e.writePoint("cpu", 7L, "cpu_usage", 2.0, base);
                try {
                    e.series("cpu", 7L, 1, "1m");
                    throw new EngineFunctional.HarnessFailure("应抛 IllegalArgumentException");
                } catch (IllegalArgumentException ex) {
                    String msg = String.valueOf(ex.getMessage());
                    if (!msg.contains("cpu_usage") || !msg.contains("mem_usage")) {
                        throw new EngineFunctional.HarnessFailure("消息未列出候选: " + msg);
                    }
                    return msg;
                }
            }
        });
    }

    // ==================== 保留期清理 ====================

    static void purgeSuite() {
        EngineFunctional.emit("--- rocksdb-series:purge (真实删除与索引回收) ---");
        String p = "purge.";

        EngineFunctional.check(p + "按时间删除并返回真实删除条数", () -> {
            long now = System.currentTimeMillis();
            long old = minuteStart(now) - 30 * 60_000L;
            long recent = minuteStart(now) - 1 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("pg-count"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, old);
                e.writePoint("cpu", 7L, "cpu_usage", 2.0, old + 1_000L);
                e.writePoint("cpu", 7L, "cpu_usage", 3.0, recent);

                int deleted = e.purge("cpu", 7L, "cpu_usage", now - 10 * 60_000L);
                if (deleted != 2) {
                    throw new EngineFunctional.HarnessFailure("删除条数: " + deleted);
                }
                List<List<Object>> left = e.series("cpu", 7L, "cpu_usage", 1, "1m");
                if (left.size() != 1 || !eqNum(left.get(0).get(1), 3.0)) {
                    throw new EngineFunctional.HarnessFailure("剩余曲线: " + left);
                }
                if (e.purge("cpu", 7L, "cpu_usage", now - 10 * 60_000L) != 0) {
                    throw new EngineFunctional.HarnessFailure("再清理应删 0 条");
                }
                return "deleted=2, 剩 1 点";
            }
        });

        EngineFunctional.check(p + "清空某指标后索引被回收（metrics 不再报告）", () -> {
            long now = System.currentTimeMillis();
            long ts = minuteStart(now) - 20 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("pg-index"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, ts);
                e.writePoint("cpu", 7L, "mem_usage", 2.0, ts + 60_000L);
                if (!e.metrics("cpu", 7L).contains("cpu_usage")) {
                    throw new EngineFunctional.HarnessFailure("清理前应包含 cpu_usage");
                }
                e.purge("cpu", 7L, "cpu_usage", now + 60_000L);
                List<String> after = e.metrics("cpu", 7L);
                if (after.contains("cpu_usage")) {
                    throw new EngineFunctional.HarnessFailure("清空后索引未回收: " + after);
                }
                if (!after.contains("mem_usage")) {
                    throw new EngineFunctional.HarnessFailure("其它指标不应被误删: " + after);
                }
                return "cpu_usage 已回收, mem_usage 保留";
            }
        });

        EngineFunctional.check(p + "monitorId 为空时跨 monitor 清理", () -> {
            long now = System.currentTimeMillis();
            long ts = minuteStart(now) - 20 * 60_000L;
            try (RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("pg-all"), "default")) {
                e.writePoint("cpu", 7L, "cpu_usage", 1.0, ts);
                e.writePoint("cpu", 8L, "cpu_usage", 2.0, ts);
                int deleted = e.purge("cpu", null, now + 60_000L);
                if (deleted != 2) {
                    throw new EngineFunctional.HarnessFailure("删除条数: " + deleted);
                }
                if (!e.metrics("cpu", null).isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("应全部清空");
                }
                return "deleted=2";
            }
        });
    }

    // ==================== 持久化 ====================

    static void persistenceSuite() {
        EngineFunctional.emit("--- rocksdb-series:persistence (跨实例持久化) ---");
        String p = "persist.";

        EngineFunctional.check(p + "关闭后重开同一目录，数据仍在", () -> {
            String dir = sub("persist");
            long base = minuteStart(System.currentTimeMillis()) - 7 * 60_000L;
            try (RocksDbSeriesEngine first = new RocksDbSeriesEngine(dir, "default")) {
                first.writePoint("disk", 1L, "cpu_usage", 12.5, base);
            }
            try (RocksDbSeriesEngine second = new RocksDbSeriesEngine(dir, "default")) {
                List<List<Object>> curve = second.series("disk", 1L, "cpu_usage", 1, "1m");
                if (curve.size() != 1 || !eqNum(curve.get(0).get(1), 12.5)) {
                    throw new EngineFunctional.HarnessFailure("重开后曲线: " + curve);
                }
                if (!second.metrics("disk", 1L).contains("cpu_usage")) {
                    throw new EngineFunctional.HarnessFailure("重开后索引丢失");
                }
                return "12.5 持久化成功";
            }
        });
    }

    // ==================== 生命周期 ====================

    static void lifecycleSuite() {
        EngineFunctional.emit("--- rocksdb-series:lifecycle (关闭语义) ---");
        String p = "lifecycle.";

        EngineFunctional.check(p + "close 幂等，关闭后写拒绝、读空、清理 0", () -> {
            RocksDbSeriesEngine e = new RocksDbSeriesEngine(sub("lc"), "default");
            if (!e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("初始应可用");
            }
            e.close();
            if (e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("关闭后应不可用");
            }
            e.close();
            expectType(IllegalStateException.class, () -> e.writePoint("cpu", 1L, "cpu_usage", 1.0, 1L));
            if (!e.metrics("cpu", 1L).isEmpty()) {
                throw new EngineFunctional.HarnessFailure("关闭后 metrics 应为空");
            }
            if (!e.series("cpu", 1L, "cpu_usage", 1, "5m").isEmpty()) {
                throw new EngineFunctional.HarnessFailure("关闭后 series 应为空");
            }
            if (e.purge("cpu", 1L, 1L) != 0) {
                throw new EngineFunctional.HarnessFailure("关闭后 purge 应为 0");
            }
            return "closed+idempotent";
        });
    }

    // ==================== 辅助 ====================

    private static final AtomicLong SEQ = new AtomicLong();

    private static String sub(String name) {
        return root.resolve(name + "-" + SEQ.incrementAndGet()).toAbsolutePath().toString();
    }

    private static long minuteStart(long millis) {
        return (millis / 60_000L) * 60_000L;
    }

    private static boolean eqNum(Object actual, double expected) {
        return actual instanceof Number n && Math.abs(n.doubleValue() - expected) < 1e-9;
    }

    private static void expectType(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new EngineFunctional.HarnessFailure(
                    "期望 " + type.getSimpleName() + "，实际 " + EngineFunctional.chain(t));
        }
        throw new EngineFunctional.HarnessFailure("未抛 " + type.getSimpleName());
    }

    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }
}
