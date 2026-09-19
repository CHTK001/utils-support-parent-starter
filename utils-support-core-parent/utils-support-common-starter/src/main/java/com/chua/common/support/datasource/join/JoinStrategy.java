package com.chua.common.support.datasource.join;

import java.util.List;
import java.util.Map;

/**
 * JOIN 策略 SPI 接口。
 *
 * <p>默认启用 {@code none}（单表模式），通过配置切换到 {@code inner} / {@code outer}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface JoinStrategy {

    /**
     * 策略名称
     * @return 结果字符串
     */
    String name();

    /** 是否启用（none 默认不启用） */
    default boolean enabled() { return false; }

    /**
    * 执行 JOIN 查询。
    *
    * @param ctx 查询上下文（含两个表的记录集合和 join 条件）
    * @return 结果行列表
    */
    List<Map<String, Object>> execute(JoinContext ctx);

    /** JOIN 查询上下文 */
    record JoinContext(
            String leftTable,
            List<Map<String, Object>> leftRows,
            String rightTable,
            List<Map<String, Object>> rightRows,
            String leftKey,
            String rightKey,
            List<String> projectColumns
    ) {}
}
