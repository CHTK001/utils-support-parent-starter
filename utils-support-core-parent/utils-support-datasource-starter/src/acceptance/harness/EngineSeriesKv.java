import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.lang.datasource.series.SeriesEngine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段三：KvEngine / SeriesEngine 子类 + mysql（本地真实服务）。
 *
 * <p>断言全部取自接口 Javadoc 的显式契约，重点覆盖「文档写了但容易没实现」的分支：
 * KV 的 null 值等效删除、TTL 能力自洽；Series 的多指标不得合并、非正回溯小时必须拒绝、
 * purge 不得把「不支持」与「删了 0 条」混为一谈。</p>
 *
 * <p>引擎差异按能力适配而非硬编码期望：{@code DataSourceSeriesEngine} 是「一表一指标」
 * 模型（{@code metrics()} 恒返回 1 个），多指标场景对它不适用，断言会自动降级为「不适用」。</p>
 */
public final class EngineSeriesKv {

    private EngineSeriesKv() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段三：KvEngine / SeriesEngine 子类 + mysql");
        EngineFunctional.emit("=".repeat(78));

        // ---------------- A. KvEngine ----------------
        runQuietly("kv:chronicle-map",
                () -> kvSuite("chronicle-map", new com.chua.chronicle.support.kv.ChronicleMapKv()));
        runQuietly("kv:rocksdb", () -> kvSuite("rocksdb",
                ((com.chua.rocksdb.support.engine.RocksDbEngine) Engine.create("rocksdb"))
                        .addDataSource("default", "target/acc-rocksdb")));
        runQuietly("kv:redis-client", () -> kvSuite("redis-client",
                new com.chua.redis.support.client.RedisClient("redis://127.0.0.1:6379")));

        // ---------------- B. SeriesEngine ----------------
        // 关系型实现要求明细表已存在（列名约定 {target}_monitor_id/_collect_time/_{metric}）
        runQuietly("series:datasource(h2)", () -> {
            var h2 = ((com.chua.h2.support.engine.H2Engine) Engine.create("h2"))
                    .addDataSource("default", "jdbc:h2:mem:accseries;DB_CLOSE_DELAY=-1");
            String t = "ms_monitor_metric_cpu";
            h2.executeSql("CREATE TABLE IF NOT EXISTS " + t + " ("
                    + t + "_monitor_id BIGINT, "
                    + t + "_collect_time TIMESTAMP, "
                    + t + "_cpu_usage DOUBLE, "
                    + t + "_mem_usage DOUBLE)");
            seriesSuite("datasource(h2)",
                    new com.chua.common.support.lang.datasource.series.impl.DataSourceSeriesEngine(h2),
                    t, "cpu_usage", "mem_usage");
        });
        runQuietly("series:redis", () -> {
            var props = new java.util.Properties();
            props.setProperty("url", "redis://127.0.0.1:6379");
            // 按轮唯一的 target：Redis 状态跨轮持久，复用固定表名会让上一轮的指标索引
            // 污染「单指标可推断」场景（陈旧索引项已无数据，任何清理路径都碰不到它）
            seriesSuite("redis", new com.chua.redis.support.engine.RedisSeriesEngine(props),
                    "acc_series_redis_" + System.currentTimeMillis(), "cpu_usage", "mem_usage");
        });

        // ---------------- C. mysql（本机 3306） ----------------
        runQuietly("mysql", () -> EngineFunctional.sqlSuite("mysql",
                () -> ((com.chua.mysql.support.engine.MysqlEngine) Engine.create("mysql"))
                        .addDataSource("default", "127.0.0.1", 3306, "engine_accept", "root", "root"),
                EngineFunctional.Profile.JDBC));

