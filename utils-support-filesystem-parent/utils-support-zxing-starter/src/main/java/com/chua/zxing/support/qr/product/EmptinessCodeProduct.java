package com.chua.zxing.support.qr.product;

import com.chua.common.support.lang.qr.QrSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;
import com.google.zxing.qrcode.encoder.QRCode;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认二维码生成器
 * @author CH
 * @since 4.0.0.42
 */
public class EmptinessCodeProduct implements CodeProduct{

    @Override
    public BufferedImage create(QrCodeOptions qrCodeOptions, BitMatrixEx bitMatrix, QRCode qrCode, QrSetting setting) throws IOException {
        return null;
    }

}
