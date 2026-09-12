package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.geom.Path2D;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 空气bubbledraweye解析器类，继承自抽象draweye解析器。该类为绘制眼睛的一种具体实现方式。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("AIR_BUBBLE")
public class AirBubbleDrawEyeResolver extends AbstractDrawEyeResolver {

    /**
    * 创建 空气bubbledraweye解析器 实例
    * @param qrCodeConfig qr编码配置
    * @param g2 Graphics2D
    * @param bitMatrix 钻头matrixex
    * @param matrixW int
    * @param matrixW int
    * @param matrixW int
    * @param matrixW int
    * @param matrixW int
    * @param matrixW int
    * @param detectOutColor Color
    * @param detectOutColor Color
    * @param codeEyeSetting 编码eyesetting
    * @param g2 g2
    * @param bitMatrix 钻头matrix
    * @param matrixW matrixw
    * @param matrixH matrixh
    * @param leftPadding leftpadding
    * @param topPadding toppadding
    * @param infoSize 信息大小
    * @param detectCornerSize detectcorner大小
    * @param detectOutColor detect出color
    * @param detectInnerColor detect内部color
    * @param codeEyeSetting 编码eyesetting
     */
    public AirBubbleDrawEyeResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int matrixW, int matrixH, int leftPadding, int topPadding, int infoSize, int detectCornerSize, Color detectOutColor, Color detectInnerColor, CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }


    @Override
    /** Draw */
    public void draw(int x, int y, QrCodeRenderHelper.DetectLocation detectLocation) {
        int x1 =  leftPadding + x * infoSize;
        int y1 =  topPadding + y * infoSize;
        int h1 = infoSize * detectCornerSize;
        int w1 = infoSize * detectCornerSize;
        double arcw1 = infoSize * detectCornerSize / 2D;
        double arch1 = infoSize * detectCornerSize / 2D;
        BasicStroke stroke = new BasicStroke(infoSize);
        g2.setStroke(stroke);

        Path2D path = new Path2D.Double();
        path.moveTo(x1, y1 + h1 - arch1);
        path.lineTo(x1, y1);
        path.lineTo(x1 + w1 - arcw1, y1);
        path.quadTo(x1 + w1, y1, x1 + w1, y1 + arch1);
        path.lineTo(x1 + w1, y1 + h1);
        path.lineTo(x1 + arcw1, y1 + h1);
        path.quadTo(x1, y1 + h1, x1, y1 + h1 - arch1);
        path.closePath();
        
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
        g2.draw(path);
        int x2 =  leftPadding + x * infoSize + infoSize * 2;
        int y2 =  topPadding + y * infoSize + infoSize * 2;
        int h2 = infoSize * detectCornerSize - 4 * infoSize;
        int w2 = infoSize * detectCornerSize - 4 * infoSize;
        double arcw2 = h2 / 2D;
        double arch2 = w2 / 2D;
        g2.setColor(Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));

        Path2D path2 = new Path2D.Double();
        path2.moveTo(x2, y2 + h2 - arch2);
        path2.lineTo(x2, y2);
        path2.lineTo(x2 + w2 - arcw2, y2);
        path2.quadTo(x2 + w2, y2, x2 + w2, y2 + arch2);
        path2.lineTo(x2 + w2, y2 + h2);
        path2.lineTo(x2 + arcw2, y2 + h2);
        path2.quadTo(x2, y2 + h2, x2, y2 + h2 - arch2);
        path2.setWindingRule(Path2D.WIND_EVEN_ODD);
        path2.closePath();
        g2.fill(path2);
        // 图片直接渲染完毕之后，将其他探测图形的点设置为0，表示不需要再次渲染
        for (int addX = 0; addX < detectCornerSize; addX++) {
            for (int addY = 0; addY < detectCornerSize; addY++) {
                bitMatrix.getByteMatrix().set(x + addX, y + addY, 0);
            }
        }
    }

    @Override
    /** 饰面 */
    public void finish() {
        //绘制LT
    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {
    }

}
