package com.chua.common.support.lang.qr;

import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

/**
 * 二维码背景设置类。
 *
 * @author CH
 */
@NullUnmarked
@Data
public class BackgroundSetting {
    /**
     * 背景图片，支持多种类型。
     */
    private Object backgroundImage;

    /**
     * 背景透明度，取值范围0.0到1.0。
     */
    private float bgAlpha;

    /**
     * 背景图片的样式，如cover、contain等。
     */
    private String bgImgStyle;

    /**
     * 渐变起始颜色，使用十六进制或颜色名称表示。
     */
    private String fromColor;

    /**
     * 渐变结束颜色，使用十六进制或颜色名称表示。
     */
    private String toColor;
}
