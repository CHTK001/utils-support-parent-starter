package com.chua.zxing.support.qr.draw;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.zxing.support.qr.draw.eye.DrawEyeResolver;
import com.chua.zxing.support.qr.draw.point.DrawPointResolver;
import com.github.hui.quick.plugin.qrcode.entity.RenderImgResourcesV2;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.helper.v2.ImgRenderV2Helper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;

import static com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.inDetectCornerArea;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
   * 图像绘制解析器类，继承自抽象绘制解析器类 抽象draw解析器。
 * 用于处理图像的绘制逻辑。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("IMAGE_V2")
public class ImageDrawResolver extends AbstractDrawResolver{
    /**
      * 创建 镜像draw解析器 实例
     * @param g2 g2
     * @param detectInnerColor Color
     * @param detectInnerColor Color
     * @param qrCodeConfig qr编码期权
     * @param bitMatrix 钻头matrixex
     * @param qrCodeConfig qr编码期权
     * @param drawOptions draw期权
     * @param setting qrsetting
     * @param detectInnerColor detect内部color
     * @param detectOutColor detect出color
     * @param qrCodeConfig qr编码配置
     * @param bitMatrix 钻头matrix
     * @param setting setting
     */
    public ImageDrawResolver(Graphics2D g2, Color detectInnerColor, Color detectOutColor, QrCodeOptions qrCodeConfig, BitMatrixEx bitMatrix, QrCodeOptions.DrawOptions drawOptions, QrSetting setting) {
        super(g2, detectInnerColor, detectOutColor, qrCodeConfig, bitMatrix, drawOptions, setting);
    }

    @Override
    /** Draw */
    public void draw() {
        DrawEyeResolver drawEyeResolver = ServiceProvider.of(DrawEyeResolver.class)
                .getNewExtension(setting.getCodeEyeSetting().getCodeEye());

        DrawPointResolver drawPointResolver = ServiceProvider.of(DrawPointResolver.class)
                .getNewExtension(setting.getCodePointSetting().getCodePoint());

        // 若探测图形特殊绘制，则提前处理掉
        RenderImgResourcesV2 imgResourcesV2 = drawOptions.getImgResourcesForV2();
        for (int x = 0; x < matrixW; x++) {
            for (int y = 0; y < matrixH; y++) {
                QrCodeRenderHelper.DetectLocation detectLocation = inDetectCornerArea(x, y, matrixW, matrixH, detectCornerSize);
                if (detectLocation.detectedArea()) {
                    // 若探测图形特殊绘制，则单独处理
                    if (qrCodeConfig.getDetectOptions().getSpecial()) {
                        if (bitMatrix.getByteMatrix().get(x, y) == 1) {
                            // 绘制三个位置探测图形
                            drawEyeResolver.draw(x, y, detectLocation);
                            bitMatrix.getByteMatrix().set(x, y, 0);
                        }
                    } else {
                        if (bitMatrix.getByteMatrix().get(x, y) == 0 && imgResourcesV2.getDefaultBgImg() != null) {
                            drawPointResolver.draw(x, y);
                        }
                    }
                    continue;
                }

                // 非探测区域内的0点图渲染
                if (bitMatrix.getByteMatrix().get(x, y) == 0 && imgResourcesV2.getDefaultBgImg() != null) {
                    drawPointResolver.draw(x, y);
                }
            }
        }
        ImgRenderV2Helper.drawImg(g2, bitMatrix.getByteMatrix(), drawOptions.getImgResourcesForV2(), leftPadding, topPadding, infoSize);
    }
}

