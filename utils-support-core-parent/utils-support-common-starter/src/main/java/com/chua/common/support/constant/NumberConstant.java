package com.chua.common.support.constant;


/**
 * 数字常量接口，定义了常用的数字常量。
 *
 * <p>该接口包含了一些常用的数字常量，用于：
 * <ul>
 *   <li>默认大小（如集合初始容量、缓冲区大小）</li>
 *   <li>常用数值（0、1、2、-1、100、1000等）</li>
 *   <li>类型转换（int、long、double 等基础类型的零和一）</li>
 * </ul>
 *
 * <p>使用这些常量可以：</p>
 * <ul>
 *   <li>避免魔法数字（magic numbers）</li>
 *   <li>提高代码的可读性和可维护性</li>
 *   <li>支持重构时的全局替换</li>
 * </ul>
 *
 * @author CH
 * @since 1.0
 */
public final class NumberConstant {
    private NumberConstant() {}

    /**
     * 默认大小常量 16。
     * 常用于集合初始容量、缓冲区大小、分页大小等场景。
     */
    public static final int DEFAULT_SIZE = 16;
    /**
     * 默认缓冲区大小常量，值为 {@value}（2 << 12 = 8192 字节）。
     */
    public static final int DEFAULT_BUFFER_SIZE = 2 << 12;

    // ========================== int 常量 ==========================

    /**
     * 整数负一常量 -1。
     * 常用于表示未找到、默认错误码、反向索引等场景。
     */
    public static final int NUMBER_MINUS_ONE = -1;

    /**
     * 整数零常量 0。
     * 常用于初始化、比较、默认值等场景。
     */
    public static final int NUMBER_0 = 0;

    /**
     * 整数一常量 1。
     * 常用于计数、索引、增量等场景。
     */
    public static final int NUMBER_1 = 1;

    /**
     * 整数二常量 2。
     * 常用于二分、成对操作、倍数计算等场景。
     */
    public static final int NUMBER_2 = 2;

    /**
     * 整数三常量 3。
     */
    public static final int NUMBER_3 = 3;

    /**
     * 整数四常量 4。
     */
    public static final int NUMBER_4 = 4;

    /**
     * 整数五常量 5。
     */
    public static final int NUMBER_5 = 5;

    /**
     * 整数六常量 6。
     */
    public static final int NUMBER_6 = 6;
    /**
     * 整数七常量 7。
     */
    public static final int NUMBER_7 = 7;
    /**
     * 整数八常量 8。
     * 常用于字节位数、八进制等场景。
     */
    public static final int NUMBER_8 = 8;

    /**
     * 整数九常量 9。
     */
    public static final int NUMBER_9 = 9;

    /**
     * 整数十常量 10。
     * 常用于十进制、百分比基数等场景。
     */
    public static final int NUMBER_10 = 10;

    /**
     * 整数一百常量 100。
     * 常用于百分比计算、分页限制等场景。
     */
    public static final int NUMBER_100 = 100;

    /**
     * 整数一百二十八常量 128。
     * 常用于颜色值计算等场景。
     */
    public static final int MAX_128 = 128;

    /**
     * 整数二百五十五常量 255。
     * 常用于颜色值计算等场景。
     */
    public static final int MAX_255 = 255;

    /**
     * 整数二百五十六常量 256。
     * 常用于颜色值计算等场景。
     */
    public static final int MAX_256 = 256;

    /**
     * 整数千常量 1000。
     * 常用于毫秒转秒、千分比等场景。
     */
    public static final int NUMBER_1000 = 1000;

    // ========================== long 常量 ==========================

    /**
     * 长整型零常量 0L。
     */
    public static final long LONG_ZERO = 0L;

    /**
     * 长整型一常量 1L。
     */
    public static final long LONG_ONE = 1L;

    /**
     * 长整型负一常量 -1L。
     */
    public static final long LONG_MINUS_ONE = -1L;

    // ========================== double 常量 ==========================

    /**
     * 双精度浮点数零常量 0.0。
     */
    public static final double DOUBLE_ZERO = 0.0;

    /**
     * 双精度浮点数一常量 1.0。
     */
    public static final double DOUBLE_ONE = 1.0;

    /** 一千（int），NUMBER_1000 的别名 */
    public static final int ONE_THOUSAND = 1000;

    /** 两千（int） */
    public static final int TWO_THOUSAND = 2000;

}
