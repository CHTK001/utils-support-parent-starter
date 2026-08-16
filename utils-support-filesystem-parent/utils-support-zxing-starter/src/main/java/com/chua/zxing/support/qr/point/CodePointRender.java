package com.chua.zxing.support.qr.point;

import com.chua.common.support.lang.qr.CodePointSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeGenWrapper;

import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 码点解析器
 * @author CH
 * @since 4.0.0.42
 */
public interface CodePointRender {
    /**
     * 使用给定的设置解析并构建QrCodeGenWrapper对象。
     * 会将QrSetting中的配置应用到QrCodeGenWrapper.Builder上，最终生成QrCodeGenWrapper对象。
     *
     * @param builder QrCodeGenWrapper对象的构建器提供了一个用于构建QrCodeGenWrapper对象的可变容器。
     *                通过该参数，设置将被应用到QrCodeGenWrapper的构建过程中。
     * @param pointSetting Qr码的设置包含生成Qr码所需的所有配置，如尺寸、颜色、错误修正级别等。
     *                该参数决定了QrCodeGenWrapper对象的生成方式。
     * @throws IOException IOException
     */
    void render(QrCodeGenWrapper.Builder builder, CodePointSetting pointSetting) throws IOException;

}
