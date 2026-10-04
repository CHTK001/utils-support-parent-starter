import com.chua.common.support.lang.datasource.series.SeriesEngine;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Properties;

/**
 * 引擎验收运行器 —— 阶段五：补齐 KvEngine / SeriesEngine 全子类与 monitor 装配面。
 *
 * <p>阶段三已覆盖 KvEngine 的 ChronicleMap / RocksDb / RedisClient 三个实现，
 * 以及 SeriesEngine 的 datasource / redis 两个实现。本阶段补齐其**余下实现**：</p>
 * <ul>
 *   <li>{@code RedisKvEngine}（spring-support-redis-starter，基于 Spring
 *       {@code StringRedisTemplate}）—— 用本机 Redis 做**真实读写**验收，
 *       复用阶段三的 KV 套件；</li>
 *   <li>{@code InfluxdbSeriesEngine} —— 本机无 InfluxDB，只验**契约分支**
 *       （必填校验 / 非法标识符 / 不可用时的行为），如实声明不做真实读写；</li>
 *   <li>{@code NoopSeriesEngine}（monitor 私有）—— 经其唯一公开入口
 *       {@code SeriesEngineConfiguration.seriesEngine(...)} 驱动，验其兜底语义；</li>
 *   <li>monitor 的引擎装配路径（SPI 发现 + 连接参数拼装 / 不可用兜底）。</li>
 * </ul>
 */
public final class EngineGaps {

    private EngineGaps() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段五：KvEngine/SeriesEngine 余下子类 + monitor 装配面");
        EngineFunctional.emit("=".repeat(78));

        runQuietly("kv:redis-spring", EngineGaps::redisSpringKvSuite);
        runQuietly("series:influxdb", EngineGaps::influxdbSeriesSuite);
        runQuietly("series:none", EngineGaps::noopSeriesSuite);

