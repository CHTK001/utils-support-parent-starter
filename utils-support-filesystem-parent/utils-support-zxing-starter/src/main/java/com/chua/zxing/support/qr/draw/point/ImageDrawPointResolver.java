package com.chua.zxing.support.qr.draw.point;

import com.chua.common.support.spi.annotations.Spi;
import com.github.hui.quick.plugin.qrcode.entity.DotSize;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
   * 镜像drawpoint解析器，解析并处理图片绘制点的问题。
   * 继承自抽象drawpoint解析器，提供具体的绘制点的解析实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("image")
public class ImageDrawPointResolver extends AbstractDrawPointResolver {
    /**
      * 创建 镜像drawpoint解析器 实例
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
    public ImageDrawPointResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int leftPadding, int topPadding, int infoSize) {
        super(qrCodeConfig, g2, bitMatrix, leftPadding, topPadding, infoSize);
    }

    @Override
    void draw(Graphics2D g2d, int x, int y, int w, int h, BufferedImage img, String txt) {
        g2d.drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), x, y, null);

    }

    @Override
    boolean expand(DotSize dotSize) {
        return  true;
    }
}
