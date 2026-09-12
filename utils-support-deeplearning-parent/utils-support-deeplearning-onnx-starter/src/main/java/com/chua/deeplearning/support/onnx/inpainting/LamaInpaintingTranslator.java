package com.chua.deeplearning.support.onnx.inpainting;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;

/**
   * lama 图像修复（Inpainting）Translator
 * <p>
   * lama (Large Mask Inpainting) 是基于拉普拉斯金字塔的大尺寸掩码修复模型，
 * 能够有效修复图像中的大面积缺失区域，生成自然连贯的内容。
 * 适用于修复照片划痕、移除水印、擦除不需要的物体等场景。
 * </p>
 * <p>
   * 模型来源：huggingface.co/onnx-社区/lama-inpainting-ONNX
 * 架构：U-Net 风格编码器-解码器 + 拉普拉斯金字塔
   * 输入：镜像（RGBA 格式，RGB=待修复图像，A=掩码，255=需修复区域）
   * 输出：镜像（RGB 修复结果）
 * </p>
 * <p>
 * 输入流程：
 * <ol>
 *   <li>从 RGBA 图像中分离 RGB 通道作为图像输入</li>
 *   <li>从 Alpha 通道提取二值掩码（255=修复区域，0=保留区域）</li>
 *   <li>ONNX 模型同时接收图像和掩码，输出修复结果</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LamaInpaintingTranslator implements Translator<Image, Image> {

    @Override
    /** 处理输入 */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDManager manager = ctx.getNDManager();

        // 获取原始尺寸
        int width = input.getWidth();
        int height = input.getHeight();

        // 提取 RGB 图像，归一化到 [-1, 1]
        NDArray rgb = input.toNDArray(manager, Image.Flag.COLOR);
        if (!DataType.FLOAT32.equals(rgb.getDataType())) {
            rgb = rgb.toType(DataType.FLOAT32, false);
        }
        rgb = rgb.div(255.0f).mul(2.0f).sub(1.0f);
        rgb = rgb.transpose(2, 0, 1).expandDims(0);
        rgb.setName("image");

        // 从 Alpha 通道提取掩码
        NDArray mask = extractMask(manager, input, width, height);
        mask.setName("mask");

        log.debug("[LamaInpainting] Input: image={}, mask={}", rgb.getShape(), mask.getShape());
        return new NDList(rgb, mask);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();

        // output: [1, 3, H, W], 值域 [-1, 1] -> [0, 255] RGB
        output = output.squeeze(0);         // [3, H, W]
        output = output.transpose(1, 2, 0); // [H, W, 3]
        output = output.add(1.0f).div(2.0f).mul(255.0f);
        output = output.toType(DataType.UINT8, true);

        return ImageFactory.getInstance().fromNDArray(output);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 从 RGBA 图像的 Alpha 通道提取二值掩码
     * <p>
     * Alpha=255 -> 掩码=1（需修复区域）
     * Alpha=0   -> 掩码=0（保留区域）
     *
     * @param manager   nd管理器
     * @param image     输入图像（应为 RGBA 格式）
     * @param width     图像宽度
     * @param height    图像高度
     * @return 二值掩码 ndarray，形状 [1, 1, H, W]，值域 [0, 1]
     */
    private static NDArray extractMask(NDManager manager, Image image, int width, int height) {
        BufferedImage buffered = (BufferedImage) image.getWrappedImage();
        int[] rgba = buffered.getRGB(0, 0, width, height, null, 0, width);

        float[] maskData = new float[width * height];
        for (int i = 0; i < rgba.length; i++) {
            int alpha = (rgba[i] >> 24) & 0xFF;
            maskData[i] = alpha / 255.0f;
        }

        NDArray mask = manager.create(maskData);
        mask = mask.reshape(1, 1, height, width);
        return mask;
    }
}