        EngineFunctional.finishAndExit("EngineGaps-report.txt");
    }

    /**
     * RedisKvEngine（Spring StringRedisTemplate）真实读写：复用阶段三的 KV 契约套件。
     */
    static void redisSpringKvSuite() {
        EngineFunctional.emit("--- kv:redis-spring (RedisKvEngine, spring StringRedisTemplate) ---");
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        try {
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var kv = new com.chua.starter.redis.support.kv.RedisKvEngine(template);
            EngineSeriesKv.kvSuite("redis-spring", kv);
            EngineFunctional.check("redis-spring.supportsTtl==true（覆写了 TTL 操作）", () -> {
                if (!kv.supportsTtl()) {
                    throw new EngineFunctional.HarnessFailure(
                            "RedisKvEngine 覆写了 put(k,v,ttl)/ttl/expire，契约要求 supportsTtl() 返回 true");
                }
                return "true";
            });
        } catch (Exception e) {
            EngineFunctional.recordFail("kv:redis-spring 套件异常: " + EngineFunctional.chain(e));
        } finally {
            try {
                factory.destroy();
            } catch (Exception ignored) {
                // 连接工厂销毁失败不影响断言结论
            }
        }
    }

    /**
     * InfluxdbSeriesEngine：本机无 InfluxDB，只验契约分支（不做真实读写）。
     */
    static void influxdbSeriesSuite() {
        EngineFunctional.emit("--- series:influxdb (InfluxdbSeriesEngine, 仅契约面) ---");
        String p = "influxdb.";
        // url/database 留空 → 构造器走「未配置」分支，不发起任何网络连接
        InfluxdbSeriesEngineHolder holder;
        try {
            holder = new InfluxdbSeriesEngineHolder(new com.chua.influxdb.support.engine.InfluxdbSeriesEngine(new Properties()));
        } catch (Throwable t) {
            EngineFunctional.recordFail(p + "构造异常: " + EngineFunctional.chain(t));
            return;
        }
        SeriesEngine se = holder.engine;

        EngineFunctional.check(p + "engine()", () -> {
            if (!"influxdb".equals(se.engine())) {
                throw new EngineFunctional.HarnessFailure("engine() 期望 influxdb，实际 " + se.engine());
            }
            return "influxdb";
        });

        EngineFunctional.check(p + "未配置时 isAvailable()==false", () -> {
            if (se.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("url/database 均未配置却报告可用");
            }
            return "false";
        });

        EngineFunctional.check(p + "writePoint 缺必填 → IAE", () -> expectIae(() -> se.writePoint(null, 1L, "cpu", 1.0, 0L)));
        EngineFunctional.check(p + "writePoint 缺 monitorId → IAE", () -> expectIae(() -> se.writePoint("t", null, "cpu", 1.0, 0L)));
        EngineFunctional.check(p + "writePoint 缺 metric → IAE", () -> expectIae(() -> se.writePoint("t", 1L, null, 1.0, 0L)));
        EngineFunctional.check(p + "writePoint 非法标识符 → IAE", () -> expectIae(() -> se.writePoint("t;drop", 1L, "cpu", 1.0, 0L)));
        EngineFunctional.check(p + "writePoint 不可用 → ISE（拒绝静默丢弃）",
                () -> expectState(() -> se.writePoint("acc_t", 1L, "cpu", 1.0, 0L)));

        EngineFunctional.check(p + "metrics(null) → IAE", () -> expectIae(() -> se.metrics(null, 1L)));
        EngineFunctional.check(p + "metrics 不可用 → 空非 null", () -> {
            List<String> m = se.metrics("acc_t", 1L);
            if (m == null || !m.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不可用时应返回空列表，实际 " + m);
            }
            return "empty";
        });

        EngineFunctional.check(p + "series(null target) → IAE", () -> expectIae(() -> se.series(null, 1L, "cpu", 2, "1m")));
        EngineFunctional.check(p + "series(null monitorId) → IAE", () -> expectIae(() -> se.series("acc_t", null, "cpu", 2, "1m")));
        EngineFunctional.check(p + "series(hours<=0) → IAE", () -> expectIae(() -> se.series("acc_t", 1L, "cpu", 0, "1m")));
        EngineFunctional.check(p + "series(hours<0) → IAE", () -> expectIae(() -> se.series("acc_t", 1L, "cpu", -1, "1m")));
        EngineFunctional.check(p + "series 非法标识符 → IAE", () -> expectIae(() -> se.series("acc-t", 1L, "cpu", 2, "1m")));
        EngineFunctional.check(p + "series 不可用 → 空非 null", () -> {
            List<List<Object>> s = se.series("acc_t", 1L, "cpu", 2, "1m");
            if (s == null || !s.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不可用时应返回空列表，实际 " + s);
            }
            return "empty";
        });

        EngineFunctional.check(p + "purge(null) → IAE", () -> expectIae(() -> se.purge(null, 1L, "cpu", 0L)));
        EngineFunctional.check(p + "purge 不可用 → 0（不支持）", () -> {
            int r = se.purge("acc_t", 1L, "cpu", 0L);
            if (r != 0) {
                throw new EngineFunctional.HarnessFailure("不可用时契约要求返回 0，实际 " + r);
            }
            return "0";
        });

        EngineFunctional.check(p + "close 后 isAvailable()==false 且可重复 close", () -> {
            closeQuietly(se);
            if (se.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("close 后仍报告可用");
            }
            closeQuietly(se);
            return "false";
        });
    }

    /**
     * NoopSeriesEngine 与 monitor 的引擎装配路径。
     *
     * <p>NoopSeriesEngine 是 monitor 模块的私有实现，唯一公开入口是
     * {@code SeriesEngineConfiguration.seriesEngine(...)} 这个 Bean 工厂方法，
     * 因此按配置组合驱动它，同时验装配路径本身。</p>
     */
    static void noopSeriesSuite() {
        EngineFunctional.emit("--- series:none (NoopSeriesEngine + monitor 装配) ---");
        String p = "none.";
        var cfg = new com.chua.starter.monitor.support.config.SeriesEngineConfiguration();
        StandardEnvironment env = new StandardEnvironment();

        // 1. engine 为空 → Noop("none")
        SeriesEngine none = cfg.seriesEngine(props(""), null, env);
        EngineFunctional.check(p + "engine 为空 → 非 null 且不可用", () -> {
            if (none == null) {
                throw new EngineFunctional.HarnessFailure("装配结果不得为 null（契约明示）");
            }
            if (none.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("空配置不应报告可用");
            }
            return none.engine();
        });
        EngineFunctional.check(p + "Noop.engine() 非空", () -> {
            String e = none.engine();
            if (e == null || e.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("engine() 返回空");
            }
            return e;
        });
        EngineFunctional.check(p + "Noop.series → 空非 null", () -> {
            List<List<Object>> s = none.series("any", 1L, "cpu", 2, "1m");
            if (s == null || !s.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("空实现应返回空列表，实际 " + s);
            }
            return "empty";
        });
        EngineFunctional.check(p + "Noop.metrics → 空非 null", () -> {
            List<String> m = none.metrics("any", 1L);
            if (m == null || !m.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("空实现应返回空列表，实际 " + m);
            }
            return "empty";
        });
        EngineFunctional.check(p + "Noop.writePoint 静默不抛（空实现语义）", () -> {
            none.writePoint("any", 1L, "cpu", 1.0, 0L);
            return "ok";
        });
        EngineFunctional.check(p + "Noop.purge → 0", () -> {
            int r = none.purge("any", 1L, "cpu", 0L);
            if (r != 0) {
                throw new EngineFunctional.HarnessFailure("空实现 purge 期望 0，实际 " + r);
            }
            return "0";
        });
        closeQuietly(none);

        // 2. engine=none 显式
        EngineFunctional.check(p + "engine=none → 依然非 null 且不可用", () -> {
            SeriesEngine e = cfg.seriesEngine(props("none"), null, env);
            if (e == null || e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("engine=none 应装配不可用实现，实际 " + e);
            }
            closeQuietly(e);
            return e.engine();
        });

        // 3. engine=datasource 但容器无 DataSource → 兜底 Noop，不得抛 NPE
        EngineFunctional.check(p + "engine=datasource 且无 DataSource → 兜底不抛 NPE", () -> {
            SeriesEngine e = cfg.seriesEngine(props("datasource"), null, env);
            if (e == null || e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("无 DataSource 时应兜底为不可用实现，实际 " + e);
            }
            closeQuietly(e);
            return e.engine();
        });

        // 4. 未知引擎名 → 兜底 Noop（扩展点无实现）
        EngineFunctional.check(p + "未知引擎名 → 兜底不抛且不可用", () -> {
            SeriesEngine e = cfg.seriesEngine(props("definitely-not-a-series-engine"), null, env);
            if (e == null || e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("未知引擎应兜底为不可用实现，实际 " + e);
            }
            closeQuietly(e);
            return e.engine();
        });

        // 5. engine=influxdb 但 url 留空 → 装配 InfluxdbSeriesEngine 且不可用（不发起网络）
        EngineFunctional.check(p + "engine=influxdb 且 url 空 → 装配成功且不可用（无网络）", () -> {
            var iv = new com.chua.starter.monitor.support.config.MonitorTimeseriesProperties();
            iv.setEngine("influxdb");
            iv.setUrl("");
            iv.setDatabase("");
            SeriesEngine e = cfg.seriesEngine(iv, null, env);
            if (e == null) {
                throw new EngineFunctional.HarnessFailure("装配结果不得为 null");
            }
            if (e.isAvailable()) {
                throw new EngineFunctional.HarnessFailure("url/database 为空却报告可用");
            }
            closeQuietly(e);
            return e.getClass().getSimpleName();
        });

        // 6. engine=redis → 经 SPI 装配 RedisSeriesEngine，本机 6379 可用
        EngineFunctional.check(p + "engine=redis → SPI 装配且可用（本机 6379）", () -> {
            SeriesEngine e = cfg.seriesEngine(props("redis"), null, env);
            try {
                if (e == null) {
                    throw new EngineFunctional.HarnessFailure("装配结果不得为 null");
                }
                if (!"redis".equals(e.engine())) {
                    throw new EngineFunctional.HarnessFailure("engine() 期望 redis，实际 " + e.engine());
                }
                if (!e.isAvailable()) {
                    throw new EngineFunctional.HarnessFailure("本机 6379 可用，装配结果却报告不可用");
                }
                // 真实写一点并清理，证明装配出来的实例确实连通
                String t = "acc_gaps_" + System.currentTimeMillis();
                e.writePoint(t, 1L, "cpu_usage", 1.0, System.currentTimeMillis());
                List<List<Object>> curve = e.series(t, 1L, "cpu_usage", 1, "1m");
                if (curve == null || curve.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("装配后的实例写入后查不到曲线");
                }
                e.purge(t, null, System.currentTimeMillis() + 86_400_000L);
                return "redis, points=" + curve.size();
            } finally {
                closeQuietly(e);
            }
        });
    }

    /** 构造一份只设 engine 的时序配置。 */
    private static com.chua.starter.monitor.support.config.MonitorTimeseriesProperties props(String engine) {
        var p = new com.chua.starter.monitor.support.config.MonitorTimeseriesProperties();
        p.setEngine(engine);
        return p;
    }

    /** 期望抛出 IllegalArgumentException。 */
    private static String expectIae(Runnable action) {
        try {
            action.run();
            throw new EngineFunctional.HarnessFailure("未抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            return "IllegalArgumentException";
        }
    }

    /** 期望抛出 IllegalStateException。 */
    private static String expectState(Runnable action) {
        try {
            action.run();
            throw new EngineFunctional.HarnessFailure("未抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            return "IllegalStateException";
        }
    }

    /** 隔离执行一个套件：构造或套件本身抛异常时记为一条失败，不影响其余套件。 */
    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }

    /**
     * 关闭 AutoCloseable，忽略关闭期异常。
     *
     * <p>{@code SeriesEngine} 接口本身不继承 AutoCloseable，只有具体实现才可能可关闭，
     * 因此这里按实例判定，而不是在接口上强加 close 语义。</p>
     *
     * @param o 目标对象，可为 null 或不可关闭
     */
    private static void closeQuietly(Object o) {
        if (o instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
                // 关闭语义不在本阶段断言范围
            }
        }
    }

    /** 仅用于给 SeriesEngine 引用加一层 try 包裹，避免构造异常逃逸。 */
    private static final class InfluxdbSeriesEngineHolder {
        private final SeriesEngine engine;
        private InfluxdbSeriesEngineHolder(SeriesEngine engine) {
            this.engine = engine;
        }
    }
}
