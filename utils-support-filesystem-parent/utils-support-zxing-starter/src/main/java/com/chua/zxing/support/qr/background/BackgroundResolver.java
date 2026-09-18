package com.chua.zxing.support.qr.background;

import com.chua.common.support.lang.qr.BackgroundSetting;
import com.chua.common.support.lang.qr.QrSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.google.zxing.qrcode.encoder.QRCode;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* background解析器接口定义。
* 定义后台处理任务的解决策略或机制。
*
* @author CH
* @since 4.0.0.42
 */
public interface BackgroundResolver {

    /**
    * 根据给定的设置生成并解析QR码图像。
    *
    * @param setting QR码的设置，包括大小、颜色等
    * @param backgroundSetting 背景的设置，包括颜色、图片等
    * @param image 使用的背景图像，如果设置中包含背景图像
    * @param qrCode 包含QR码数据的对象
    * @param bitMatrix QR码的位矩阵表示
    * @return 根据设置和提供的QR码数据生成的带背景的BufferedImage对象
    */
    BufferedImage resolve(QrSetting setting, BackgroundSetting backgroundSetting, BufferedImage image, QRCode qrCode, BitMatrixEx bitMatrix);
}


