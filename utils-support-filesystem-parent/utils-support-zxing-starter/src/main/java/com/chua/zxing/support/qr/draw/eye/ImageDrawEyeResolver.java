package com.chua.zxing.support.qr.draw.eye;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.StringUtils;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;



/**
* 图片码眼
* @author CH
* @since 4.0.0.42
 */
public class ImageDrawEyeResolver extends AbstractDrawEyeResolver{


    /**
    * 创建 镜像draweye解析器 实例
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
    public ImageDrawEyeResolver(QrCodeOptions qrCodeConfig, Graphics2D g2, BitMatrixEx bitMatrix, int matrixW, int matrixH, int leftPadding, int topPadding, int infoSize, int detectCornerSize, Color detectOutColor, Color detectInnerColor, CodeEyeSetting codeEyeSetting) {
        super(qrCodeConfig, g2, bitMatrix, matrixW, matrixH, leftPadding, topPadding, infoSize, detectCornerSize, detectOutColor, detectInnerColor, codeEyeSetting);
    }

    @Override
    /** Draw */
    public void draw(int x, int y, QrCodeRenderHelper.DetectLocation detectLocation) {
        BufferedImage detectedImg = qrCodeConfig.getDetectOptions().chooseDetectedImg(detectLocation);
        if (detectedImg != null) {
            if(StringUtils.isNotBlank(codeEyeSetting.getCodeEyeColor())) {
                BufferedImageUtils.changeColor(detectedImg, Converter.convertIfNecessary(codeEyeSetting.getCodeEyeColor(), Color.class));
            }
            // 使用探测图形的图片来渲染
            g2.drawImage(detectedImg
                            .getScaledInstance(infoSize * detectCornerSize, infoSize * detectCornerSize, Image.SCALE_SMOOTH),
                    leftPadding + x * infoSize, topPadding + y * infoSize, null);

            // 图片直接渲染完毕之后，将其他探测图形的点设置为0，表示不需要再次渲染
            for (int addX = 0; addX < detectCornerSize; addX++) {
                for (int addY = 0; addY < detectCornerSize; addY++) {
                    bitMatrix.getByteMatrix().set(x + addX, y + addY, 0);
                }
            }
        }
    }

    @Override
    void draw(Graphics2D g2, int x, int y, int w, int h) {

    }

    @Override
    /** 饰面 */
    public void finish() {

    }
}
