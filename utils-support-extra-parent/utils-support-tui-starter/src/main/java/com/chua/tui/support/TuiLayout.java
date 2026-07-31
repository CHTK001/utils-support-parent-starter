package com.chua.tui.support;

/**
 * 终端仪表盘布局枚举。
 * <p>
 * 定义固定的网格布局规格，每种规格对应终端上的一种排列方式。
 * 例如 GRID_2x2 表示 2 行 2 列共 4 个组件。
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
    GRID_4x4(4, 4);

    /** 网格行数 */
    private final int rows;

    /** 网格列数 */
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
     *
     * @return rows × cols
     */
    public int getCapacity() {
        return rows * cols;
    }
}
