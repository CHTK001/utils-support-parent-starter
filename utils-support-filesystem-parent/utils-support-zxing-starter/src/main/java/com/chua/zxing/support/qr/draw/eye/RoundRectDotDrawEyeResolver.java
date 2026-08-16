package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 圆角外框 + 中心圆点 风格的码眼
 * 对应示例图（Telegram 风格）：外层为圆角矩形环，内为实心圆
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ROUND_RECTANGLE_DOT")
public class RoundRectDotDrawEyeResolver extends AbstractDrawEyeResolver {

    public RoundRectDotDrawEyeResolver(QrCodeOptions qrCodeConfig,
                                       Graphics2D g2,
                                       BitMatrixEx bitMatrix,
                                       int matrixW,
                                       int matrixH,
                                       int leftPadding,
                                       int topPadding,
                                       int infoSize,
                                       int detectCornerSize,
                                       Color detectOutColor,
                                       Color detectInnerColor,
                                       CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }

    @Override
    public void draw(int x, int y, QrCodeRenderHelper.DetectLocation detectLocation) {
        // 外层圆角方框（实心），再清空内层形成 ring
        double outerArc = infoSize * detectCornerSize / 2D;
        int sx = leftPadding + x * infoSize;
        int sy = topPadding + y * infoSize;
        int sw = infoSize * detectCornerSize;
        int sh = sw;

        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        RoundRectangle2D.Double outer = new RoundRectangle2D.Double(sx, sy, sw, sh, outerArc, outerArc);
        g2.fill(outer);

        // 清除中间留白
        int ring = infoSize;
        g2.setColor(Color.WHITE);
        RoundRectangle2D.Double innerBlank = new RoundRectangle2D.Double(sx + ring, sy + ring, sw - 2 * ring, sh - 2 * ring,
                (sw - 2 * ring) / 2D, (sh - 2 * ring) / 2D);
        g2.fill(innerBlank);

        // 中心圆点
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        int inset = infoSize * 2;
        Ellipse2D.Double dot = new Ellipse2D.Double(sx + inset, sy + inset, sw - 2 * inset, sh - 2 * inset);
        g2.fill(dot);

        // 标记已绘制区域，避免重复渲染
        for (int addX = 0; addX < detectCornerSize; addX++) {
            for (int addY = 0; addY < detectCornerSize; addY++) {
                bitMatrix.getByteMatrix().set(x + addX, y + addY, 0);
            }
        }
    }

    @Override
    public void finish() {
        // 无需额外操作
    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {
        // 不使用逐像素绘制，逻辑在 draw(int,int,DetectLocation) 中实现
    }
}

