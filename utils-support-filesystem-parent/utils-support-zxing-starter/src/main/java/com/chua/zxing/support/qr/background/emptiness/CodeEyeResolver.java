package com.chua.zxing.support.qr.background.emptiness;

import java.awt.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 码眼解析器
 *
 * @author CH
 * @版本 1.0.0
 * @since 4.0.0.42
 */
public interface CodeEyeResolver {
    /**
     * 解析并绘制二维码。
     *
     * @param g 使用的Graphics2D对象，绘制二维码
     * @param df 默认颜色，绘制二维码的默认模块
     * @param lf 边框左侧的颜色，突出显示二维码的左侧边框
     * @param lb 边框底部的颜色，突出显示二维码的底部边框
     * @param qrCodeWidth 二维码的宽度
     * @param startX 绘制二维码的起始x坐标
     * @param startY 绘制二维码的起始y坐标
     * @param version 二维码的版本号
     */
    void resolve(Graphics2D g, Color df, Color lf, Color lb, int qrCodeWidth, int startX, int startY, int version);
}
