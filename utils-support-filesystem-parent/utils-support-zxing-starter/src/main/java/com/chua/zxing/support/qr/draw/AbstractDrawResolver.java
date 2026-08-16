package com.chua.zxing.support.qr.draw;

import com.chua.common.support.lang.qr.QrSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;

/**
 * 提供绘图解析功能的抽象类，作为DrawResolver接口的一个实现基础。
 * 该类为抽象类，不能直接实例化，但提供了基本的框架，以供具体实现时继承和扩展。
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractDrawResolver implements DrawResolver {


    protected final Graphics2D g2;
    protected final Color detectInnerColor;
    protected final Color detectOutColor;
    protected final QrCodeOptions qrCodeConfig;
    protected final BitMatrixEx bitMatrix;
    protected final QrCodeOptions.DrawOptions drawOptions;
    protected final Color preColor;
    protected final int infoSize;
    protected final QrCodeOptions.DrawStyle drawStyle;
    protected final QrSetting setting;
    protected final int matrixH;
    protected final int matrixW;
    protected final int detectCornerSize;
    protected final int topPadding;
    protected final int leftPadding;

    public AbstractDrawResolver(Graphics2D g2,
                                Color detectInnerColor,
                                Color detectOutColor,
                                QrCodeOptions qrCodeConfig,
                                BitMatrixEx bitMatrix,
                                QrCodeOptions.DrawOptions drawOptions,
                                QrSetting setting) {

        this.g2 = g2;
        this.detectInnerColor = detectInnerColor;
        this.detectOutColor = detectOutColor;
        this.qrCodeConfig = qrCodeConfig;
        this.bitMatrix = bitMatrix;
        this.drawOptions = drawOptions;
        this.infoSize = bitMatrix.getMultiple();
        // 绘制前置色
        this.preColor = drawOptions.getPreColor();
        this.leftPadding = bitMatrix.getLeftPadding();
        this.topPadding = bitMatrix.getTopPadding();
        // 探测图形的大小
        this.detectCornerSize = bitMatrix.getByteMatrix().get(0, 5) == 1 ? 7 : 5;
        this.matrixW = bitMatrix.getByteMatrix().getWidth();
        this.matrixH = bitMatrix.getByteMatrix().getHeight();
        this.drawStyle = drawOptions.getDrawStyle();
        this.setting = setting;
    }
}
