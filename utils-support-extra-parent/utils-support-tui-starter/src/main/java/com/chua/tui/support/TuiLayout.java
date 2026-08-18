package com.chua.tui.support;

import lombok.Getter;

/**
 * 终端仪表盘布局枚举。
 * <p>
 * 定义固定的网格布局规格，每种规格对应终端上的一种排列方式。
 * 例如 GRID_2x2 表示 2 行 2 列共 4 个组件。
 * </p>
 * <p>
 * 新增 FREE_GRID 表示自由网格，组件通过 colspan/rowspan 动态决定大小，
 * 自动换行填充，多用于 top/bottom 混合布局。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum TuiLayout {

    /** 1 行 1 列：单组件全屏显示 */
    GRID_1x1(1, 1),

    /** 2 行 2 列：四个组件，适合概览式仪表盘 */
    GRID_2x2(2, 2),

    /** 3 行 3 列：九个组件，适合系统监控大屏 */
    GRID_3x3(3, 3),

    /** 4 行 4 列：十六个组件，适合详细监控面板 */
    GRID_4x4(4, 4),

    /**
     * 自由网格：组件通过 colspan/rowspan 动态决定大小，
     * 自动换行填充，多用于 top/bottom 混合布局。
     */
    FREE_GRID(0, 0);

    /** 网格行数 */
    /** Rows */
    private final int rows;

    /** 网格列数 */
    /** Cols */
    private final int cols;

    /**
     * 构造布局枚举。
     *
     * @param rows 行数
     * @param cols 列数
     */
    TuiLayout(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
    }

    /**
     * 获取网格行数。
     *
     * @return 行数
     */
    public int getRows() {
        return rows;
    }

    /**
     * 获取网格列数。
     *
     * @return 列数
     */
    public int getCols() {
        return cols;
    }

    /**
     * 获取网格总组件数。
     * <p>
     * 对于 FREE_GRID 返回 0，表示无固定容量限制。
     * </p>
     *
     * @return rows × cols，FREE_GRID 返回 0
     */
    public int getCapacity() {
        if (this == FREE_GRID) {
            return 0;
        }
        return rows * cols;
    }

    /**
     * 判断是否为自由网格布局。
     *
     * @return true 表示自由网格
     */
    public boolean isFreeGrid() {
        return this == FREE_GRID;
    }
}
