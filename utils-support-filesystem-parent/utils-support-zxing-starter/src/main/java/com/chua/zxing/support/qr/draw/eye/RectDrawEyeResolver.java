package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * rectdraweye解析器类，继承自抽象draweye解析器。该类为绘制眼睛的一种具体实现方式。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("RECTANGLE")
public class RectDrawEyeResolver extends AbstractDrawEyeResolver{


    /**
     * 创建 rectdraweye解析器 实例
     * @param qrCodeConfig qr编码配置
     * @param g2 Graphics2D
     * @param bitMatrix 钻头matrixex
     * @param matrixW int
     * @param matrixW int
     * @param matrixW int
     * @param matrixW int
     * @param matrixW int
     * @param matrixW int
     * @param detectOutColor Color
     * @param detectOutColor Color
     * @param codeEyeSetting 编码eyesetting
     * @param g2 g2
     * @param bitMatrix 钻头matrix
     * @param matrixW matrixw
     * @param matrixH matrixh
     * @param leftPadding leftpadding
     * @param topPadding toppadding
     * @param infoSize 信息大小
     * @param detectCornerSize detectcorner大小
     * @param detectOutColor detect出color
     * @param detectInnerColor detect内部color
     * @param codeEyeSetting 编码eyesetting
     */
    public RectDrawEyeResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int matrixW, int matrixH, int leftPadding, int topPadding, int infoSize, int detectCornerSize, Color detectOutColor, Color detectInnerColor, CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }

    @Override
    /**
     * 饰面
    */
    public void finish() {

    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {
        g2.fillRect(x, y, w, h);
    }
}
