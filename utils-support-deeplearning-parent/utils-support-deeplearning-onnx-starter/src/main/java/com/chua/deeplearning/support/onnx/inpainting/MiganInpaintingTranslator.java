package com.chua.deeplearning.support.onnx.inpainting;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;

/**
 * MIGAN 图像修复（Inpainting）Translator。
 *
 * <p>MIGAN (MultImeGAN) 支持任意尺寸动态输入（H/W 可变，无 padding），
 * 双输入 uint8：{@code image [1,3,H,W]}（0~255）+ {@code mask [1,1,H,W]}
 * （0=保留，255=需修复），输出 {@code [1,3,H,W]} uint8 RGB。</p>
 *
 * <p>输入约定与项目 inpainting 模型一致：RGBA 图像，RGB=待修复图像，
 * A=掩码（255=需修复区域，0=保留区域）。
 * 当输入为纯 RGB（A 全 255）时，等价于整图修复/增强。</p>
 *
 * <p>ONNX 张量序为 NCHW（C 轴在内层），故图像输入按
 * {@code [1,3,H,W]} 布局、掩码按 {@code [1,1,H,W]} 布局构造。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiganInpaintingTranslator implements Translator<Image, Image> {

    /**
     * 当前帧宽度。
    */
    private int width;

    /**
     * 当前帧高度。
    */
    private int height;


    @Override
    /**
     * 构造双 uint8 输入张量（image + mask）。
     *
     * @param ctx   翻译上下文
     * @param input 输入 RGBA 图像（RGB=图像，A=修复掩码）
     * @return 双输入 NDList（顺序：image, mask）
     */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        width = input.getWidth();
        height = input.getHeight();
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        int[] rgb = buffered.getRGB(0, 0, width, height, null, 0, width);

        // 图像：NCHW [1,3,H,W]，第 0 维=batch、第 1 维=通道、第 2 维=行、第 3 维=列，
        // 线性下标 c*H*W + y*W + x，值域 0~255
        byte[] imgData = new byte[3 * height * width];
        int plane = height * width;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int v = rgb[row + x];
                imgData[x] = (byte) ((v >>> 16) & 0xFF);
                imgData[plane + x + y * width] = (byte) ((v >>> 8) & 0xFF);
                imgData[2 * plane + x + y * width] = (byte) (v & 0xFF);
            }
        }

        // 掩码：NCHW [1,1,H,W]，值域 0~255（255=需修复）
        byte[] maskData = new byte[height * width];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int alpha = (rgb[row + x] >>> 24) & 0xFF;
                maskData[x + y * width] = (byte) alpha;
            }
        }

        NDManager manager = ctx.getNDManager();
        NDArray image = manager.create(imgData, new Shape(1, 3, height, width));
        NDArray mask = manager.create(maskData, new Shape(1, 1, height, width));
        image.setName("image");
        mask.setName("mask");

        log.debug("[MiganInpainting] input={} mask={}", image.getShape(), mask.getShape());
        return new NDList(image, mask);
    }


    @Override
    /**
     * 修复结果 NCHW uint8 [1,3,H,W] 转 RGB 图像。
     *
     * @param ctx  翻译上下文
     * @param list 输出张量列表
     * @return 修复结果 RGB 图像
     */
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.getFirst();
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


    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }
}
