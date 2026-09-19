package com.chua.zxing.support.qr.background.emptiness;

import java.awt.*;

import static com.chua.zxing.support.qr.background.EmptinessBackgroundResolver.size;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 码眼解析器
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RectangleCodeEyeResolver implements CodeEyeResolver{

    /**
     * 单位宽度
    */
    private int unitWidth = 54;

    /**
     * 创建 rectangle编码eye解析器 实例
    */
    public RectangleCodeEyeResolver() {
    }

    /**
     * 创建 rectangle编码eye解析器 实例
     * @param unitWidth unitwidth
     */
    public RectangleCodeEyeResolver(int unitWidth) {
        this.unitWidth = unitWidth;
    }

    @Override
    /**
     * 解析
    */
    public void resolve(Graphics2D g, Color df, Color lf, Color lb, int qrCodeWidth, int startX, int startY, int version) {
        // 添加一层遮罩
        g.setColor(lb);
        // g.fillRect(startXTemp, startYTemp, QRCodeWidthTemp,
        // QRCodeWidthTemp);
        g.fillRect(startX - unitWidth, startY - unitWidth, qrCodeWidth, qrCodeWidth);

        // 画四个圆滑的大码眼
        Stroke stroke = g.getStroke();
        float thick = unitWidth * 1f;
        // 左上角
        g.setColor(lf);
        g.fillRect(startX + unitWidth, startY + unitWidth, 5 * unitWidth, 5 * unitWidth);
        g.setColor(df);
        g.fillRect(startX + 2 * unitWidth, startY + 2 * unitWidth, 3 * unitWidth, 3 * unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(lf);
        g.drawRect(startX + unitWidth / 2 - unitWidth, startY + unitWidth / 2 - unitWidth, 8 * unitWidth, 8 * unitWidth);
        g.setColor(df);
        g.drawRect(startX + unitWidth / 2, startY + unitWidth / 2, 6 * unitWidth, 6 * unitWidth);
        g.setStroke(stroke);

        // 右上角
        g.setColor(lf);
        g.fillRect(startX + (size(version) - 6) * unitWidth, startY + unitWidth, 5 * unitWidth, 5 * unitWidth);
        g.setColor(df);
        g.fillRect(startX + (size(version) - 5) * unitWidth, startY + 2 * unitWidth, 3 * unitWidth, 3 * unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(lf);
        g.drawRect(startX + unitWidth / 2 + (size(version) - 8) * unitWidth, startY + unitWidth / 2 - unitWidth, 8 * unitWidth, 8 * unitWidth);
        g.setColor(df);
        g.drawRect(startX + unitWidth / 2 + (size(version) - 7) * unitWidth, startY + unitWidth / 2 , 6 * unitWidth, 6 * unitWidth);
        g.setStroke(stroke);

        // 左下角
        g.setColor(lf);
        g.fillRect(startX + unitWidth, startY + (size(version) - 6) * unitWidth, 5 * unitWidth, 5 * unitWidth);
        g.setColor(df);
        g.fillRect(startX + 2 * unitWidth, startY + (size(version) - 5) * unitWidth, 3 * unitWidth, 3 * unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(lf);
        g.drawRect(startX + unitWidth / 2 - unitWidth, startY + unitWidth / 2 + (size(version) - 8) * unitWidth, 8 * unitWidth, 8 * unitWidth);
        g.setColor(df);
        g.drawRect(startX + unitWidth / 2 , startY + unitWidth / 2 + (size(version) - 7) * unitWidth, 6 * unitWidth, 6 * unitWidth);
        g.setStroke(stroke);

        // 右下角
        g.setColor(lf);
        g.fillRect(startX + (size(version) - 8) * unitWidth, startY + (size(version) - 8) * unitWidth, 3 * unitWidth, 3 * unitWidth);
        g.setColor(df);
        g.fillRect(startX + (size(version) - 7) * unitWidth, startY + (size(version) - 7) * unitWidth, unitWidth, unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.drawRect(startX + unitWidth / 2 + (size(version) - 9) * unitWidth, startY + unitWidth / 2 + (size(version) - 9) * unitWidth, 4 * unitWidth, 4 * unitWidth);
        g.setStroke(stroke);
    }
}
