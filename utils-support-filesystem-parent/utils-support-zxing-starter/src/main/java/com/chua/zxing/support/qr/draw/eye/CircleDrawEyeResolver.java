package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * RectDrawEyeResolver类，继承自AbstractDrawEyeResolver。该类为绘制眼睛的一种具体实现方式。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CircleDrawEyeResolver extends AbstractDrawEyeResolver {

    /**
     * 创建 CircleDrawEyeResolver 实例
     * @param qrCodeConfig qrCodeConfig
     * @param Graphics2D Graphics2D
     * @param BitMatrixEx BitMatrixEx
     * @param int int
     * @param int int
     * @param int int
     * @param int int
     * @param int int
     * @param int int
     * @param Color Color
     * @param Color Color
     * @param CodeEyeSetting CodeEyeSetting
     */
    public CircleDrawEyeResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int matrixW, int matrixH, int leftPadding, int topPadding, int infoSize, int detectCornerSize, Color detectOutColor, Color detectInnerColor, CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }


    @Override
    /** Draw */
    public void draw(int x, int y, QrCodeRenderHelper.DetectLocation detectLocation) {
        Ellipse2D.Double shape = new Ellipse2D.Double(leftPadding + x * infoSize, topPadding + y * infoSize,infoSize * detectCornerSize, infoSize * detectCornerSize);
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        g2.fill(shape);
//        // 清除中心的形状，以模拟二维码码眼的效果
        g2.setColor(Color.WHITE);
        g2.fill(new Ellipse2D.Double(leftPadding + x * infoSize + infoSize, topPadding + y * infoSize + infoSize,infoSize * detectCornerSize - 2 * infoSize, infoSize * detectCornerSize - 2 * infoSize));
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        g2.fill(new Ellipse2D.Double(leftPadding + x * infoSize + infoSize * 2, topPadding + y * infoSize + infoSize * 2,infoSize * detectCornerSize - 4 * infoSize, infoSize * detectCornerSize - 4 * infoSize));
        // 图片直接渲染完毕之后，将其他探测图形的点设置为0，表示不需要再次渲染
        for (int addX = 0; addX < detectCornerSize; addX++) {
            for (int addY = 0; addY < detectCornerSize; addY++) {
                bitMatrix.getByteMatrix().set(x + addX, y + addY, 0);
            }
        }
    }

    @Override
    /** Finish */
    public void finish() {
    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {
    }

}
