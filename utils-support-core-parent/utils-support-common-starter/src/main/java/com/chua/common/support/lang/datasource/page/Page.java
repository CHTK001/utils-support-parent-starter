package com.chua.common.support.lang.datasource.page;

import java.util.Collections;
import java.util.List;

/**
 * 通用分页结果封装，将数据库分页查询的结果统一包装为带分页信息的对象。
 * <p>
 * 包含当前页的数据列表以及总记录数、总页数等分页元数据。
 * 适用于前端分页表格、列表等场景。
 * </p>
 * <p>
 * 属性说明：
 * <ul>
 *   <li>{@code pageNum} — 当前页码（从 1 开始）</li>
 *   <li>{@code pageSize} — 每页记录数</li>
 *   <li>{@code total} — 总记录数</li>
 *   <li>{@code records} — 当前页的数据列表</li>
 *   <li>{@link #getPages()} — 总页数（根据 total 和 pageSize 自动计算）</li>
 *   <li>{@code nextCursor} — 游标（keyset）分页的下一页键值，null 表示按页码翻页</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 创建分页结果
 * List<User> users = queryUserPage();
 * Page<User> page = new Page<>(1, 10, 100, users);
 *
 * // 访问分页信息
 * int pageNum = page.getPageNum();
 * int totalPages = page.getPages();
 * boolean empty = page.isEmpty();
 * }</pre>
 * </p>
 *
 * @param <T> 数据行类型
 * @author CH
 * @since 2024/12/12
 */
public record Page<T>(int pageNum, int pageSize, long total, List<T> records, List<Object> nextCursor) {

    /**
     * 构造分页结果（按页码翻页，不含游标）。
     *
     * @param pageNum  当前页码
     * @param pageSize 每页记录数
     * @param total    总记录数
     * @param records  当前页数据列表（null 视为空列表）
     */
    public Page(int pageNum, int pageSize, long total, List<T> records) {
        this(pageNum, pageSize, total, records, null);
    }

    /**
     * 构造分页结果。
     *
     * @param pageNum    当前页码
     * @param pageSize   每页记录数
     * @param total      总记录数
     * @param records    当前页数据列表（null 视为空列表）
     * @param nextCursor 下一页游标键值（null 表示按页码翻页或已到末页）
     */
    public Page {
        records = records != null ? records : Collections.emptyList();
        // 游标键值是绑定值，做防御性拷贝避免调用方后续修改影响翻页
        nextCursor = (nextCursor == null || nextCursor.isEmpty())
                ? null : List.copyOf(nextCursor);
    }

    /**
     * 获取当前页码（从 1 开始）。
     * @return 结果数值
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * 获取每页记录数。
     * @return 结果数值
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * 获取总记录数。
     * @return 结果数值
     */
    public long getTotal() {
        return total;
    }

    /**
     * 获取总页数。
     * <p>根据总记录数和每页记录数自动计算。</p>
     *
     * @return 总页数，pageSize ≤ 0 时返回 0
     */
    public long getPages() {
        return pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
    }

    /**
     * 获取当前页的数据列表。
     * @return 结果列表，无数据时为空列表
     */
    public List<T> getRecords() {
        return records;
    }

    /**
     * 判断当前页是否为空（无数据）。
     * @return 是否成功（true 表示成功）
     */
    public boolean isEmpty() {
        return records.isEmpty();
    }

    /**
     * 获取下一页游标键值（keyset 分页）。
     *
     * <p>仅当引擎开启游标分页、且本次分页满足「物理分页 + 有 ORDER BY + 无
     * GROUP BY/JOIN」时才有值；值为当前页末行的排序键，顺序与 ORDER BY 一致，
     * 下次调用 {@code LambdaQueryWrapper#cursor(List)} 传入即可取下一页。</p>
     *
     * @return 游标键值，null 表示按页码翻页或已到末页
     */
    public List<Object> getNextCursor() {
        return nextCursor;
    }

    /**
     * 判断是否还有下一页。
     * <p>有游标时以游标为准（末页无游标）；无游标时按总记录数推算。</p>
     *
     * @return true 表示存在下一页
     */
    public boolean hasNext() {
        if (nextCursor != null) {
            return true;
        }
        return pageSize > 0 && (long) pageNum * pageSize < total;
    }
}
