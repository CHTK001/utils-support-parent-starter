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
 * MiniRectDrawPointResolver类，解析并处理mini矩形绘制点的问题。
 * 继承自AbstractDrawPointResolver，提供具体的绘制点的解析实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("MINI_RECT")
public class MiniRectDrawPointResolver extends AbstractDrawPointResolver {
    /**
     * 创建 MiniRectDrawPointResolver 实例
     * @param qrCodeConfig qrCodeConfig
     * @param Graphics2D Graphics2D
     * @param BitMatrixEx BitMatrixEx
     * @param int int
     * @param int int
     * @param int int
     */
    public MiniRectDrawPointResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int leftPadding, int topPadding, int infoSize) {
        super(qrCodeConfig, g2, bitMatrix, leftPadding, topPadding, infoSize);
    }

    @Override
    void draw(Graphics2D g2d, int x, int y, int w, int h, BufferedImage img, String txt) {
        int offsetX = w / 5, offsetY = h / 5;
        int width = w - offsetX * 2, height = h - offsetY * 2;
        g2d.fillRect(x + offsetX, y + offsetY, width, height);
    }

    @Override
    boolean expand(DotSize dotSize) {
        return dotSize.getRow() == dotSize.getCol();
    }
}
