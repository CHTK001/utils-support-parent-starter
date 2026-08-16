package com.chua.zxing.support.qr.point;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodePointSetting;
import com.chua.common.support.lang.qr.CodePointStyle;
import com.chua.common.support.utils.StringUtils;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeGenWrapper;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;

import java.awt.*;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 码点解析器
 * 
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultCodePointRender implements CodePointRender {

    @Override
    public void render(QrCodeGenWrapper.Builder builder, CodePointSetting setting) throws IOException {
        if (StringUtils.isEmpty(setting.getCodePointColor())) {
            Color color = Converter.convertIfNecessary(setting.getCodePointColor(), Color.class);
            builder.setDrawPreColor(color);
        }

        builder.setDrawEnableScale(setting.isDrawEnableScale());
        formatCodePoint(builder, setting, setting.getCodePoint());
    }

    /**
     * 根据指定的码点样式格式化QrCodeGenWrapper的构建器设置。
     *
     * @param builder QrCodeGenWrapper的构建器对象，设置Qr码的绘制样式和其他选项
     * @param setting   码点
     * @param codePoint 码点样式枚举，定义了Qr码的绘制形状，例如矩形或圆形
     */
    private void formatCodePoint(QrCodeGenWrapper.Builder builder, CodePointSetting setting, CodePointStyle codePoint) {
        String codePointImage = setting.getCodePointImage();
        if (StringUtils.isNotEmpty(codePointImage)) {
            setting.setCodePoint(CodePointStyle.IMAGE);
            return;
        }
        // 根据码点样式切换绘制风格，并设置相应的选项
        switch (codePoint) {
            case MINI_RECT:
                // 设置绘制样式为矩形
                builder.setDrawStyle(QrCodeOptions.DrawStyle.MINI_RECT);
                return;
            case RECTANGLE:
                // 设置绘制样式为矩形
                builder.setDrawStyle(QrCodeOptions.DrawStyle.RECT);
                return;
            case CIRCLE:
                // 设置绘制样式为圆形，并启用按比例绘制的选项
                builder.setDrawStyle(QrCodeOptions.DrawStyle.CIRCLE);
                builder.setDrawEnableScale(true);
                return;
            case ROUND_DOT:
                // 艺术流体码点：使用自定义绘制器（SPI: ROUND_DOT），此处沿用 CIRCLE 以启用图形绘制通道
                builder.setDrawStyle(QrCodeOptions.DrawStyle.CIRCLE);
                builder.setDrawEnableScale(false);
                return;
            case TEXT:
                if (StringUtils.isEmpty(setting.getCodePointText())) {
                    throw new IllegalArgumentException("CodePointText is null");
                }
                builder.setDrawStyle(QrCodeOptions.DrawStyle.TXT);
                builder.setQrText(setting.getCodePointText());
                builder.setQrTxtMode(QrCodeOptions.TxtMode.RANDOM);
            default:
                // 如果没有匹配到任何样式，则不进行任何设置
        }

    }
}
