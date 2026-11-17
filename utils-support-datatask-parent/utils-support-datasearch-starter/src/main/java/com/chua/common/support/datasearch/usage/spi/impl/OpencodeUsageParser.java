// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.utils.StringUtils;

/**
 * 打开编码 用量解析器 — 从本地 sqlite 数据库解析会话与消息用量
 *
 * <p>数据源: {@code %USERPROFILE%\.local\share\opencode\opencode.db}
 *
 * <p>解析 {@code message} 表中的 {@code data} JSON 列，提取每次请求的
 * 输入/输出/推理/缓存 令牌、费用、模型及服务商信息，
 * 映射为标准的 {@link AiUsage} 记录。
 *
 * <p>源的 {@code tokens.input} 只记未命中缓存的输入，与 {@code cache.read}、
 * {@code cache.write} 三段互斥（本机实测输入中位数 988、命中中位数 101376），
 * 因此三者相加才是全量输入；命中量另记于 {@code cacheTokens} 供补全器折算。</p>
 *
 * <p><b>取数方式：惰性 keyset 分页。</b>本机 {@code session_message} 有 102 万条带 token 的
 * assistant 行、{@code message} 另有 6.5 万条，全量装载需 400 秒以上并占用 GB 级堆内存，
 * 调用方按 300 秒超时中断后整批丢弃 —— 数据永远入不了库。改为逐页产出后，每页只持有一行
 * 投影后的标量（{@code data} 的 JSON 在 SQL 侧就展开成列，不进 JVM），内存与总行数无关。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
@Slf4j
public class OpencodeUsageParser extends BaseUsageParser {

    /**
     * DB 路径
    */
    private static final Path DB_PATH = resolveDbPath();

    /**
     * resolvedb路径。
     * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        Path fallback = Path.of(System.getProperty("user.home"),
                ".local", "share", "opencode", "opencode.db");
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (StringUtils.isNotBlank(xdgDataHome)) {
            return firstExisting(Path.of(xdgDataHome, "opencode", "opencode.db"), fallback);
        }
        return firstExisting(fallback);
    }

    /**
     * 单页行数：keyset 分页的取数窗口。
     *
     * <p>本机实测 2000 行/页时 {@code session_message} 约 0.45 秒/页、{@code message}
     * 约 4 秒/页；页再大则单次查询变长，下游取消时回退的粒度也变粗。</p>
     */
    private static final int PAGE_SIZE = 2000;

    /**
     * 迟到 token 的重叠窗口（毫秒）：一天。
     *
     * <p>消息行可能先落库、完成时才把 token 写进 {@code data}，只按 {@code time_created}
     * 单调推进会把这类迟到的完成记录永久漏掉，故扫到表尾后回看一天；重叠部分由
     * {@code UsageAggregationTask} 的 requestId 去重挡住。</p>
     */
    private static final long OVERLAP_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * 新库 {@code session_message} 的游标（跨轮次保留）。
     */
    private static final Cursor SESSION_CURSOR = new Cursor();

    /**
     * 旧库 {@code message} 的游标（跨轮次保留）。
     */
    private static final Cursor LEGACY_CURSOR = new Cursor();

    /**
     * 新库 {@code session_message} 用量查询（9-03 之后的数据都在这里）。
     *
     * <p>与旧 {@code message} 表的列序保持一致，共用 {@link #toAiUsage}。首字时间取
     * {@code content} 里最早带 {@code time.created} 的块（文字/推理/工具），即模型第一个
     * 产出落库的时刻。</p>
     */
    private static final String SQL_SESSION_MESSAGES =
            "SELECT time_created, COALESCE(CAST(json_extract(data, '$.time.completed') AS INTEGER), time_updated), "
            + "CAST(json_extract(data, '$.finish') AS TEXT), "
            + "CAST(json_extract(data, '$.model.providerID') AS TEXT), "
            + "CAST(json_extract(data, '$.model.id') AS TEXT), "
            + "CAST(json_extract(data, '$.tokens.input') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.output') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.reasoning') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.read') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.write') AS INTEGER), "
            + "CAST(json_extract(data, '$.cost') AS REAL), "
            + "(SELECT MIN(CAST(json_extract(c.value, '$.time.created') AS INTEGER)) "
            + "   FROM json_each(json_extract(data, '$.content')) c "
            + "  WHERE json_extract(c.value, '$.time.created') IS NOT NULL), "
            + "id "
            + "FROM session_message "
            + "WHERE type = 'assistant' "
            + "  AND (CAST(json_extract(data, '$.tokens.input') AS INTEGER) > 0 "
            + "    OR CAST(json_extract(data, '$.tokens.output') AS INTEGER) > 0) "
            + "  AND (time_created, id) > (?, ?) "
            + "ORDER BY time_created ASC, id ASC LIMIT ?";

    /**
     * 旧库 {@code message} 表用量查询：只补新库没有的行。
     *
     * <p>两表是并行的两套记录而非前后接力——9-14 旧表 4038 行而新表仅 465 行，
     * 8-10~9-02 更是只有旧表有；重叠 id 的 token 全量比对 25228 条完全一致，
     * 故按 id 反连接取并集，不会把同一条请求算两遍。</p>
     */
    private static final String SQL_LEGACY_MESSAGES =
            "SELECT time_created, COALESCE(CAST(json_extract(data, '$.time.completed') AS INTEGER), time_updated), "
            + "CAST(json_extract(data, '$.finish') AS TEXT), "
            + "CAST(json_extract(data, '$.providerID') AS TEXT), "
            + "CAST(json_extract(data, '$.modelID') AS TEXT), "
            + "CAST(json_extract(data, '$.tokens.input') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.output') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.reasoning') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.read') AS INTEGER), "
            + "CAST(json_extract(data, '$.tokens.cache.write') AS INTEGER), "
            + "CAST(json_extract(data, '$.cost') AS REAL), "
            + "(SELECT MIN(p.time_created) FROM part p "
            + " WHERE p.message_id = message.id "
            + "   AND json_extract(p.data, '$.type') IN ('text', 'reasoning', 'tool')), "
            + "id "
            + "FROM message "
            + "WHERE (CAST(json_extract(data, '$.tokens.input') AS INTEGER) > 0 "
            + "    OR CAST(json_extract(data, '$.tokens.output') AS INTEGER) > 0) "
            + "  AND (time_created, id) > (?, ?) "
            + "  AND NOT EXISTS (SELECT 1 FROM session_message s "
            + "                   WHERE s.id = message.id AND s.type = 'assistant') "
            + "ORDER BY time_created ASC, id ASC LIMIT ?";

    /**
     * 名称。
     * @return 名称的结果
     */
    @Override
    public String name() {
        return "opencode";
    }

    /**
     * 惰性产出全部用量记录：先扫 {@code session_message}，再扫旧 {@code message}。
     *
     * <p>两条流串行订阅（{@code Flux.concat}），各自持有一个跨轮次保留的游标：
     * 下游取消（时间预算用尽）时游标停在上一页末尾，下一轮接着扫，
     * 不会像整批装载那样「超时即全丢、每轮原地重来」。</p>
     *
     * @return 用量记录流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[opencode] 数据库文件不存在: {}", DB_PATH);
            return Flux.empty();
        }
        return Flux.defer(() -> {
            log.info("[opencode] 开始解析（session_message 游标 {}/{}，legacy message 游标 {}/{}）",
                    SESSION_CURSOR.time(), SESSION_CURSOR.id(),
                    LEGACY_CURSOR.time(), LEGACY_CURSOR.id());
            return Flux.concat(
                    streamTable(SQL_SESSION_MESSAGES, SESSION_CURSOR),
                    streamTable(SQL_LEGACY_MESSAGES, LEGACY_CURSOR));
        });
    }

    /**
     * 把一张表的分页查询包装为「开连接 → 分页产出 → 关连接」的流。
     *
     * @param sql    分页查询（三个占位符：游标时间、游标 id、页大小）
     * @param cursor 该表的跨轮次游标
     * @return 用量记录流
     */
    private static Flux<AiUsage> streamTable(String sql, Cursor cursor) {
        return Flux.using(
                OpencodeUsageParser::openConnection,
                conn -> Flux.fromIterable(() -> new PageIterator(conn, sql, cursor)),
                OpencodeUsageParser::closeQuietly);
    }

    /**
     * 打开只读 sqlite 连接。
     *
     * @return 数据库连接
     */
    private static Connection openConnection() {
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        } catch (SQLException e) {
            throw new IllegalStateException("打开 opencode 数据库失败: " + DB_PATH, e);
        }
    }

    /**
     * 关闭连接；关闭失败只记日志，不影响已交付的数据。
     *
     * @param conn 数据库连接
     */
    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("[opencode] 关闭数据库连接失败: {}", e.getMessage());
        }
    }

    /**
     * 单表分页游标：最后一条<b>已整页读出</b>记录的 {@code (time_created, id)}。
     *
     * <p>只有整页读出才推进，因此下游中途取消（本轮时间预算用尽）时，游标停在上一页末尾，
     * 下一轮从那里续扫，不会跳过任何一行。</p>
     *
     * <p>读出与入库之间仍有窗口：整页读出后若插入失败（数据库暂时不可用），
     * 失败行进入内存重试队列，由下一轮<b>先于</b>游标判断重试；进程退出导致重试队列丢失的，
     * 只能靠扫到表尾时的重叠窗口（最近 24 小时）兜底，这一点由 {@code UsageAggregationTask}
     * 的 ERROR 日志显式暴露，不静默吞掉。</p>
     *
     * <p>扫到表尾后复位到「最大时间 - 24 小时」：消息行可能先落库、几分钟后才把 token
     * 写进 {@code data}，只按时间戳单调推进会把这类迟到的完成记录永久漏掉；
     * 重叠部分由 {@code UsageAggregationTask} 的 requestId 去重挡住。</p>
     */
    private static final class Cursor {

        /**
         * 最后一条已交付记录的 time_created（epoch 毫秒），0 表示从头扫。
         */
        private long time;

        /**
         * 最后一条已交付记录的 id；复位到重叠窗口时为空串（小于任何非空 id）。
         */
        private String id = "";

        /**
         * 获取游标时间。
         *
         * @return 游标时间（epoch 毫秒）
         */
        synchronized long time() {
            return time;
        }

        /**
         * 获取游标 id。
         *
         * @return 游标 id；复位后为空串
         */
        synchronized String id() {
            return id;
        }

        /**
         * 整页交付完成：游标推进到该页最后一行。
         *
         * @param time 该页最后一行的 time_created
         * @param id   该页最后一行的 id
         */
        synchronized void commit(long time, String id) {
            if (time <= 0L) {
                // 时间戳缺失（源数据异常）时不推进，避免把游标钉死在 0 反复全表重扫
                return;
            }
            this.time = time;
            this.id = id == null ? "" : id;
        }

        /**
         * 扫到表尾：复位到「最大时间 - 重叠窗口」，保留迟到 token 的兜底窗口。
         *
         * @param maxSeen 本轮见到的最大 time_created
         */
        synchronized void reset(long maxSeen) {
            long next = maxSeen > OVERLAP_MILLIS ? maxSeen - OVERLAP_MILLIS : 0L;
            if (next > time) {
                time = next;
            }
            id = "";
        }
    }

    /**
     * 单表惰性分页迭代器：一次只持有一页，逐行产出。
     *
     * <p>行值比较 {@code (time_created, id) > (?, ?)} 走索引 SEARCH；写成
     * {@code time_created > ? OR (time_created = ? AND id > ?)} 会退化为全索引扫描，
     * 深游标实测 221 秒/页，不可用。</p>
     */
    private static final class PageIterator implements Iterator<AiUsage> {

        /**
         * 数据库连接（由外层 Flux.using 负责关闭）。
         */
        private final Connection conn;

        /**
         * 分页查询。
         */
        private final String sql;

        /**
         * 该表的跨轮次游标。
         */
        private final Cursor cursor;

        /**
         * 当前页的行。
         */
        private List<AiUsage> page = List.of();

        /**
         * 当前页已交付到的下标。
         */
        private int index;

        /**
         * 当前页是否为尾页（不足一页 = 已扫到表尾）。
         */
        private boolean tail;

        /**
         * 是否已无可交付的行。
         */
        private boolean finished;

        /**
         * 本轮已见到的最大 time_created（扫到表尾时用于复位游标）。
         */
        private long maxSeen;

        /**
         * 当前页最后一行的 time_created。
         */
        private long pageEndTime;

        /**
         * 当前页最后一行的 id。
         */
        private String pageEndId = "";

        PageIterator(Connection conn, String sql, Cursor cursor) {
            this.conn = conn;
            this.sql = sql;
            this.cursor = cursor;
            this.maxSeen = cursor.time();
        }

        @Override
        public boolean hasNext() {
            if (index < page.size()) {
                return true;
            }
            if (finished) {
                return false;
            }
            loadPage();
            return index < page.size();
        }

        @Override
        public AiUsage next() {
            if (!hasNext()) {
                throw new NoSuchElementException("opencode 用量已解析完毕");
            }
            AiUsage usage = page.get(index++);
            if (index < page.size()) {
                return usage;
            }
            // 整页交付完成，此时推进游标才是安全的：下游中途取消会停在上一页末尾
            if (tail) {
                finished = true;
                cursor.reset(maxSeen);
            } else {
                cursor.commit(pageEndTime, pageEndId);
            }
            return usage;
        }

        /**
         * 按游标加载下一页。
         */
        private void loadPage() {
            List<AiUsage> rows = new ArrayList<>(PAGE_SIZE);
            long endTime = 0L;
            String endId = "";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, cursor.time());
                stmt.setString(2, cursor.id());
                stmt.setInt(3, PAGE_SIZE);
                // 结果集读完立即关闭，避免长游标期间持有 sqlite 语句句柄
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(toAiUsage(rs));
                        endTime = rs.getLong(1);
                        endId = rs.getString(13);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("opencode 分页查询失败: " + e.getMessage(), e);
            }
            page = rows;
            index = 0;
            if (rows.isEmpty()) {
                // 已扫到表尾：复位到重叠窗口，迟到的 token 下一轮还能被捞回来
                finished = true;
                cursor.reset(maxSeen);
                return;
            }
            tail = rows.size() < PAGE_SIZE;
            pageEndTime = endTime;
            pageEndId = endId;
            if (endTime > maxSeen) {
                maxSeen = endTime;
            }
        }
    }

    /**
     * 转为AiUsage。
     *
     * @param rs 方法入参 rs
     * @return AiUsage 对象
     * @throws SQLException 当执行过程不满足前置条件时
     */
    private static AiUsage toAiUsage(ResultSet rs) throws SQLException {
        long startTime = rs.getLong(1);
        long endTime = rs.getLong(2);
        String finishReason = rs.getString(3);
        String provider = rs.getString(4);
        String model = rs.getString(5);
        int inputTokens = rs.getInt(6);
        int outputTokens = rs.getInt(7);
        int reasoningTokens = rs.getInt(8);
        int cacheRead = rs.getInt(9);
        int cacheWrite = rs.getInt(10);
        double costDouble = rs.getDouble(11);
        long firstTokenTime = rs.getLong(12);
        String requestId = rs.getString(13);

        int promptTokens = Math.max(0, inputTokens) + Math.max(0, cacheRead) + Math.max(0, cacheWrite);
        int cacheHit = Math.min(cacheRead, promptTokens);
        int totalTokens = promptTokens + Math.max(0, outputTokens);
        BigDecimal totalCost = BigDecimal.valueOf(costDouble);
        long duration = endTime > startTime ? endTime - startTime : 0L;
        long firstToken = firstTokenTime > startTime ? firstTokenTime - startTime : 0L;

        return AiUsage.builder()
                .provider(provider)
                .model(model)
                .requestId(requestId)
                .finishReason(finishReason)
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .reasoningTokens(reasoningTokens > 0 ? reasoningTokens : null)
                .cacheTokens(cacheHit > 0 ? Integer.valueOf(cacheHit) : null)
                .totalCost(totalCost.compareTo(BigDecimal.ZERO) > 0 ? totalCost : null)
                .currency("USD")
                .startTime(startTime > 0 ? startTime : null)
                .durationMillis(duration > 0 ? duration : null)
                .firstTokenLatencyMillis(firstToken > 0 ? firstToken : null)
                .build();
    }
}