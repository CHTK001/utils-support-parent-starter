package com.chua.common.support.datasource.join;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * 是否启用（none 默认不启用）
     */
    default boolean enabled() { return false; }

    /**
     * 执行 JOIN 查询。
     *
     * @param ctx 查询上下文（含两个表的记录集合和 join 条件）
     * @return 结果行列表
     */
    List<Map<String, Object>> execute(JoinContext ctx);

    /**
     * JOIN 查询上下文
     */
    record JoinContext(
            String leftTable,
            List<Map<String, Object>> leftRows,
            String rightTable,
            List<Map<String, Object>> rightRows,
            String leftKey,
            String rightKey,
            List<String> projectColumns
    ) {

        /**
         * 规范构造器：对三个列表组件做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。
         * {@code InnerJoinStrategy} 显式判空 {@code ctx.projectColumns() != null}，
         * 说明该列表允许为 null；行集合来自数据库查询，元素可含 null 值。
         * 因此三个列表均保留 null 语义并采用可空安全写法。</p>
         *
         * @param leftTable      左表名
         * @param leftRows       左表记录集合，可为 null
         * @param rightTable     右表名
         * @param rightRows      右表记录集合，可为 null
         * @param leftKey        左表关联键
         * @param rightKey       右表关联键
         * @param projectColumns 投影列，可为 null
         */
        public JoinContext {
            leftRows = copyRows(leftRows);
            rightRows = copyRows(rightRows);
            projectColumns = projectColumns == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(projectColumns));
        }

        /**
         * 复制行集合：仅做外层防御性拷贝，行内的 null 值不参与拷贝语义。
         *
         * @param rows 行集合，可为 null
         * @return 不可变行集合，入参为 null 时返回 null
         */
        private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
            if (rows == null) {
                return null;
            }
            return Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }
}
