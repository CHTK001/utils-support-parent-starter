package com.chua.zxing.support.qr.eye;

import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeGenWrapper;

import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 码眼解析器
*
* @author CH
* @since 4.0.0.42
 */
public interface CodeEyeRender {
    /**
    * 使用给定的设置解析并构建qr编码gen包装器对象。
    * 会将qrsetting中的配置应用到qr编码gen包装器.构建器上，最终生成qr编码gen包装器对象。
    *
    * @param builder qr编码gen包装器对象的构建器提供了一个用于构建qr编码gen包装器对象的可变容器。
    * 通过该参数，设置将被应用到qr编码gen包装器的构建过程中。
    * @param setting Qr码的设置包含生成Qr码所需的所有配置，如尺寸、颜色、错误修正级别等。
    * 该参数决定了qr编码gen包装器对象的生成方式。
    * @throws IOException io异常
     */
    void render(QrCodeGenWrapper.Builder builder, CodeEyeSetting setting) throws IOException;

}
