package com.chua.zxing.support.qr.eye;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.CodeEyeSetting;
import com.chua.common.support.lang.qr.CodeEyeStyle;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.IoUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.zxing.support.qr.DefaultQrCode;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeGenWrapper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static com.chua.common.support.lang.qr.CodeEyeStyle.NONE;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 码眼解析器
* @author CH
* @since 4.0.0.42
 */
public class DefaultCodeEyeRender implements CodeEyeRender {
    @Override
    /** Render */
    public void render(QrCodeGenWrapper.Builder builder, CodeEyeSetting setting) throws IOException {
        if (!StringUtils.isBlank(setting.getCodeEyeColor())) {
            Color color = Converter.convertIfNecessary(setting.getCodeEyeColor(), Color.class);
            builder.setDetectOutColor(color);
            builder.setDetectInColor(color);
        }

        builder.setDetectSpecial();

        if (StringUtils.isNotBlank(setting.getCodeEyeImage())) {
            builder.setDetectImg(setting.getCodeEyeImage()).setDiaphaneityFill(true);
            setting.setCodeEye(CodeEyeStyle.IMAGE);
        }

        if (StringUtils.isNotBlank(setting.getLTCodeEyeImage())) {
            BufferedImage bufferedImage = BufferedImageUtils.toBufferedImage(setting.getLTCodeEyeImage());
            builder.setDetectImg(bufferedImage).setDiaphaneityFill(true);
            setting.setCodeEye(CodeEyeStyle.IMAGE);
        }

        if (StringUtils.isNotBlank(setting.getLBCodeEyeImage())) {
            BufferedImage bufferedImage = BufferedImageUtils.toBufferedImage(setting.getLBCodeEyeImage());
            builder.setDetectImg(bufferedImage).setDiaphaneityFill(true);
            setting.setCodeEye(CodeEyeStyle.IMAGE);
        }

        if (StringUtils.isNotBlank(setting.getRTCodeEyeImage())) {
            BufferedImage bufferedImage = BufferedImageUtils.toBufferedImage(setting.getRTCodeEyeImage());
            builder.setDetectImg(bufferedImage).setDiaphaneityFill(true);
            setting.setCodeEye(CodeEyeStyle.IMAGE);
        }

        //formatCodeEye(builder, setting);
    }

    /**
    * 格式化编码eye
    *
    * @param builder 构建器
    * @param setting setting
     */
    private void formatCodeEye(QrCodeGenWrapper.Builder builder, CodeEyeSetting setting) {
        CodeEyeStyle codeEye = setting.getCodeEye();
        if(null == codeEye || NONE == codeEye) {
            return;
        }

        if(codeEye == CodeEyeStyle.CIRCLE) {
            try (InputStream inputStream = DefaultQrCode.class.getResourceAsStream("/eye3.png")) {
                byte[] byteArray = IoUtils.asBytes(inputStream);
                BufferedImage bufferedImage = BufferedImageUtils.toBufferedImage(byteArray);
                if(StringUtils.isNotBlank(setting.getCodeEyeColor())) {
                    BufferedImageUtils.changeColor(bufferedImage, Converter.convertIfNecessary(setting.getCodeEyeColor(), Color.class));
                }
                builder.setDetectImg(bufferedImage);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
