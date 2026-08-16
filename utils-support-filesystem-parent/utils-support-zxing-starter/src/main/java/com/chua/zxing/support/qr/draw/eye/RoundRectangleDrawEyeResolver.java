package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * RoundRectangleDrawEyeResolver类，继承自AbstractDrawEyeResolver。该类为绘制眼睛的一种具体实现方式。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ROUND_RECTANGLE")
public class RoundRectangleDrawEyeResolver extends AbstractDrawEyeResolver {

    public RoundRectangleDrawEyeResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int matrixW, int matrixH, int leftPadding, int topPadding, int infoSize, int detectCornerSize, Color detectOutColor, Color detectInnerColor, CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }


    @Override
    public void draw(int x, int y, QrCodeRenderHelper.DetectLocation detectLocation) {
        RoundRectangle2D.Double shape = new RoundRectangle2D.Double(leftPadding + x * infoSize,
                topPadding + y * infoSize,
                infoSize * detectCornerSize,
                infoSize * detectCornerSize,
                infoSize * detectCornerSize / 2D,
                infoSize * detectCornerSize / 2D);
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        g2.fill(shape);
//        // 清除中心的形状，以模拟二维码码眼的效果
        g2.setColor(Color.WHITE);
        g2.fill(new RoundRectangle2D.Double(leftPadding + x * infoSize + infoSize,
                topPadding + y * infoSize + infoSize,
                infoSize * detectCornerSize - 2 * infoSize,
                infoSize * detectCornerSize - 2 * infoSize,
                (infoSize * detectCornerSize - 2 * infoSize) / 2D,
                (infoSize * detectCornerSize - 2 * infoSize) / 2D));
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        g2.fill(new RoundRectangle2D.Double(leftPadding + x * infoSize + infoSize * 2,
                topPadding + y * infoSize + infoSize * 2,
                infoSize * detectCornerSize - 4 * infoSize,
                infoSize * detectCornerSize - 4 * infoSize,
                (infoSize * detectCornerSize - 4 * infoSize) / 2D,
                (infoSize * detectCornerSize - 4 * infoSize) / 2D));
        // 图片直接渲染完毕之后，将其他探测图形的点设置为0，表示不需要再次渲染
        for (int addX = 0; addX < detectCornerSize; addX++) {
            for (int addY = 0; addY < detectCornerSize; addY++) {
                bitMatrix.getByteMatrix().set(x + addX, y + addY, 0);
            }
        }
    }

    @Override
    public void finish() {
        //绘制LT
    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {
    }

}
