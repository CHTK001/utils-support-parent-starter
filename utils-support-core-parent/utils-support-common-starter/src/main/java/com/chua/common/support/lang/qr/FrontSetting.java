package com.chua.common.support.lang.qr;

import lombok.Data;

/**
* 二维码前端设置类。
* 用于配置二维码生成时的视觉样式和布局参数。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class FrontSetting {

    /**
    * 填充颜色，通常为十六进制颜色字符串（如 "#000000"）。
    * 用于设置二维码前景色或背景色的填充值。
    */
    private String ftFillColor;

    /**
    * 起始 X 坐标，表示二维码在画布上的水平起始位置。
    */
    private int ftStartX;

    /**
    * 起始 Y 坐标，表示二维码在画布上的垂直起始位置。
    */
    private int ftStartY;

    /**
    * 自定义图像对象，可用于将图片嵌入二维码中心或作为水印。
    * 具体类型取决于实际使用场景（如 BufferedImage、String 路径等）。
    */
    private Object ftImg;
}
