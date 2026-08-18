package com.chua.zxing.support.qr.background.emptiness;

import java.awt.*;

import static com.chua.zxing.support.qr.background.EmptinessBackgroundResolver.size;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 码眼解析器
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
public class RoundRectangleCodeEyeResolver implements CodeEyeResolver{

    /** 单位宽度 */
    private int unitWidth = 54;

    public RoundRectangleCodeEyeResolver() {
    }

    public RoundRectangleCodeEyeResolver(int unitWidth) {
        this.unitWidth = unitWidth;
    }

    @Override
    public void resolve(Graphics2D g, Color df, Color lf, Color lb, int qrCodeWidth, int startX, int startY, int version) {
        // 添加一层遮罩
        g.setColor(lb);
        // g.fillRect(startXTemp, startYTemp, QRCodeWidthTemp,
        // QRCodeWidthTemp);
        g.fillRoundRect(startX - unitWidth, startY - unitWidth, qrCodeWidth, qrCodeWidth, (int) (unitWidth * 4.5), (int) (unitWidth * 4.5));

        // 画四个圆滑的大码眼
        Stroke stroke = g.getStroke();
        float thick = unitWidth * 1f;
        // 左上角
        g.setColor(lf);
        g.fillRoundRect(startX + unitWidth, startY + unitWidth, 5 * unitWidth, 5 * unitWidth, unitWidth, unitWidth);
        g.setColor(df);
        g.fillRoundRect(startX + 2 * unitWidth, startY + 2 * unitWidth, 3 * unitWidth, 3 * unitWidth, unitWidth, unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
        g.setColor(lf);
        g.drawRoundRect(startX + unitWidth / 2 - unitWidth, startY + unitWidth / 2 - unitWidth, 8 * unitWidth, 8 * unitWidth, (int) (unitWidth * 3.8), (int) (unitWidth * 3.8));
        g.setColor(df);
        g.drawRoundRect(startX + unitWidth / 2, startY + unitWidth / 2, 6 * unitWidth, 6 * unitWidth, unitWidth * 2, unitWidth * 2);
        g.setStroke(stroke);

        // 右上角
        g.setColor(lf);
        g.fillRoundRect(startX + (size(version) - 6) * unitWidth, startY + unitWidth, 5 * unitWidth, 5 * unitWidth, unitWidth, unitWidth);
        g.setColor(df);
        g.fillRoundRect(startX + (size(version) - 5) * unitWidth, startY + 2 * unitWidth, 3 * unitWidth, 3 * unitWidth, unitWidth, unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
        g.setColor(lf);
        g.drawRoundRect(startX + unitWidth / 2 + (size(version) - 8) * unitWidth, startY + unitWidth / 2 - unitWidth, 8 * unitWidth, 8 * unitWidth, (int) (unitWidth * 3.8),
                (int) (unitWidth * 3.8));
        g.setColor(df);
        g.drawRoundRect(startX + unitWidth / 2 + (size(version) - 7) * unitWidth, startY + unitWidth / 2, 6 * unitWidth, 6 * unitWidth, unitWidth * 2, unitWidth * 2);
        g.setStroke(stroke);

        // 左下角
        g.setColor(lf);
        g.fillRoundRect(startX + unitWidth, startY + (size(version) - 6) * unitWidth, 5 * unitWidth, 5 * unitWidth, unitWidth, unitWidth);
        g.setColor(df);
        g.fillRoundRect(startX + 2 * unitWidth, startY + (size(version) - 5) * unitWidth, 3 * unitWidth, 3 * unitWidth, unitWidth, unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
        g.setColor(lf);
        g.drawRoundRect(startX + unitWidth / 2 - unitWidth, startY + unitWidth / 2 + (size(version) - 8) * unitWidth, 8 * unitWidth, 8 * unitWidth, (int) (unitWidth * 3.8),
                (int) (unitWidth * 3.8));
        g.setColor(df);
        g.drawRoundRect(startX + unitWidth / 2, startY + unitWidth / 2 + (size(version) - 7) * unitWidth, 6 * unitWidth, 6 * unitWidth, unitWidth * 2, unitWidth * 2);
        g.setStroke(stroke);

        // 右下角
        g.setColor(lf);
        g.fillRoundRect(startX + (size(version) - 8) * unitWidth, startY + (size(version) - 8) * unitWidth, 3 * unitWidth, 3 * unitWidth, unitWidth, unitWidth);
        g.setColor(df);
        g.fillRoundRect(startX + (size(version) - 7) * unitWidth, startY + (size(version) - 7) * unitWidth, unitWidth, unitWidth, unitWidth, unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
        g.setColor(df);
        g.drawRoundRect(startX + unitWidth / 2 + (size(version) - 9) * unitWidth, startY + unitWidth / 2 + (size(version) - 9) * unitWidth, 4 * unitWidth, 4 * unitWidth,
                unitWidth, unitWidth);
        g.setStroke(stroke);
    }
}
