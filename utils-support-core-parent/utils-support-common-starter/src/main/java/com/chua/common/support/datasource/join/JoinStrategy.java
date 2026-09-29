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
     * JOIN 查询上下文。
     *
     * <p>交给 {@link JoinStrategy#execute(JoinContext)} 的入参载体：左右两张表的
     * 表名、已从数据库查出的行集合、关联键列名与投影列。
     * 行集合由调用方先各自查询好再组装，本 record 不做任何 IO。</p>
     *
     * <p>表名以 {@link String} 形式承载（不是表对象），行以
     * {@code Map<列名, 列值>} 形式承载，关联比较统一走
     * {@code String.valueOf(row.get(key))}，即数值列也按字符串匹配。</p>
     *
     * @param leftTable      左（驱动）侧表名，仅用于报错信息与结果溯源，策略实现不据此访问数据源；
     *                      不允许为 {@code null}
     * @param leftRows       左表记录集合，每行为「列名 → 列值」映射，键即查询结果的列名；
     *                      允许为 {@code null}，此时按空集合处理；行内列值允许为 {@code null}
     *                      （规范构造器只拷外层列表，不动行内元素）
     * @param rightTable     右（被驱动）侧表名，作用同 {@link #leftTable}；不允许为 {@code null}
     * @param rightRows      右表记录集合，行结构同 {@link #leftRows}；
     *                      允许为 {@code null}。{@code InnerJoinStrategy} 会先按
     *                      {@link #rightKey} 对其做哈希分组，故数据量大时该列的选择性直接决定性能
     * @param leftKey        左表关联键的<b>列名</b>，取值方式是 {@code left.get(leftKey)}；
     *                      不允许为 {@code null}（取不到会得到 {@code "null"} 字面量而误匹配）
     * @param rightKey       右表关联键的<b>列名</b>，与 {@link #leftKey} 按字符串相等配对；
     *                      不允许为 {@code null}
     * @param projectColumns 投影列名列表，限定结果行只输出这些列；允许为 {@code null}，
     *                      且为 {@code null} 或空列表时输出左右合并后的全部列。
     *                      非空时只保留在合并结果中确实存在的列，不存在的列被静默丢弃
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
