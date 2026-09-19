package com.chua.common.support.lang.qr;

import lombok.Data;

/**
 * 二维码码点配置类。
 * 用于定义二维码生成时码点的样式、颜色、缩放、图像及文本等属性。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class CodePointSetting {
    /**
     * 码点样式枚举，控制码点的形状或风格。
     */
    private CodePointStyle codePoint;

    /**
     * 码点的颜色值，支持十六进制字符串表示。
     */
    private String codePointColor;

    /**
     * 是否启用缩放绘制功能。
     */
    private boolean drawEnableScale;

    /**
     * 自定义码点图像的路径或资源标识。
     */
    private String codePointImage;

    /**
     * 码点关联的文本描述或标识。
     */
    private String codePointText;
}
