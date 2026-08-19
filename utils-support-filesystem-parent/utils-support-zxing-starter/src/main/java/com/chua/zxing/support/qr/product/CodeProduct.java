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
 * @author CH
 * @since 4.0.0.42
 */
public interface CodeProduct {


    /**
     * 创建一个QR码图像。
     *
     * @param qrCodeOptions QR码选项，包含编码类型、错误纠正级别等配置
     * @param bitMatrix QR码的位矩阵扩展对象，包含编码后的数据
     * @param qrCode QR码对象，包含生成QR码所需的基本信息
     * @param setting QR码的设置，例如图像尺寸、颜色等
     * @return 返回BufferedImage对象，代表生成的QR码图像
     * @throws IOException IOException
     */
    BufferedImage create(QrCodeOptions qrCodeOptions, BitMatrixEx bitMatrix, QRCode qrCode, QrSetting setting) throws IOException;
}
