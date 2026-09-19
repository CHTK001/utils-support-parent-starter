package com.chua.zxing.support.qr.product;

import com.chua.common.support.lang.qr.QrSetting;
import com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils;
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
public class DefaultCodeProduct implements CodeProduct{

    @Override
    /**
     * 创建
    */
    public BufferedImage create(QrCodeOptions qrCodeOptions, BitMatrixEx bitMatrix, QRCode qrCode, QrSetting setting) throws IOException {
        return toBufferedImage(qrCodeOptions, bitMatrix, setting);
    }

    /**
     * 将二维码数据转换为带有可能的背景图、logo和前景图的缓冲镜像对象。
     *
     * @param qrCodeConfig 二维码配置选项，包含背景图、logo和前景图等设置
     * @param bitMatrix 二维码的位矩阵数据
     * @param setting      二维码设置
     * @return 背景图、logo和前景图的BufferedImage对象
     * @throws IOException 如果读取背景图、logo或前景图时发生错误。
     */
    BufferedImage toBufferedImage(QrCodeOptions qrCodeConfig, BitMatrixEx bitMatrix, QrSetting setting) throws IOException {
        // 绘制二维码基本图形
        return QrCodeRenderUtils.drawQrInfo(qrCodeConfig, bitMatrix, setting);
    }

}
