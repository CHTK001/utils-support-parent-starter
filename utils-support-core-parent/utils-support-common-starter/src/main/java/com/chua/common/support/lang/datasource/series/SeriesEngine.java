package com.chua.common.support.lang.datasource.series;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时序（Series）引擎统一契约。
 *
 * <p>该接口定义了与具体后端无关的时序数据操作集合，
 * 涵盖 MySQL（DataSource）、Redis（ZSET）、InfluxDB 等后端的通用能力：
 * 单指标点写入、批量指标点写入、按时间窗口查询降采样曲线。</p>
 *
 * <p><b>写入</b>：按 {@code (target, monitorId, metricName, value, timestamp)} 五元组写入，
 * 后端可自行选择存储结构（MySQL 明细表、Redis ZSET、InfluxDB measurement）。</p>
 *
 * <p><b>查询</b>：按 {@code (target, monitorId, hours, window)} 查询降采样曲线，
 * 返回 {@code [[ts, value], ...]} 升序数组，前端直接用于折线图。</p>
 *
 * <h2>SPI 契约</h2>
 * <pre>{@code
 * // 注册文件：META-INF/extensions/com.chua.common.support.lang.datasource.series.SeriesEngine
 * // 内容格式：别名=实现类全限定名，例如
 * // datasource=com.chua.common.support.lang.datasource.series.impl.DataSourceSeriesEngine
 * // redis=com.chua.redis.support.engine.RedisSeriesEngine
 *
 * Properties props = new Properties();
 * props.setProperty("engine", "datasource");
 * SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("datasource", props);
 * engine.writePoint("ms_monitor_metric_cpu", 1L, "cpu_usage", 25.5, System.currentTimeMillis());
 * List<List<Object>> curve = engine.series("ms_monitor_metric_cpu", 1L, 1, "5m");
 * }</pre>
 *
 * <h2>Spring 注入</h2>
 * <pre>{@code
 * @Autowired
 * private SeriesEngine seriesEngine;
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SeriesEngine {

    /**
     * 引擎标识（与配置 {@code monitor.timeseries.engine} 对应）
     *
     * @return 引擎名，如 datasource / redis / influxdb
     */
    String engine();

    /**
     * 是否可用。
     *
     * <p><b>语义边界（务必分清）</b>：本方法只回答「本引擎是否<b>处于可接受请求的状态</b>」
     * ——依赖装配成功、底层客户端存在、尚未 {@code close}。它<b>不探测后端实时可达性</b>，
     * 也不会为此发起网络请求：{@code writePoint}／{@code series} 在采集与查询主链路上
     * 高频调用，每次探活都会放大为后端的连接压力。</p>
     *
     * <p>因此后端宕机时本方法仍可能返回 {@code true}，<b>这不是缺陷</b>：
     * 故障的可见性由「操作必须抛异常」保证——{@link #writePoint} 抛
     * {@link IllegalStateException}，{@link #series} 抛
     * {@link IllegalStateException}，都不得降级为空结果。
     * 换言之 {@code isAvailable() == true} 表示「值得发起一次调用」，
     * 而不是「调用一定成功」。</p>
     *
     * @return true 表示引擎已装配且未关闭，可以发起调用
     */
    boolean isAvailable();

    /**
     * 写入单个指标点。
     * <p>契约：任何失败都必须抛出，实现不得静默丢弃数据点。调用方若要 best-effort
     * 语义（如采集主链路旁路双写），自行捕获并记录。</p>
     *
     * @param target    目标表/测量名，如 ms_monitor_metric_cpu
     * @param monitorId 监控目标标识
     * @param metricName 指标名，如 cpu_usage
     * @param value     指标值
     * @param timestamp 时间戳（毫秒）
     * @throws IllegalArgumentException 参数为空或标识符含非法字符
     * @throws IllegalStateException    引擎不可用或已关闭
     */
    void writePoint(String target, Long monitorId, String metricName, double value, long timestamp);

    /**
     * 批量写入指标点。
     * <p>逐个委托 {@link #writePoint}，失败语义与其一致。</p>
     *
     * @param target    目标表/测量名
     * @param monitorId 监控目标标识
     * @param metrics   指标名 → 指标值
     * @param timestamp 时间戳（毫秒）
     * @throws IllegalArgumentException 参数为空或标识符含非法字符
     * @throws IllegalStateException    引擎不可用或已关闭
     */
    default void writeBatch(String target, Long monitorId, Map<String, Double> metrics, long timestamp) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            writePoint(target, monitorId, entry.getKey(), entry.getValue(), timestamp);
        }
    }

    /**
     * 列出某 target 下某个监控目标已写入的全部指标名。
     *
     * <p>本方法是 {@link #series(String, Long, String, int, String)} 的配套发现接口：
     * 由于同一 target 下可并存多个指标，调用方必须先通过本方法拿到候选指标名，
     * 再显式指定要查询哪一条曲线。实现不得返回 {@code null}。</p>
     *
     * @param target    目标表/测量名
     * @param monitorId 监控目标标识；{@code null} 表示列出该 target 下全部目标
     * @return 已写入的指标名，按字典序升序；无数据或后端不支持发现时返回空列表
     * @throws IllegalArgumentException target 非法
     * @throws IllegalStateException    后端查询失败
     */
    default List<String> metrics(String target, Long monitorId) {
        return List.of();
    }

    /**
     * 均值曲线查询（前端折线图），显式指定指标维度。
     *
     * <p>契约：查询失败必须抛出，不得降级成空列表——空曲线与后端故障对使用者是
     * 两件事。仅在引擎明确不可用（{@link #isAvailable()} 为 false）时返回空列表。</p>
     *
     * <p><b>指标维度是强约束</b>：同一 {@code target} 下可并存多个指标，
     * 把它们混进同一条曲线会产生语义上无意义的数值。实现必须遵守：</p>
     * <ul>
     *   <li>{@code metricName} 非空：只返回该指标的曲线；</li>
     *   <li>{@code metricName} 为 {@code null}：仅当该 {@code (target, monitorId)}
     *       下<b>恰好只有一个</b>指标时可返回该曲线；若存在多个指标，
     *       <b>必须</b>抛 {@link IllegalArgumentException} 并在消息中列出候选指标名，
     *       <b>严禁</b>合并成一条曲线——那会让折线图的纵轴失去意义且不报错。</li>
     * </ul>
     *
     * @param target     目标表/测量名，如 ms_monitor_metric_cpu
     * @param monitorId  监控目标标识
     * @param metricName 指标名，如 cpu_usage；{@code null} 表示由实现按上文规则推断，
     *                   推断不唯一时必须抛异常
     * @param hours      回溯小时数，必须大于 0
     * @param window     聚合窗口，如 1m/5m/1h
     * @return [[ts, value], ...] 升序
     * @throws IllegalArgumentException target 非法、hours 非正数，或指标无法唯一确定
     * @throws IllegalStateException    后端查询失败
     */
    List<List<Object>> series(String target, Long monitorId, String metricName, int hours, String window);

    /**
     * 均值曲线查询（前端折线图），由实现按唯一性规则推断指标。
     *
     * <p>等价于 {@link #series(String, Long, String, int, String)} 且
     * {@code metricName == null}：仅在指标唯一时可返回曲线，多个指标时同样抛
     * {@link IllegalArgumentException}。保留本重载是为了兼容既有调用方，
     * 但它无法表达「要哪条曲线」，新代码请显式传 {@code metricName}。</p>
     *
     * @param target    目标表/测量名，如 ms_monitor_metric_cpu
     * @param monitorId 监控目标标识
     * @param hours     回溯小时数，必须大于 0
     * @param window    聚合窗口，如 1m/5m/1h
     * @return [[ts, value], ...] 升序
     * @throws IllegalArgumentException target 非法、hours 非正数，或指标无法唯一确定
     * @throws IllegalStateException    后端查询失败
     */
    default List<List<Object>> series(String target, Long monitorId, int hours, String window) {
        return series(target, monitorId, null, hours, window);
    }

    /**
     * 校验回溯小时数，非法值显式拒绝而不是静默改写。
     *
     * <p>历史上三个实现都用 {@code Math.max(1, Math.min(hours, 24 * 7))} 夹取，
     * 导致 {@code hours <= 0} 被悄悄改成 1 小时、更大的请求被悄悄砍到 7 天，
     * 调用方拿到的曲线与自己的请求不符却毫无察觉。静默篡改入参比报错更危险，
     * 因此统一由本方法把关。</p>
     *
     * @param hours 调用方传入的回溯小时数
     * @return 实际生效的回溯小时数
     * @throws IllegalArgumentException hours 非正数
     */
    static int requirePositiveHours(int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("回溯小时数必须大于 0: " + hours);
        }
        return hours;
    }

    /**
     * 删除某 target 在指定时间点之前的时序点（保留期清理）。
     *
     * <p>与读写一样与后端无关：关系型实现执行 DELETE，Influx 执行 DELETE FROM ... WHERE time &lt; ?，
     * Redis 按 ZREMRANGEBYSCORE 清理。默认实现不支持、返回 0，各后端按需覆盖。</p>
     *
     * <p>本重载<b>委托</b>给 {@link #purge(String, Long, String, long)} 且 {@code metricName}
     * 传 {@code null}（即「清理全部指标」），因此实现只需覆写带指标维度的那一个。
     * 委托方向不可颠倒：若本重载留在接口里直接 {@code return 0}，而实现只覆写带
     * {@code metricName} 的重载，则既有调用方（走本签名）会永远拿到 0，
     * 保留期清理静默失效——这正是本方法历史上三个实现全部未覆写所暴露的问题。</p>
     *
     * @param target       目标表/测量名
     * @param monitorId    监控目标标识；{@code null} 表示清理该 target 下全部目标
     * @param beforeMillis 删除该毫秒时间戳之前的点
     * @return 删除点数；无法精确统计返回 -1；后端不支持返回 0
     * @throws IllegalArgumentException target 非法
     * @throws IllegalStateException    后端清理失败
     */
    default int purge(String target, Long monitorId, long beforeMillis) {
        return purge(target, monitorId, null, beforeMillis);
    }

    /**
     * 删除某 target 下指定指标在指定时间点之前的时序点（保留期清理）。
     *
     * <p>与 {@link #purge(String, Long, long)} 的区别是限定了指标维度：
     * 同一 target 下多个指标可以分别设置不同的保留期。{@code metricName} 为
     * {@code null} 时清理该 target 下全部指标。</p>
     *
     * <p><b>返回值语义必须如实区分</b>：契约允许的三个值各有含义，实现不得混用——
     * {@code > 0} 真实删除条数；{@code -1} 已执行清理但后端无法精确统计；
     * {@code 0} 表示<b>后端不支持</b>清理。若实现把「不支持」返回成 0，
     * 调用方就无法区分「已清理且本批无过期点」与「从未清理过」，
     * 保留期策略会静默失效、时序表无限增长。</p>
     *
     * <p><b>实现只需覆写本重载</b>：{@link #purge(String, Long, long)} 委托到此处。
     * 确实不支持清理的后端不覆写任何重载即可，默认 0 即代表「不支持」。</p>
     *
     * @param target       目标表/测量名
     * @param monitorId    监控目标标识；{@code null} 表示清理该 target 下全部目标
     * @param metricName   指标名；{@code null} 表示清理全部指标
     * @param beforeMillis 删除该毫秒时间戳之前的点
     * @return 删除点数；无法精确统计返回 -1；后端不支持返回 0
     * @throws IllegalArgumentException target 非法
     * @throws IllegalStateException    后端清理失败
     */
    default int purge(String target, Long monitorId, String metricName, long beforeMillis) {
        return 0;
    }

    /**
     * 解析聚合窗口为毫秒数，供各实现类的降采样统一使用。
     *
     * @param window 窗口字符串，如 30s/1m/5m/1h/2d；空或非法回退 5 分钟
     * @return 窗口毫秒数（至少 1 毫秒）
     */
    static long windowMillis(String window) {
        long millis = 300_000L;
        if (window != null && !window.isEmpty()) {
            String lower = window.toLowerCase().trim();
            try {
                char unit = lower.charAt(lower.length() - 1);
                long n = Long.parseLong(lower.substring(0, lower.length() - 1));
                millis = switch (unit) {
                    case 's' -> n * 1000L;
                    case 'm' -> n * 60_000L;
                    case 'h' -> n * 3600_000L;
                    case 'd' -> n * 86400_000L;
                    default -> throw new NumberFormatException("未知窗口单位: " + unit);
                };
            } catch (NumberFormatException e) {
                // 纯数字视为毫秒
                try {
                    millis = Long.parseLong(lower);
                } catch (NumberFormatException ignored) {
                    millis = 300_000L;
                }
            }
        }
        return Math.max(1L, millis);
    }

    /**
     * 按窗口均值降采样并封顶点数。
     *
     * @param points   原始点列表，元素为 {@code [timestampMillis(Number), value(Number)]}，无需预排序
     * @param windowMs 聚合窗口毫秒数
     * @param maxPoints 返回点数上限，超出时等距抽样
     * @return [[bucketStartTs, avg], ...] 升序
     */
    static List<List<Object>> downsample(List<Object[]> points, long windowMs, int maxPoints) {
        List<List<Object>> result = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return result;
        }
        Map<Long, double[]> buckets = new LinkedHashMap<>();
        for (Object[] point : points) {
            long bucket = ((Number) point[0]).longValue() / windowMs;
            double[] acc = buckets.computeIfAbsent(bucket, k -> new double[2]);
            acc[0] += ((Number) point[1]).doubleValue();
            acc[1] += 1;
        }
        List<Long> keys = new ArrayList<>(buckets.keySet());
        keys.sort(Long::compare);
        for (Long bucket : keys) {
            double[] acc = buckets.get(bucket);
            List<Object> pair = new ArrayList<>(2);
            pair.add(bucket * windowMs);
            pair.add(acc[1] > 0 ? acc[0] / acc[1] : 0D);
            result.add(pair);
        }
        if (maxPoints > 0 && result.size() > maxPoints) {
            int step = (int) Math.ceil((double) result.size() / maxPoints);
            List<List<Object>> sampled = new ArrayList<>();
            for (int i = 0; i < result.size(); i += step) {
                sampled.add(result.get(i));
            }
            return sampled;
        }
        return result;
    }
}
