package com.chua.zxing.support.qr.background;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.BackgroundSetting;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.google.zxing.qrcode.encoder.QRCode;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 渐进色
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("PROGRESSIVE")
@Slf4j
public class ProgressiveEmptinessBackgroundResolver implements BackgroundResolver {


    @Override
    /**
     * 解析
    */
    public BufferedImage resolve(QrSetting setting, BackgroundSetting backgroundSetting, BufferedImage image, QRCode qrCode, BitMatrixEx bitMatrix) {
        Color fromColor = Converter.convertIfNecessary(backgroundSetting.getFromColor(), Color.class);
        if (null == fromColor) {
            log.warn("fromColor is null");
            return image;
        }

        Color toColor =  Converter.convertIfNecessary(backgroundSetting.getToColor(), Color.class);
        if (null == toColor) {
            log.warn("fromColor is null");
            return image;
        }

        Color[] gradientColor = BufferedImageUtils.getGradientColor(fromColor, toColor, 30);
        return BufferedImageUtils.handleGradientQRCodeOblique(image, gradientColor);
    }

}

