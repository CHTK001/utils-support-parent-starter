package com.chua.zxing.support.qr.draw.point;

import com.chua.common.support.spi.annotations.Spi;
import com.github.hui.quick.plugin.qrcode.entity.DotSize;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* circledrawpoint解析器，解析并处理圆形绘制点的问题。
* 继承自抽象drawpoint解析器，提供具体的绘制点的解析实现。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("CIRCLE")
public class CircleDrawPointResolver extends AbstractDrawPointResolver {
    /**
    * 创建 circledrawpoint解析器 实例
    * @param qrCodeConfig qr编码配置
    * @param g2 Graphics2D
    * @param bitMatrix 钻头matrixex
    * @param leftPadding int
    * @param leftPadding int
    * @param leftPadding int
    * @param g2 g2
    * @param bitMatrix 钻头matrix
    * @param leftPadding leftpadding
    * @param topPadding toppadding
    * @param infoSize 信息大小
    */
    public CircleDrawPointResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int leftPadding, int topPadding, int infoSize) {
        super(qrCodeConfig, g2, bitMatrix, leftPadding, topPadding, infoSize);
    }

    @Override
    void draw(Graphics2D g2d, int x, int y, int w, int h, BufferedImage img, String txt) {
        g2d.fill(new Ellipse2D.Float(x, y, w, h));
    }

    @Override
    boolean expand(DotSize dotSize) {
        return false;
    }
}
