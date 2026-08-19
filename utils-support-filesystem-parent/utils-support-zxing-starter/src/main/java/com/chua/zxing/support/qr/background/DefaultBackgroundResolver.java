package com.chua.zxing.support.qr.background;

import com.chua.common.support.lang.qr.BackgroundSetting;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;
import com.google.zxing.qrcode.encoder.QRCode;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultBackgroundResolver implements BackgroundResolver{

    @Override
    /** 解析 */
    public BufferedImage resolve(QrSetting setting, BackgroundSetting backgroundSetting, BufferedImage image, QRCode qrCode, BitMatrixEx bitMatrix) {
        
        return QrCodeRenderHelper.drawBackground(image,
                QrCodeOptions.BgImgOptions.builder()
                        .bgImg( BufferedImageUtils.toBufferedImage(backgroundSetting.getBackgroundImage()))
                        .bgW( setting.getWidth())
                        .bgH(setting.getHeight())
                        .opacity(backgroundSetting.getBgAlpha())
                        .build()
                );
    
    }
}


