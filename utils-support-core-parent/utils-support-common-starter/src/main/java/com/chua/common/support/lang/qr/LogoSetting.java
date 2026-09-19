package com.chua.common.support.lang.qr;

import lombok.Data;

/**
 * 二维码Logo设置类。
 * <p>
 * 用于配置二维码中嵌入的Logo图片的相关属性，包括图片本身、透明度、形状样式及边框颜色。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class LogoSetting {

    /**
     * Logo图片对象。
     * 支持多种图像格式，如 BufferedImage、File、InputStream 或 String (路径/URL)。
     */
    private Object logoImage;

    /**
     * Logo图片的透明度值。
     * 取值范围：0.0f (完全透明) 到 1.0f (完全不透明)。
     * 若为 null，则使用默认不透明处理。
     */
    private Float logoAlpha;

    /**
     * Logo的形状样式。
     * 控制Logo在二维码中的裁剪形状，如圆形、圆角矩形等。
     */
    private LogoStyle logoShape;

    /**
     * Logo边框的颜色。
     * 以十六进制字符串表示，例如 "#FF0000" 或 "red"。
     * 若为 null 或未设置，则不显示边框。
     */
    private String logoBorderColor;
}
