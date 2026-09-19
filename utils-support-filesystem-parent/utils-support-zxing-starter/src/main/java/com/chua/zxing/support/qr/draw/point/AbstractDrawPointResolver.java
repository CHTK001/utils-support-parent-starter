package com.chua.zxing.support.qr.draw.point;

import com.github.hui.quick.plugin.qrcode.entity.DotSize;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.image.BufferedImage;

import static com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.*;

/**
 * drawpoint解析器接口用于定义绘制点的操作。
 * 规定了一个绘制图形的方法，需要由实现类具体实现绘制的逻辑。
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractDrawPointResolver implements DrawPointResolver{

    QrCodeOptions qrCodeConfig; // qr编码配置
    Graphics2D g2; // g2
    BitMatrixEx bitMatrix; // 钻头matrix
    int leftPadding; // leftpadding
    int topPadding; // toppadding
    int infoSize; // 信息大小

    /**
     * 创建 抽象drawpoint解析器 实例
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
    public AbstractDrawPointResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int leftPadding, int topPadding, int infoSize) {
        this.qrCodeConfig = qrCodeConfig;
        this.g2 = g2;
        this.bitMatrix = bitMatrix;
        this.leftPadding = leftPadding;
        this.topPadding = topPadding;
        this.infoSize = infoSize;
    }

    @Override
    /**
     * Draw
    */
    public void draw(int x, int y) {
        if (!qrCodeConfig.getDrawOptions().isEnableScale()) {
            // 用几何图形进行填充时，如果不支持多个像素点渲染一个几何图形时，直接返回即可
            draw(g2, leftPadding + x * infoSize, topPadding + y * infoSize, infoSize, infoSize, qrCodeConfig.getDrawOptions().getImage(1, 1), qrCodeConfig.getDrawOptions().getDrawQrTxt());
            return;
        }

        int maxRow = getMaxRow(bitMatrix.getByteMatrix(), x, y);
        int maxCol = getMaxCol(bitMatrix.getByteMatrix(), x, y);
        java.util.List<DotSize> availableSize = getAvailableSize(bitMatrix.getByteMatrix(), x, y, maxRow, maxCol);
        for (DotSize dotSize : availableSize) {
            if (!expand(dotSize)) {
                continue;
            }

            // 开始绘制，并将已经绘制过得地方置空
            draw(g2, leftPadding + x * infoSize, topPadding + y * infoSize, infoSize, infoSize, qrCodeConfig.getDrawOptions().getImage(1, 1), qrCodeConfig.getDrawOptions().getDrawQrTxt());
            for (int col = 0; col < dotSize.getCol(); col++) {
                for (int row = 0; row < dotSize.getRow(); row++) {
                    bitMatrix.getByteMatrix().set(x + col, y + row, 0);
                }
            }
            return;

        }
        draw(g2,leftPadding + x * infoSize, topPadding + y * infoSize, infoSize, infoSize, qrCodeConfig.getDrawOptions().getImage(1, 1), qrCodeConfig.getDrawOptions().getDrawQrTxt());
    }
    /**
     * 该抽象方法用于绘制图形。
     * @param g2d 用于绘制的Graphics2D对象
     * @param x 图形左上角的x坐标
     * @param y 图形左上角的y坐标
     * @param w 图形的宽度
     * @param h 图形的高度
     * @param img 要绘制的图像
     * @param txt 要绘制的文本
     */
    abstract void draw(Graphics2D g2d, int x, int y, int w, int h, BufferedImage img, String txt);

    /**
     * 该抽象方法用于判断是否扩展dot大小对象。
     * @param dotSize 待判断的dot大小对象
     * @return 如果能够扩展，则返回true；否则返回false
     */
    abstract boolean expand(DotSize dotSize);
}
