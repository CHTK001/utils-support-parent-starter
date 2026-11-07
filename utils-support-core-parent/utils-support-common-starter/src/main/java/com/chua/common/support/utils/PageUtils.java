package com.chua.common.support.utils;

import lombok.Getter;

/**
 * 分页工具类
 * <p>
 * 提供分页相关的计算功能，包括起始位置、结束位置、总页数等计算。
 * 支持自定义首页页码（如从0开始或从1开始）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PageUtils {

    /**
     * page工具。
     */
    private PageUtils() {}

    /**
     * 首页页码，默认为0。
     * 如果业务中页码从1开始，可通过 {@link #setFirstPageNo(int)} 修改。
     */
    @Getter
    private static volatile int firstPageNo = 0;




     * @param pageSize 每页大小
     * @return 包含起始索引和结束索引的数组，格式为 [启动, 结束]
     */
    public static int[] transToStartEnd(int pageNo, int pageSize) {
        int start = getStart(pageNo, pageSize);
        long end = (long) start + pageSize;
        if (end > Integer.MAX_VALUE) {
            end = Integer.MAX_VALUE;
        }
    }

    /**
     * 计算总页数
     *
     * @param totalCount 总记录数
     * @param pageSize   每页大小
     * @return 总页数
     */
    public static int totalPage(int totalCount, int pageSize) {
        if (pageSize <= 0) {
            return 0;
        }
        if (totalCount <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalCount / pageSize);
    }
}
