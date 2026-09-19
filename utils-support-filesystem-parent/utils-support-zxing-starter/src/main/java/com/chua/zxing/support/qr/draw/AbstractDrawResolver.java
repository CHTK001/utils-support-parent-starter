package com.chua.zxing.support.qr.draw;

import com.chua.common.support.lang.qr.QrSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;

/**
 * 提供绘图解析功能的抽象类，作为draw解析器接口的一个实现基础。
 * 该类为抽象类，不能直接实例化，但提供了基本的框架，以供具体实现时继承和扩展。
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractDrawResolver implements DrawResolver {


    /** G2 */
    protected final Graphics2D g2;
    /** Detectinner颜色 */
    protected final Color detectInnerColor;
    /** detect出颜色 */
    protected final Color detectOutColor;
    /** QR代码配置 */
    protected final QrCodeOptions qrCodeConfig;
    /** 位matrix */
    protected final BitMatrixEx bitMatrix;
    /** Drawoptions */
    protected final QrCodeOptions.DrawOptions drawOptions;
    /** PRE颜色 */
    protected final Color preColor;
    /** 信息尺寸 */
    protected final int infoSize;
    /** Drawstyle */
    protected final QrCodeOptions.DrawStyle drawStyle;
    /** 设置 */
    protected final QrSetting setting;
    /** matrixh */
    protected final int matrixH;
    /** matrixw */
    protected final int matrixW;
    /** Detectcorner尺寸 */
    protected final int detectCornerSize;
    /** 顶部padding */
    protected final int topPadding;
    /** 左侧padding */
    protected final int leftPadding;

    /**
     * 创建 抽象draw解析器 实例
     * @param g2 g2
     * @param detectInnerColor detect内部color
     * @param detectOutColor detect出color
     * @param qrCodeConfig qr编码配置
     * @param bitMatrix 钻头matrix
     * @param drawOptions draw期权
     * @param setting setting
     */
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
