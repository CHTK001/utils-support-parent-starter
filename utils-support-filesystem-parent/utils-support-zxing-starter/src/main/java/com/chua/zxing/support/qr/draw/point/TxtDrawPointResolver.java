package com.chua.zxing.support.qr.draw.point;

import com.chua.common.support.spi.annotations.Spi;
import com.github.hui.quick.plugin.qrcode.constants.QuickQrUtil;
import com.github.hui.quick.plugin.qrcode.entity.DotSize;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * TxtDrawPointResolver，解析并处理文本绘制点的问题。
 * 继承自AbstractDrawPointResolver，提供具体的绘制点的解析实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("TEXT")
public class TxtDrawPointResolver extends AbstractDrawPointResolver {
    /**
     * 创建 TxtDrawPointResolver 实例
     * @param qrCodeConfig qrCodeConfig
     * @param Graphics2D Graphics2D
     * @param BitMatrixEx BitMatrixEx
     * @param int int
     * @param int int
     * @param int int
     */
    public TxtDrawPointResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int leftPadding, int topPadding, int infoSize) {
        super(qrCodeConfig, g2, bitMatrix, leftPadding, topPadding, infoSize);
    }

    @Override
    void draw(Graphics2D g2d, int x, int y, int w, int h, BufferedImage img, String txt) {
        Font oldFont = g2d.getFont();
        if (oldFont.getSize() != w) {
            Font newFont = QuickQrUtil.font(oldFont.getName(), oldFont.getStyle(), w);
            g2d.setFont(newFont);
        }
        g2d.drawString(txt, x, y + w);
        g2d.setFont(oldFont);
    }

    @Override
    boolean expand(DotSize dotSize) {
        return  dotSize.getRow() == dotSize.getCol();
    }
}
