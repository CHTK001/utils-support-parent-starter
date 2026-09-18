package com.chua.zxing.support.qr;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.qr.AbstractQrCode;
import com.chua.common.support.lang.qr.BackgroundSetting;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.zxing.support.qr.background.BackgroundResolver;
import com.chua.zxing.support.qr.eye.CodeEyeRender;
import com.chua.zxing.support.qr.front.FrontResolver;
import com.chua.zxing.support.qr.logo.LogoResolver;
import com.chua.zxing.support.qr.point.CodePointRender;
import com.chua.zxing.support.qr.product.CodeProduct;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeGenWrapper;
import com.github.hui.quick.plugin.qrcode.wrapper.QrCodeOptions;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import static com.chua.common.support.constant.NameConstant.DEFAULT;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* zxing qr码
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"zxing", DEFAULT})
public class DefaultQrCode extends AbstractQrCode {
    /** Quiet_zone_大小 */
    private static final int QUIET_ZONE_SIZE = 4;

    /**
    * 创建 默认qr编码 实例
    * @param setting setting
    */
    public DefaultQrCode(QrSetting setting) {
        super(setting);
    }

    @Override
    /** 出 */
    public void out(String content, OutputStream outputStream) {
        QrCodeGenWrapper.Builder builder = QrCodeGenWrapper.of(content);

        builder.setW(setting.getWidth());
        builder.setH(setting.getHeight());
        builder.setErrorCorrection(ErrorCorrectionLevel.valueOf(setting.getCodeLevel().name()));

        try {
            ServiceProvider.of(CodePointRender.class).getNewExtension(DEFAULT).render(builder, setting.getCodePointSetting());
            ServiceProvider.of(CodeEyeRender.class).getNewExtension(DEFAULT).render(builder, setting.getCodeEyeSetting());

            QrCodeOptions codeOptions = builder.build();
            QRCode qrCode = code(codeOptions);
            BitMatrixEx bitMatrix = encode(qrCode, codeOptions);
            BufferedImage bufferedImage = getBufferedImage(codeOptions, setting, qrCode, bitMatrix);

            if(setting.hasBackgroundStyle()) {
                BackgroundSetting backgroundSetting = setting.getBackgroundSetting();
                bufferedImage = ServiceProvider.of(BackgroundResolver.class).getNewExtension(backgroundSetting.getBgImgStyle()).resolve(setting, setting.getBackgroundSetting(), bufferedImage, qrCode, bitMatrix);
            }
            bufferedImage = ServiceProvider.of(LogoResolver.class).getNewExtension(DEFAULT).resolve(bufferedImage, setting.getLogoSetting(), setting);
            bufferedImage = ServiceProvider.of(FrontResolver.class).getNewExtension(DEFAULT).resolve(bufferedImage, setting.getFrontSetting(), setting);

            BufferedImageUtils.writeToStream(bufferedImage, "png", outputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
    * 获取QR码图像
    *
    * @param codeOptions QR码的选项设置
    * @param setting QR码的详细设置，包括背景设置等
    * @param qrCode 生成的QR码实例
    * @param bitMatrix QR码的位矩阵表示
    * @return QR码图像
    * @throws IOException 生成图像时发生IO错误
    */
    private BufferedImage getBufferedImage(QrCodeOptions codeOptions, QrSetting setting, QRCode qrCode, BitMatrixEx bitMatrix) throws IOException {
        // 如果未设置背景风格，使用默认风格生成QR码图像
        return ServiceProvider.of(CodeProduct.class).getNewExtension(DEFAULT).create(codeOptions, bitMatrix, qrCode, setting);
    }



    /**
    * 对 zxing 的 qr编码writer 进行扩展, 解决白边过多的问题
    * <p/>
    * 源码参考 {@link com.google.zxing.qrcode.QRCodeWriter#encode(String, BarcodeFormat, int, int, Map)}
    * @param qrCodeConfig qr编码配置，不允许为 null
    * @return QR编码 对象
    */
    QRCode code(QrCodeOptions qrCodeConfig) throws WriterException {
        ErrorCorrectionLevel errorCorrectionLevel = ErrorCorrectionLevel.L;
        if (qrCodeConfig.getHints() != null) {
            if (qrCodeConfig.getHints().containsKey(EncodeHintType.ERROR_CORRECTION)) {
                errorCorrectionLevel = ErrorCorrectionLevel
                        .valueOf(qrCodeConfig.getHints().get(EncodeHintType.ERROR_CORRECTION).toString());
            }
        }
        return Encoder.encode(qrCodeConfig.getMsg(), errorCorrectionLevel, qrCodeConfig.getHints());
    }
    /**
    * 将 QR 码编码为 钻头matrixex 对象，支持自定义大小和边框。
    *
    * @param code qr编码对象，包含编码内容
    * @param codeOptions qr编码期权对象，包含QR码的自定义选项如大小、边框和Logo
    * @return BitMatrixEx对象，包含编码后的QR码图像
    * @throws WriterException 如果编码过程中发生错误。
    */
    BitMatrixEx encode(QRCode code, QrCodeOptions codeOptions) throws WriterException {
        int quietZone = 1;
        // 检查是否提供了自定义的安静区大小
        if (codeOptions.getHints() != null) {
            if (codeOptions.getHints().containsKey(EncodeHintType.MARGIN)) {
                quietZone = Integer.parseInt(codeOptions.getHints().get(EncodeHintType.MARGIN).toString());
            }
            // 限制安静区大小在允许的范围内
            if (quietZone > QUIET_ZONE_SIZE) {
                quietZone = QUIET_ZONE_SIZE;
            } else if (quietZone < 0) {
                quietZone = 0;
            }
        }
        // 渲染结果并应用Logo
        BitMatrixEx bitMatrixEx = renderResult(code, codeOptions.getW(), codeOptions.getH(), quietZone);
        clearLogo(bitMatrixEx, codeOptions.getLogoOptions());
        return bitMatrixEx;
    }

    /**
    * 清除Logo区域，避免渲染时被覆盖。
    *
    * @param bitMatrixEx 钻头matrixex对象，包含QR码图像
    * @param logoOptions Logo选项，如果存在，则清除相应区域
    */
    private static void clearLogo(BitMatrixEx bitMatrixEx, QrCodeOptions.LogoOptions logoOptions) {
        if (logoOptions == null) {
            return;
        }
        // 计算并清除Logo区域
        int rate = logoOptions.getRate() / 2;
        int width = bitMatrixEx.getByteMatrix().getWidth();
        int height = bitMatrixEx.getByteMatrix().getHeight();
        int logoWidth = (int) Math.ceil(width / (float)rate);
        int logoHeight = (int) Math.ceil(height / (float)rate);
        int logoX = (width - logoWidth) / 2;
        int logoY = (height - logoHeight) / 2;
        for (int x = logoX; x <= logoX + logoWidth; x++) {
            for (int y = logoY; y <= logoY + logoHeight; y++) {
                bitMatrixEx.getByteMatrix().set(x, y, 0);
            }
        }
    }

    /**
    * 对 zxing 的 qr编码writer 进行扩展, 解决白边过多的问题
    * <p/>
    *
    * @param code qr编码对象，包含编码内容
    * @param width 目标宽度
    * @param height 目标高度
    * @param quietZone 安静区大小，取值范围 [0, 4]
    * @return BitMatrixEx对象，包含调整后的QR码图像
    */
    private static BitMatrixEx renderResult(QRCode code, int width, int height, int quietZone) {
        ByteMatrix input = code.getMatrix();
        if (input == null) {
            throw new IllegalStateException();
        }

        // 计算二维码大小，并处理安静区
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        int qrWidth = inputWidth + (quietZone * 2);
        int qrHeight = inputHeight + (quietZone * 2);

        // 根据目标大小调整二维码尺寸
        int minSize = Math.min(width, height);
        int scale = calculateScale(qrWidth, minSize);
        if (scale > 0) {
            // 计算并调整边框大小以适应目标尺寸
            int padding, tmpValue;
            padding = (minSize - qrWidth * scale) / QUIET_ZONE_SIZE * quietZone;
            tmpValue = qrWidth * scale + padding;
            if (width == height) {
                width = tmpValue;
                height = tmpValue;
            } else if (width > height) {
                width = width * tmpValue / height;
                height = tmpValue;
            } else {
                height = height * tmpValue / width;
                width = tmpValue;
            }
        }

        // 最终计算输出尺寸并进行渲染
        int outputWidth = Math.max(width, qrWidth);
        int outputHeight = Math.max(height, qrHeight);

        int multiple = Math.min(outputWidth / qrWidth, outputHeight / qrHeight);
        int leftPadding = (outputWidth - (inputWidth * multiple)) / 2;
        int topPadding = (outputHeight - (inputHeight * multiple)) / 2;

        // 构建并返回结果
        BitMatrixEx res = new BitMatrixEx();
        res.setByteMatrix(input);
        res.setLeftPadding(leftPadding);
        res.setTopPadding(topPadding);
        res.setMultiple(multiple);

        res.setWidth(outputWidth);
        res.setHeight(outputHeight);
        return res;
    }



    /**
    * 如果留白超过15% , 则需要缩放
    * (15% 可以根据实际需要进行修改)
    *
    * @param qrCodeSize 二维码大小
    * @param expectSize 期望输出大小
    * @return 返回缩放比例, <= 0 则表示不缩放, 否则指定缩放参数
    */
    private static int calculateScale(int qrCodeSize, int expectSize) {
        if (qrCodeSize >= expectSize) {
            return 0;
        }

        int scale = expectSize / qrCodeSize;
        int abs = expectSize - scale * qrCodeSize;
        if (abs < expectSize * 0.15) {
            return 0;
        }

        return scale;
    }

}
