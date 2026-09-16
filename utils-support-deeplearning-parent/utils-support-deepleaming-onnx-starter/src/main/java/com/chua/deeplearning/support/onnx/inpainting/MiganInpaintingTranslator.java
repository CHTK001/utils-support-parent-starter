package com.chua.deeplearning.support.onnx.inpainting;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;

/**
 * MiGAN 图像修复（Inpainting）Translator。
 * <p>
 * MiGAN（MultImeGAN）是支持任意尺寸输入的生成式图像修复模型，
 * 输入为 uint8 NCHW 双输入：{@code image [1,3,H,W]}（0~255 像素值）
 * 与 {@code mask [1,1,H,W]}（0=保留，255=需修复区域），
 * 输出 {@code [1,3,H,W]} uint8 修复结果。
 * </p>
 * <p>
 * 输入约定：传入 RGBA 图像，RGB=待修复图像，Alpha=掩码（255=需修复区域，0=保留区域）。
 * 若输入为纯 RGB（无 Alpha），则整图作为修复区域。
 * </p>
 * <p>
 * ONNX 模型支持动态 H/W，无需固定 512 尺寸，自动适配输入尺寸。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiganInpaintingTranslator implements Translator<Image, Image> {

    /**
     * 处理输入：构造双 uint8 NCHW 输入（image + mask）。
     *
     * @param ctx   翻译上下文
     * @param input 输入 RGBA 图像（RGB=图像，A=修复掩码）
     * @return 双输入 NDList（顺序：image, mask）
     */
    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        int width = input.getWidth();
        int height = input.getHeight();
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        int[] rgba = buffered.getRGB(0, 0, width, height, null, 0, width);

        // image: NCHW uint8 [1,3,H,W]，线性下标 c*H*W + y*W + x，值域 0~255
        byte[] imgData = new byte[3 * height * width];
        int plane = height * width;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = rgba[y * width + x];
                imgData[x] = (byte) ((v >>> 16) & 0xFF);
                imgData[plane + x + y * width] = (byte) ((v >>> 8) & 0xFF);
                imgData[2 * plane + x + y * width] = (byte) (v & 0xFF);
            }
        }

        // mask: NCHW uint8 [1,1,H,W]，值域 0~255（255=需修复）
        byte[] maskData = new byte[height * width];
        boolean hasAlpha = buffered.getType() == BufferedImage.TYPE_INT_ARGB
                || buffered.getType() == BufferedImage.TYPE_INT_ARGB_PRE
                || buffered.getType() == BufferedImage.TYPE_4BYTE_ABGR;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = rgba[y * width + x];
                int alpha = hasAlpha ? (v >>> 24) & 0xFF : 255;
                maskData[x + y * width] = (byte) alpha;
            }
        }

        NDArray image = ctx.getNDManager().create(imgData, new ai.djl.ndarray.types.Shape(1, 3, height, width))
                .toType(DataType.UINT8, false);
        NDArray mask = ctx.getNDManager().create(maskData, new ai.djl.ndarray.types.Shape(1, 1, height, width))
                .toType(DataType.UINT8, false);
        image.setName("image");
        mask.setName("mask");

        log.debug("[MiganInpainting] image={}, mask={}", image.getShape(), mask.getShape());
        return new NDList(image, mask);
    }

    /**
     * 处理输出：uint8 NCHW [1,3,H,W] 转 RGB 图像。
     *
     * @param ctx  翻译上下文
     * @param list 输出张量列表
     * @return 修复结果 RGB 图像
     */
    @Override
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.get(0);
        byte[] data = output.toByteArray();
        int h = (int) output.getShape().get(2);
        int w = (int) output.getShape().get(3);
        int plane = h * w;

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = x + y * w;
                int r = data[i] & 0xFF;
                int g = data[plane + i] & 0xFF;
                int b = data[2 * plane + i] & 0xFF;
                result.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    /**
     * 获取Batchifier
     *
     * @return null
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
