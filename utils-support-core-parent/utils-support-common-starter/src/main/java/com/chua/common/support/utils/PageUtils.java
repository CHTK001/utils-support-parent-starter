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
 */
public class PageUtils {

    /**
     * 首页页码，默认为0。
     * 如果业务中页码从1开始，可通过 {@link #setFirstPageNo(int)} 修改。
     */
    @Getter
    private static volatile int firstPageNo = 0;

    /**
     * 设置首页页码
     *
     * @param firstPageNo 首页页码（如 0 或 1）
     */
    public static void setFirstPageNo(int firstPageNo) {
        PageUtils.firstPageNo = firstPageNo;
    }

    /**
     * 计算分页的起始索引（Offset）
     *
     * @param pageNo   当前页码
     * @param pageSize 每页大小
     * @return 起始索引
     */
    public static int getStart(int pageNo, int pageSize) {
        if (pageNo < firstPageNo) {
            pageNo = firstPageNo;
        }
        if (pageSize < 1) {
            return 0;
        }
        // 使用 long 防止计算过程中 int 溢出
        long start = ((long) pageNo - firstPageNo) * pageSize;
        if (start < 0) {
            start = 0;
        } else if (start > Integer.MAX_VALUE) {
            start = Integer.MAX_VALUE;
        }
        return (int) start;
    }

    /**
     * 计算分页的结束索引
     *
     * @param pageNo   当前页码
     * @param pageSize 每页大小
     * @return 结束索引
     */
    public static int getEnd(int pageNo, int pageSize) {
        long start = getStart(pageNo, pageSize);
        long end = start + pageSize;
        if (end > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) end;
    }

    /**
     * 将页码和每页大小转换为起始和结束索引数组
     *
     * @param pageNo   当前页码
     * @param pageSize 每页大小
     * @return 包含起始索引和结束索引的数组，格式为 [start, end]
     */
    public static int[] transToStartEnd(int pageNo, int pageSize) {
        int start = getStart(pageNo, pageSize);
        long end = (long) start + pageSize;
        if (end > Integer.MAX_VALUE) {
            end = Integer.MAX_VALUE;
        }
        return new int[]{start, (int) end};
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
