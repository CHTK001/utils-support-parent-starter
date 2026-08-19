package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;

import static com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.inOuterDetectCornerArea;

/**
 * DrawEyeResolver接口定义了绘制眼睛图形的方法。
 * @since 4.0.0.42
 */
public abstract class AbstractDrawEyeResolver implements DrawEyeResolver{
    QrCodeOptions qrCodeConfig;
    Graphics2D g2;
    BitMatrixEx bitMatrix;
    int matrixW;
    int matrixH;
    int leftPadding;
    int topPadding;
    int infoSize;
    int detectCornerSize;
    Color detectOutColor;
    Color detectInnerColor;
    CodeEyeSetting codeEyeSetting;

    public AbstractDrawEyeResolver(QrCodeOptions qrCodeConfig,
                                   Graphics2D g2,
                                   BitMatrixEx bitMatrix,
                                   int matrixW,
                                   int matrixH,
                                   int leftPadding,
                                   int topPadding,
                                   int infoSize,
                                   int detectCornerSize,
                                   Color detectOutColor,
                                   Color detectInnerColor,
                                   CodeEyeSetting codeEyeSetting) {
        this.qrCodeConfig = qrCodeConfig;
        this.g2 = g2;
        this.bitMatrix = bitMatrix;
        this.matrixW = matrixW;
        this.matrixH = matrixH;
        this.leftPadding = leftPadding;
        this.topPadding = topPadding;
        this.infoSize = infoSize;
        this.detectCornerSize = detectCornerSize;
        this.detectOutColor = detectOutColor;
        this.detectInnerColor = detectInnerColor;
        this.codeEyeSetting = codeEyeSetting;
    }

    @Override
    public void draw(int x, int y, QrCodeRenderHelper.DetectLocation detectLocation) {
        if (inOuterDetectCornerArea(x, y, matrixW, matrixH, detectCornerSize)) {
            // 外层的框
            g2.setColor(detectOutColor);
        } else {
            // 内层的框
            g2.setColor(detectInnerColor);
        }

        draw(g2, leftPadding + x * infoSize, topPadding + y * infoSize, infoSize, infoSize);
    }

    /**
     * 为抽象方法，绘制一个矩形区域。
     * @param g2 绘图上下文
     * @param x 矩形的左上角 x 坐标
     * @param y 矩形的左上角 y 坐标
     * @param w 矩形的宽度
     * @param h 矩形的高度
     * 不返回任何内容。
     */
    abstract void draw(Graphics2D g2, int x, int y, int w, int h);

}

