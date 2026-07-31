package com.chua.zxing.support.qr.draw.point;

import com.chua.common.support.spi.annotations.Spi;
import com.github.hui.quick.plugin.qrcode.entity.DotSize;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;
import com.google.zxing.qrcode.encoder.ByteMatrix;

import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 流体风格码点：相邻的点会以“胶囊”连接，形成融合效果
 *
 * @author CH
 * @since 2025/08/09
 */
@Spi("ROUND_DOT")
public class RoundedCircleDrawPointResolver extends AbstractDrawPointResolver {
    public RoundedCircleDrawPointResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix,
            int leftPadding, int topPadding, int infoSize) {
        super(qrCodeConfig, g2, bitMatrix, leftPadding, topPadding, infoSize);
    }

    /**
     * 自定义覆盖：按模块坐标绘制，并处理与右/下（以及右下对角）邻居的流体连接
     */
    @Override
    public void draw(int mx, int my) {
        int px = leftPadding + mx * infoSize;
        int py = topPadding + my * infoSize;

        ByteMatrix m = bitMatrix.getByteMatrix();
        int w = m.getWidth();
        int h = m.getHeight();

        // 以“中心圆 + 直边连接矩形”的方式形成流体融合
                
        float thickness = 5.00f;
        float s = infoSize * thickness;
        float r = s / 2f;
        float cx = px + infoSize / 2f;
        float cy = py + infoSize / 2f;

        boolean left = mx > 0 && m.get(mx - 1, my) == 1;
        boolean right = mx + 1 < w && m.get(mx + 1, my) == 1;
        boolean up = my > 0 && m.get(mx, my - 1) == 1;
        boolean down = my + 1 < h && m.get(mx, my + 1) == 1;
        int deg = (left ? 1 : 0) + (right ? 1 : 0) + (up ? 1 : 0) + (down ? 1 : 0);

        // 抗锯齿
        Object oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 基元：中心圆
        g2.fill(new Ellipse2D.Float(cx - r, cy - r, s, s));

        // 直边连接：只桥接到相邻单元中心（半格桥接），并稍微重叠以消除接缝
        float eps = Math.max(1f, infoSize * 0.05f);
        if (left) {
            g2.fill(new Rectangle2D.Float(cx - infoSize / 2f - eps, cy - r, infoSize / 2f + eps, s));
        }
        if (right) {
            g2.fill(new Rectangle2D.Float(cx, cy - r, infoSize / 2f + eps, s));
        }
        if (up) {
            g2.fill(new Rectangle2D.Float(cx - r, cy - infoSize / 2f - eps, s, infoSize / 2f + eps));
        }
        if (down) {
            g2.fill(new Rectangle2D.Float(cx - r, cy, s, infoSize / 2f + eps));
        }

        // 孤立点：仅中心圆
        if (deg == 0) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
            return;
        }

        // 对外露边缘补半圆端帽（只在缺失方向补，避免串珠）
        if (!left) {
            g2.fill(new Ellipse2D.Float(cx - s, cy - r, s, s));
        }
        if (!right) {
            g2.fill(new Ellipse2D.Float(cx, cy - r, s, s));
        }
        if (!up) {
            g2.fill(new Ellipse2D.Float(cx - r, cy - s, s, s));
        }
        if (!down) {
            g2.fill(new Ellipse2D.Float(cx - r, cy, s, s));
        }
        // L 形外角增强（当对角为空时，加 1/4 圆使外轮廓更顺滑）
        boolean ne = (mx + 1 < w && my > 0) && m.get(mx + 1, my - 1) == 1;
        boolean nw = (mx > 0 && my > 0) && m.get(mx - 1, my - 1) == 1;
        boolean se = (mx + 1 < w && my + 1 < h) && m.get(mx + 1, my + 1) == 1;
        boolean sw = (mx > 0 && my + 1 < h) && m.get(mx - 1, my + 1) == 1;

        if (up && right && !ne) {
            g2.fill(new Ellipse2D.Float(cx, cy - s, s, s));
        }
        if (up && left && !nw) {
            g2.fill(new Ellipse2D.Float(cx - s, cy - s, s, s));
        }
        if (down && right && !se) {
            g2.fill(new Ellipse2D.Float(cx, cy, s, s));
        }
        if (down && left && !sw) {
            g2.fill(new Ellipse2D.Float(cx - s, cy, s, s));
        }
        // 还原抗锯齿设置
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
    }

    // 以下方法仅为保持抽象基类签名，不在该风格中使用
    @Override
    void draw(Graphics2D g2d, int x, int y, int w, int h, BufferedImage img, String txt) {
        // no-op：全部在 finish() 中统一重绘
    }

    @Override
    public void finish() {
        // 二阶段：按连通关系合并并重绘整图，得到顺滑外轮廓
        ByteMatrix m = bitMatrix.getByteMatrix();
        int w = m.getWidth();
        int h = m.getHeight();

        // 线宽/半径（略小于整格，避免溢出到安静区，可根据需求调参）
        float thickness = 0.95f;
        float s = infoSize * thickness;
        float r = s / 2f;

        Object oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Area all = new Area();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (m.get(x, y) != 1) {
                    continue;
                }
                float cx = leftPadding + x * infoSize + infoSize / 2f;
                float cy = topPadding + y * infoSize + infoSize / 2f;

                // 中心圆并入
                all.add(new Area(new Ellipse2D.Float(cx - r, cy - r, s, s)));

                // 仅向 右/下 方向桥接，避免重复
                if (x + 1 < w && m.get(x + 1, y) == 1) {
                    float rx = cx;
                    all.add(new Area(new Rectangle2D.Float(rx, cy - r, infoSize, s)));
                }
                if (y + 1 < h && m.get(x, y + 1) == 1) {
                    float ry = cy;
                    all.add(new Area(new Rectangle2D.Float(cx - r, ry, s, infoSize)));
                }
            }
        }

        g2.fill(all);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
    }

    @Override
    boolean expand(DotSize dotSize) {
        return false;
    }
}
