package com.chua.zxing.support.qr.draw;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.zxing.support.qr.draw.eye.DrawEyeResolver;
import com.chua.zxing.support.qr.draw.point.DrawPointResolver;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;

import static com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.drawQrDotBgImg;
import static com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.inDetectCornerArea;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * DefaultDrawResolver 类提供了一个默认的绘图解析解决方案。
 * 它继承自 AbstractDrawResolver，通过实现特定的接口和注解配置，来支持不同的绘图形状解析。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({ "MINI_RECT", "RECT" })
public class DefaultDrawResolver extends AbstractDrawResolver {

    /**
     * 创建 DefaultDrawResolver 实例
     * @param g2 g2
     * @param detectInnerColor detectInnerColor
     * @param detectOutColor detectOutColor
     * @param qrCodeConfig qrCodeConfig
     * @param bitMatrix bitMatrix
     * @param drawOptions drawOptions
     * @param setting setting
     */
    public DefaultDrawResolver(Graphics2D g2, Color detectInnerColor, Color detectOutColor, QrCodeOptions qrCodeConfig,
            BitMatrixEx bitMatrix, QrCodeOptions.DrawOptions drawOptions, QrSetting setting) {
        super(g2, detectInnerColor, detectOutColor, qrCodeConfig, bitMatrix, drawOptions, setting);
    }

    @Override
    /** Draw */
    public void draw() {
        DrawEyeResolver drawEyeResolver = ServiceProvider.of(DrawEyeResolver.class)
                .getNewExtension(setting.getCodeEyeSetting().getCodeEye(), qrCodeConfig, g2, bitMatrix, matrixW,
                        matrixH, leftPadding, topPadding, infoSize,
                        detectCornerSize, detectOutColor, detectInnerColor, setting.getCodeEyeSetting());

        DrawPointResolver drawPointResolver = ServiceProvider.of(DrawPointResolver.class)
                .getNewExtension(setting.getCodePointSetting().getCodePoint(), qrCodeConfig, g2, bitMatrix, leftPadding,
                        topPadding, infoSize);

        QrCodeRenderHelper.DetectLocation detectLocation;
        for (int x = 0; x < matrixW; x++) {
            for (int y = 0; y < matrixH; y++) {
                detectLocation = inDetectCornerArea(x, y, matrixW, matrixH, detectCornerSize);
                if (bitMatrix.getByteMatrix().get(x, y) == 0) {
                    // 探测图形内部的元素与二维码的01点图绘制逻辑分开
                    // 绘制二维码中不在探测图形内部的0点图
                    if (!detectLocation.detectedArea() || !qrCodeConfig.getDetectOptions().getSpecial()) {
                        drawQrDotBgImg(qrCodeConfig, g2, leftPadding, topPadding, infoSize, x, y);
                    }
                    continue;
                }

                if (detectLocation.detectedArea() && qrCodeConfig.getDetectOptions().getSpecial()) {
                    // 绘制三个位置探测图形
                    drawEyeResolver.draw(x, y, detectLocation);
                } else {
                    g2.setColor(preColor);
                    drawPointResolver.draw(x, y);
                }
            }
        }

        drawEyeResolver.finish();
        // 码点后处理：允许实现类做连通块合并等二阶段绘制
        drawPointResolver.finish();
    }
}
