package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * RectDrawEyeResolver类，继承自AbstractDrawEyeResolver。该类为绘制眼睛的一种具体实现方式。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("RECTANGLE")
public class RectDrawEyeResolver extends AbstractDrawEyeResolver{


    public RectDrawEyeResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int matrixW, int matrixH, int leftPadding, int topPadding, int infoSize, int detectCornerSize, Color detectOutColor, Color detectInnerColor, CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }

    @Override
    public void finish() {

    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {
        g2.fillRect(x, y, w, h);
    }
}
