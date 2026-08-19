package com.chua.zxing.support.qr.front;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.FrontSetting;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 前置解析器
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultFrontResolver implements FrontResolver {

    @Override
    public BufferedImage resolve(BufferedImage bufferedImage, FrontSetting frontSetting, QrSetting setting) throws IOException {
        if(null == frontSetting) {
            return bufferedImage;
        }

        QrCodeOptions.FrontImgOptions.FtImgOptionsBuilder builder = QrCodeOptions.FrontImgOptions.builder();
        builder.ftW(setting.getWidth());
        builder.ftH(setting.getHeight());

        if (null != frontSetting.getFtFillColor()) {
            builder.fillImg(Converter.convertIfNecessary(frontSetting.getFtFillColor(), Color.class));
        }

        if (frontSetting.getFtStartX() > 0) {
            builder.startX(frontSetting.getFtStartX());
        }

        if (frontSetting.getFtStartY() > 0) {
            builder.startY(frontSetting.getFtStartY());
        }

        if (null != frontSetting.getFtImg()) {
            builder.ftImg(BufferedImageUtils.toBufferedImage(frontSetting.getFtImg()));
        }
        return QrCodeRenderHelper.drawFrontImg(bufferedImage, builder.build());
    }
}
