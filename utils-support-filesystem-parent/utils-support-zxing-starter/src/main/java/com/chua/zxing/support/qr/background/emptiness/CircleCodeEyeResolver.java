package com.chua.zxing.support.qr.background.emptiness;

import java.awt.*;

import static com.chua.zxing.support.qr.background.EmptinessBackgroundResolver.size;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 码眼解析器
*
* @author CH
* @版本 1.0.0
* @since 4.0.0.42
 */
public class CircleCodeEyeResolver implements CodeEyeResolver{
    
    /** 单位宽度 */
    private int unitWidth = 54;

    /** 创建 circle编码eye解析器 实例 */
    public CircleCodeEyeResolver() {
    }

    /**
    * 创建 circle编码eye解析器 实例
    * @param unitWidth unitwidth
    */
    public CircleCodeEyeResolver(int unitWidth) {
        this.unitWidth = unitWidth;
    }

    @Override
    /** 解析 */
    public void resolve(Graphics2D g, Color df, Color lf, Color lb, int qrCodeWidth, int startX, int startY, int version) {
        // 画四个圆滑的大码眼
        Stroke stroke = g.getStroke();
        float thick = unitWidth * 1f;
        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // 左上角
        g.setColor(df);
        g.fillOval(startX + 2 * unitWidth, startY + 2 * unitWidth, 3 * unitWidth, 3 * unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.drawOval(startX + unitWidth / 2, startY + unitWidth / 2, 6 * unitWidth, 6 * unitWidth);
        g.setStroke(stroke);

        // 右上角
        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.fillOval(startX + (size(version) - 5) * unitWidth, startY + 2 * unitWidth, 3 * unitWidth, 3 * unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.drawOval(startX + unitWidth / 2 + (size(version) - 7) * unitWidth, startY + unitWidth / 2 , 6 * unitWidth, 6 * unitWidth);
        g.setStroke(stroke);

        // 左下角
        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.fillOval(startX + 2 * unitWidth, startY + (size(version) - 5) * unitWidth, 3 * unitWidth, 3 * unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.drawOval(startX + unitWidth / 2 , startY + unitWidth / 2 + (size(version) - 7) * unitWidth, 6 * unitWidth, 6 * unitWidth);
        g.setStroke(stroke);

        // 右下角
        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.fillOval(startX + (size(version) - 7) * unitWidth, startY + (size(version) - 7) * unitWidth, unitWidth, unitWidth);

        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(df);
        g.drawOval(startX + unitWidth / 2 + (size(version) - 9) * unitWidth, startY + unitWidth / 2 + (size(version) - 9) * unitWidth, 4 * unitWidth, 4 * unitWidth);
        g.setStroke(stroke);
    }
}
