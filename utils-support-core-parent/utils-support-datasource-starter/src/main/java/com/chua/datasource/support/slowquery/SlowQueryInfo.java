package com.chua.datasource.support.slowquery;

/**
 * 慢查询条目（语句摘要维度）。
 *
 * <p>数据来自各内核的聚合视图：MySQL 8 用 {@code performance_schema.events_statements_summary_by_digest}，
 * PostgreSQL 用 {@code pg_stat_statements}。因此这里给的是「同一类语句按摘要聚合后」的统计，
 * 不是逐条日志。</p>
 *
 * <p>时间统一为毫秒（double，保留小数），时间戳统一为 {@code yyyy-MM-dd HH:mm:ss} 字符串，
 * 时区由驱动 / 服务器决定，接口不做转换。</p>
 *
 * @param digest       语句摘要（无摘要能力的实现用语句前若干字符代替）
 * @param digestText   摘要对应的语句样板（可为 null）
 * @param schema       所属库（可为 null）
 * @param calls        执行次数
 * @param totalMs      总耗时（毫秒）
 * @param avgMs        平均耗时（毫秒）
 * @param maxMs        最大耗时（毫秒）
 * @param rowsExamined 扫描行数合计
 * @param rowsSent     返回行数合计
 * @param firstSeen    首次出现时间
 * @param lastSeen     最近出现时间
 * @author CH
 * @since 4.0.0.44
 */
public record SlowQueryInfo(
        String digest,
        String digestText,
        String schema,
        long calls,
        double totalMs,
        double avgMs,
        double maxMs,
        long rowsExamined,
        long rowsSent,
        String firstSeen,
        String lastSeen
) {
}
