package com.chua.datasource.support.slowquery;

/**
 * 慢查询条目（语句摘要维度或逐条事件维度）。
 *
 * <p>实现可以给「按摘要聚合后」的统计（MySQL 的
 * {@code events_statements_summary_by_digest}），也可以给逐条慢事件
 * （{@code events_statements_history_long} / {@code slow_log}），由各内核决定。
 * 逐条来源自带客户端 IP / 主机（{@code host}），聚合来源没有则为 null。</p>
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
 * @param host         来源客户端 IP / 主机；逐条事件来源可见，聚合来源为 null
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
        String lastSeen,
        String host
) {
}
