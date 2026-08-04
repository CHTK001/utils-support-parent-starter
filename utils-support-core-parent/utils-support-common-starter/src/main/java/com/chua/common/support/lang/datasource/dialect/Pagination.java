package com.chua.common.support.lang.datasource.dialect;

import lombok.Data;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullUnmarked;

/**
 * 分页参数对象，封装前端传入的分页信息与后端返回的总记录数。
 * <p>
 * 通过 {@link #getOffset()} 和 {@link #getLimit()} 计算数据库分页所需的偏移量和限制数，
 * 适用于 MySQL 的 LIMIT ?, ? 以及 PostgreSQL 的 LIMIT ? OFFSET ? 等分页语法。
 * </p>
 * <p>
 * 属性说明：
 * <ul>
 *   <li>{@code pageNum} — 当前页码，从 1 开始（默认 1）</li>
 *   <li>{@code pageSize} — 每页记录数（默认 10）</li>
 *   <li>{@code total} — 总记录数，查询后由框架填充</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
@NullUnmarked
@Data
@Accessors(chain = true)
public class Pagination {

    private int pageNum = 1;

    /**
     * 每页大小
     */
    private int pageSize = 10;

    /**
     * 总数
     */
    private long total;

    /**
     * 获取数据库查询的偏移量，用于分页 SQL 的起始位置。
     * <p>计算公式：(pageNum - 1) * pageSize，最小值为 0。</p>
     *
     * @return 偏移量
     */
    public int getOffset() {
        return (Math.max(pageNum, 1) - 1) * Math.max(pageSize, 1);
    }

    /**
     * 获取数据库查询的限制数，即每页最多返回的记录数。
     * <p>最小值为 1，避免 SQL 语法错误。</p>
     *
     * @return 限制数
     */
    public int getLimit() {
        return Math.max(pageSize, 1);
    }
}
