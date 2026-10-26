package com.chua.datasource.support.slowquery;

import java.util.List;

/**
 * 慢查询分析管理器 SPI 接口。
 *
 * <p>与 {@code UserManager} / {@code ProcessManager} 同一套注册方式：实现按方言协议登记在
 * {@code META-INF/extensions/com.chua.datasource.support.slowquery.SlowQueryManager} 里，
 * 取不到实现即该内核没有慢查询分析能力。</p>
 *
 * <p>注意「有实现」不等于「拿得到数据」：MySQL 的 {@code performance_schema} 可能被关闭、
 * PostgreSQL 可能没装 {@code pg_stat_statements}，这类情况由实现抛异常，调用方按
 * {@code supported=true} + {@code message} 降级告知，而不是把入口藏起来。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
public interface SlowQueryManager {

    /**
     * 返回 SPI 扩展键（与方言协议同名，如 {@code mysql}）。
     *
     * @return 数据库类型标识
     */
    String type();

    /**
     * 按平均耗时倒序列出慢查询摘要。
     *
     * @param minAvgMs 平均耗时下限（毫秒）；{@code <= 0} 时由实现取默认值
     * @param limit    最多返回条数；{@code <= 0} 时由实现取默认值
     * @return 慢查询摘要列表
     */
    List<SlowQueryInfo> listSlowQueries(double minAvgMs, int limit);
}
