package com.chua.zxing.support.qr.front;

import com.chua.common.support.lang.qr.FrontSetting;
import com.chua.common.support.lang.qr.QrSetting;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 前置解析器
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FrontResolver {
    /**
     * 使用给定的设置解析并构建QrCodeGenWrapper对象。
     * 会将QrSetting中的配置应用到QrCodeGenWrapper.Builder上，最终生成QrCodeGenWrapper对象。
     *
     * @param frontSetting  前置配置
     * @param setting Qr码的设置包含生成Qr码所需的所有配置，如尺寸、颜色、错误修正级别等。
     *                      该参数决定了QrCodeGenWrapper对象的生成方式。
     * @param bufferedImage 缓冲图像
     * @return {@link BufferedImage}
     * @throws IOException IOException
     */
    BufferedImage resolve(BufferedImage bufferedImage, FrontSetting frontSetting, QrSetting setting) throws IOException;

}
