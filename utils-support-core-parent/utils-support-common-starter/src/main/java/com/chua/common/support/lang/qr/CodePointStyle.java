package com.chua.common.support.lang.qr;


/**
 * 二维码点阵样式枚举。
 * 定义了生成二维码时使用的各种点阵形状和附加内容类型。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum CodePointStyle {
    /**
     * 迷你矩形样式，用于紧凑的二维码点阵。
     */
    MINI_RECT,

    /**
     * 标准矩形样式，最常见的二维码点阵形状。
     */
    RECTANGLE,

    /**
     * 圆形样式，将点阵渲染为圆形以美化外观。
     */
    CIRCLE,

    /**
     * 圆点样式，使用实心小圆点表示数据位。
     */
    ROUND_DOT,

    /**
     * 文本样式，允许在二维码中嵌入或关联文本信息。
     */
    TEXT,

    /**
     * 图像样式，支持在二维码中嵌入或关联图像信息。
     */
    IMAGE
}