        EngineFunctional.finishAndExit("EngineSeriesKv-report.txt");
    }

    /** KvEngine 契约 + 功能面。 */
    static void kvSuite(String name, KvEngine kv) {
        EngineFunctional.emit("--- kv:" + name + " (" + kv.getClass().getSimpleName() + ") ---");
        String p = name + ".";
        try {
            String key = "acc:kv:" + name;
            String prefix = "acc:kv:" + name + ":p:";

            EngineFunctional.check(p + "put/get 往返", () -> {
                kv.delete(key);
                kv.put(key, "v1");
                String got = kv.get(key);
                if (!"v1".equals(got)) {
                    throw new EngineFunctional.HarnessFailure("get 期望 v1，实际 " + got);
                }
                return "v1";
            });

            EngineFunctional.check(p + "containsKey 真/假", () -> {
                if (!kv.containsKey(key)) {
                    throw new EngineFunctional.HarnessFailure("已写入的键 containsKey 应为 true");
                }
                if (kv.containsKey(key + ":absent")) {
                    throw new EngineFunctional.HarnessFailure("不存在的键 containsKey 应为 false");
                }
                return "ok";
            });

            EngineFunctional.check(p + "get(不存在)=null", () -> {
                Object v = kv.get(key + ":absent");
                if (v != null) {
                    throw new EngineFunctional.HarnessFailure("期望 null，实际 " + v);
                }
                return "null";
            });

            EngineFunctional.check(p + "put(k,null) 等效删除", () -> {
                kv.put(key, null);
                if (kv.containsKey(key)) {
                    throw new EngineFunctional.HarnessFailure("契约明示值可为 null 且等效于删除，实际键仍存在");
                }
                return "已删除";
            });

            EngineFunctional.check(p + "delete 首真次假", () -> {
                kv.put(key, "v2");
                boolean first = kv.delete(key);
                boolean second = kv.delete(key);
                if (!first || second) {
                    throw new EngineFunctional.HarnessFailure("delete 期望 首=true 次=false，实际 " + first + "/" + second);
                }
                return "true/false";
            });

            EngineFunctional.check(p + "incr 原子递增", () -> {
                String ck = key + ":cnt";
                kv.delete(ck);
                long a = kv.incr(ck);
                long b = kv.incr(ck);
                if (a != 1L || b != 2L) {
                    throw new EngineFunctional.HarnessFailure("incr 期望 1→2，实际 " + a + "→" + b);
                }
                kv.delete(ck);
                return "1→2";
            });

            EngineFunctional.check(p + "findAllByPrefix 精确匹配", () -> {
                kv.put(prefix + "a", "1");
                kv.put(prefix + "b", "2");
                kv.put("acc:kv:" + name + ":other", "3");
                Map<String, String> hit = kv.findAllByPrefix(prefix);
                if (hit == null) {
                    throw new EngineFunctional.HarnessFailure("findAllByPrefix 返回 null（契约要求返回 Map）");
                }
                if (hit.size() != 2 || !"1".equals(hit.get(prefix + "a")) || !"2".equals(hit.get(prefix + "b"))) {
                    throw new EngineFunctional.HarnessFailure("前缀命中不符: " + hit);
                }
                kv.delete(prefix + "a");
                kv.delete(prefix + "b");
                kv.delete("acc:kv:" + name + ":other");
                return "size=2";
            });

            EngineFunctional.check(p + "key() 链式 set/get/exists/delete", () -> {
                String ck = key + ":chain";
                kv.key(ck).delete();
                kv.key(ck).set("cv");
                String got = kv.key(ck).get();
                boolean ex = kv.key(ck).exists();
                boolean del = kv.key(ck).delete();
                if (!"cv".equals(got) || !ex || !del) {
                    throw new EngineFunctional.HarnessFailure("链式结果异常: get=" + got + " exists=" + ex + " delete=" + del);
                }
                return "cv/true/true";
            });

            EngineFunctional.check(p + "TTL 能力自洽", () -> {
                boolean ttl = kv.supportsTtl();
                String tk = key + ":ttl";
                if (ttl) {
                    kv.put(tk, "t", Duration.ofSeconds(120));
                    long remain = kv.ttl(tk);
                    if (remain <= 0 || remain > 120) {
                        throw new EngineFunctional.HarnessFailure("支持 TTL 时 ttl() 期望 (0,120]，实际 " + remain);
                    }
                    kv.expire(tk, 60);
                    long after = kv.ttl(tk);
                    if (after <= 0 || after > 60) {
                        throw new EngineFunctional.HarnessFailure("expire 后 ttl() 期望 (0,60]，实际 " + after);
                    }
                    kv.delete(tk);
                    return "supportsTtl=true, ttl=" + remain + "→" + after;
                }
                try {
                    kv.put(tk, "t", Duration.ofSeconds(120));
                    throw new EngineFunctional.HarnessFailure(
                            "supportsTtl=false 时带 TTL 的 put 未抛 UnsupportedOperationException");
                } catch (UnsupportedOperationException expected) {
                    return "supportsTtl=false 且按契约抛异常";
                }
            });
        } finally {
            closeQuietly(kv);
        }
    }

    /**
     * SeriesEngine 契约 + 功能面。
     *
     * @param name   套件名
     * @param se     被测引擎
     * @param target 目标表/测量名（关系型实现必须是已登记且已建表的 target）
     * @param metric 主指标名
     * @param second 次指标名（用于构造「多指标」场景；引擎不支持时自动降级）
     */
    static void seriesSuite(String name, SeriesEngine se, String target, String metric, String second) {
        EngineFunctional.emit("--- series:" + name + " (" + se.getClass().getSimpleName() + ") ---");
        String p = name + ".";
        long base = System.currentTimeMillis() - 3_600_000L;

        // 清场：Redis 等外部后端的数据跨轮持久，上一轮遗留的指标会让「单指标可推断」
        // 变成「多指标必须拒绝」。契约支持 purge(target, null, before) 清理该 target 全部监控目标。
        EngineFunctional.check(p + "清场（清空该 target 历史点）", () -> {
            try {
                int r = se.purge(target, null, System.currentTimeMillis() + 86_400_000L);
                return "r=" + r;
            } catch (RuntimeException e) {
                return "跳过（" + e.getClass().getSimpleName() + "）";
            }
        });

        EngineFunctional.check(p + "engine()/isAvailable()", () -> {
            String e = se.engine();
            if (e == null || e.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("engine() 返回空");
            }
            if (!se.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("刚构造的引擎 isAvailable() 应为 true");
            }
            return e;
        });

        EngineFunctional.check(p + "writePoint + series 曲线", () -> {
            for (int i = 0; i < 5; i++) {
                se.writePoint(target, 1L, metric, 10.0 + i, base + i * 60_000L);
            }
            List<List<Object>> curve = se.series(target, 1L, metric, 2, "1m");
            if (curve == null || curve.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("曲线为空（契约要求写入后应可查）");
            }
            long prev = Long.MIN_VALUE;
            for (List<Object> pair : curve) {
                long ts = ((Number) pair.get(0)).longValue();
                if (ts < prev) {
                    throw new EngineFunctional.HarnessFailure("曲线时间戳未升序");
                }
                prev = ts;
            }
            return "points=" + curve.size();
        });

        EngineFunctional.check(p + "writeBatch + metrics 列表", () -> {
            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put(metric, 20.0);
            metrics.put(second, 30.0);
            se.writeBatch(target, 2L, metrics, base + 60_000L);
            List<String> names = se.metrics(target, 2L);
            if (names == null) {
                throw new EngineFunctional.HarnessFailure("metrics() 返回 null（契约禁止）");
            }
            if (!names.contains(metric)) {
                throw new EngineFunctional.HarnessFailure("metrics() 应至少含主指标 " + metric + "，实际 " + names);
            }
            return "metrics=" + names;
        });

        EngineFunctional.check(p + "多指标时 metricName=null 必须拒绝", () -> {
            List<String> names = se.metrics(target, 2L);
            if (names == null || names.size() < 2) {
                // 一表一指标的关系型实现天然不存在「指标不唯一」场景，断言不适用
                return "不适用（该引擎一表一指标，metrics=" + names + "）";
            }
            try {
                List<List<Object>> merged = se.series(target, 2L, null, 2, "1m");
                throw new EngineFunctional.HarnessFailure(
                        "契约明示多指标时必须抛 IllegalArgumentException 且列出候选，实际返回 "
                                + merged.size() + " 点（已把不同指标合并成一条曲线）");
            } catch (IllegalArgumentException expected) {
                String msg = String.valueOf(expected.getMessage());
                if (!msg.contains(metric) && !msg.contains(second)) {
                    throw new EngineFunctional.HarnessFailure("异常消息未列出候选指标名: " + msg);
                }
                return "已拒绝并列出候选";
            }
        });

        EngineFunctional.check(p + "单指标时 metricName=null 可推断", () -> {
            se.writePoint(target, 3L, metric, 1.0, base + 60_000L);
            List<List<Object>> curve = se.series(target, 3L, null, 2, "1m");
            if (curve == null || curve.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("单指标应可推断出曲线，实际空");
            }
            return "points=" + curve.size();
        });

        EngineFunctional.check(p + "hours<=0 必须拒绝", () -> {
            try {
                se.series(target, 1L, metric, 0, "1m");
                throw new EngineFunctional.HarnessFailure("hours=0 未抛 IllegalArgumentException（契约禁止静默夹取）");
            } catch (IllegalArgumentException expected) {
                return "已拒绝";
            }
        });

        EngineFunctional.check(p + "purge 真实删除", () -> {
            // 先写入新点，保证本次清理确实有可删数据；否则「删了 0 条」与「不支持」无法区分
            long fresh = base + 30 * 60_000L;
            for (int i = 0; i < 3; i++) {
                se.writePoint(target, 4L, metric, 5.0 + i, fresh + i * 1000L);
            }
            long future = System.currentTimeMillis() + 86_400_000L;
            int deleted = se.purge(target, 4L, metric, future);
            if (deleted == 0) {
                throw new EngineFunctional.HarnessFailure(
                        "写入 3 点后清理返回 0；契约要求 >0 为删除条数、-1 为无法统计、0 仅表示后端不支持");
            }
            if (deleted > 0) {
                List<List<Object>> after = se.series(target, 4L, metric, 24, "1m");
                if (after != null && !after.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("清理后曲线仍非空（返回删除数 " + deleted + "）");
                }
            }
            return "deleted=" + deleted;
        });

        EngineFunctional.check(p + "purge 委托重载可用", () -> {
            long future = System.currentTimeMillis() + 86_400_000L;
            int r = se.purge(target, 1L, future);
            if (r == 0) {
                throw new EngineFunctional.HarnessFailure(
                        "purge(target,id,before) 返回 0；契约要求委托给带指标维度的重载，0 只表示「后端不支持」");
            }
            return "r=" + r;
        });

        EngineFunctional.check(p + "close 后 isAvailable=false", () -> {
            if (!(se instanceof AutoCloseable ac)) {
                return "非 AutoCloseable，跳过";
            }
            ac.close();
            if (se.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("close 后 isAvailable() 仍为 true");
            }
            return "false";
        });
    }

    private static void closeQuietly(Object o) {
        if (o instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
                // 关闭语义不在本阶段断言范围
            }
        }
    }

    /**
     * 隔离执行一个套件：构造或套件本身抛异常时记为一条失败，不影响其余套件。
     *
     * @param name 套件名
     * @param body 套件体
     */
    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }
}
