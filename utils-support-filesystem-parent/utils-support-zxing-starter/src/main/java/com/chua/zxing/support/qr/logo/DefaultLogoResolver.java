package com.chua.zxing.support.qr.logo;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.LogoSetting;
import com.chua.common.support.lang.qr.LogoStyle;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.StringUtils;
import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * logo解析器
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultLogoResolver implements LogoResolver {

    @Override
    public BufferedImage resolve(BufferedImage bufferedImage, LogoSetting logoSetting, QrSetting setting) throws IOException {
        if(null == logoSetting) {
            return bufferedImage;
        }

        QrCodeOptions.LogoOptions.LogoOptionsBuilder builder = QrCodeOptions.LogoOptions.builder();
        builder.rate(12);

        if (setting.hasLogo()) {
            builder.logo(BufferedImageUtils.toBufferedImage(logoSetting.getLogoImage()));
            builder.opacity(logoSetting.getLogoAlpha());
            formatLogoStyle(builder, logoSetting.getLogoShape());
            builder.border(true);
            if (StringUtils.isNotBlank(logoSetting.getLogoBorderColor())) {
                builder.outerBorderColor(Converter.convertIfNecessary(logoSetting.getLogoBorderColor(), Color.class));
            }
        }
        return QrCodeRenderHelper.drawLogo(bufferedImage, builder.build());
    }


    /**
     * 根据提供的Logo风格格式化QrCodeGenWrapper的构建器设置。
     *
     * @param builder QrCodeGenWrapper的构建器对象，设置Logo风格
     * @param logoStyle 待设置的Logo风格，枚举类型LogoStyle
     *                  可以是圆形(CIRCLE)或矩形(ROUND)。
     *
     * 根据传入的logoStyle参数决定设置圆形还是矩形的Logo风格。
     * 如果logoStyle为圆形，则设置为圆形Logo风格；
     * 否则，默认设置为矩形Logo风格。
     */
    private void formatLogoStyle(QrCodeOptions.LogoOptions.LogoOptionsBuilder builder, LogoStyle logoStyle) {
        // 验证logoStyle是否为null，若为null则抛出NullPointerException
        if (Objects.requireNonNull(logoStyle) == LogoStyle.CIRCLE) {
            builder.logoStyle(QrCodeOptions.LogoStyle.CIRCLE);
            return;
        }
        // 默认设置为矩形Logo风格
        builder.logoStyle(QrCodeOptions.LogoStyle.ROUND);
    }
}
