package com.chua.deeplearning.support.onnx.style;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Fast Neural Style Transfer Translator
 * <p>
 * 基于 Johnson 等人的感知损失实时风格迁移论文（Perceptual Losses for Real-Time Style Transfer），
 * 使用 Instance Normalization 实现快速风格迁移。
 * <p>
 * 支持的风格模型（来自 ONNX 官方模型仓库）：
 * <ul>
 *   <li>candy - 糖果风格</li>
 *   <li>mosaic - 马赛克风格</li>
 *   <li>rain-princess - 雨中公主风格</li>
 *   <li>udnie - 抽象风格</li>
 *   <li>pointilism - 点彩派/卜利吉风格</li>
 * </ul>
 * <p>
 * 模型输入: NCHW [1, 3, H, W] float32，RGB [0, 255]（无归一化）
 * 模型输出: NCHW [1, 3, H, W] float32，RGB [0, 255]（需 clip）
 * <p>
 * 参考: https://github.com/onnx/models/tree/main/validated/vision/style_transfer/fast_neural_style
 *
 * @author CH
 * @version 4.0.0.42
 * @since 2026/8/15
 */
@Slf4j
public class FastNeuralStyleTranslator implements Translator<Image, Image> {

    /**
     * 默认输入尺寸（模型支持任意尺寸输入，但建议不超过 1024）
     */
    private static final int DEFAULT_MAX_SIZE = 1024;

    /**
     * 原始图像宽度
     */
    private int originalWidth;

    /**
     * 原始图像高度
     */
    private int originalHeight;

    /**
     * 最大输入尺寸限制（防止内存溢出）
     */
    private int maxSize = DEFAULT_MAX_SIZE;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("FastNeuralStyle 输入图像尺寸: {}x{}", originalWidth, originalHeight);
        }

        // 转换为 NDArray (HWC)
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        // 如果图像过大，按比例缩放
        int maxDim = Math.max(originalWidth, originalHeight);
        if (maxDim > maxSize) {
            float scale = (float) maxSize / maxDim;
            int newWidth = Math.round(originalWidth * scale);
            int newHeight = Math.round(originalHeight * scale);
            array = NDImageUtils.resize(array, newWidth, newHeight, Image.Interpolation.BICUBIC);
            if (log.isDebugEnabled()) {
                log.debug("缩放图像: {}x{} -> {}x{}", originalWidth, originalHeight, newWidth, newHeight);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Step 1 - HWC 数组: {}", array.getShape());
        }

        // HWC -> CHW (transpose)
        array = array.transpose(2, 0, 1);
        if (log.isDebugEnabled()) {
            log.debug("Step 2 - CHW 数组: {}", array.getShape());
        }

        // 添加 batch 维度: CHW -> NCHW [1, 3, H, W]
        array = array.expandDims(0);
        if (log.isDebugEnabled()) {
            log.debug("Step 3 - NCHW 数组: {}", array.getShape());
        }

        // 转换为 float32（输入范围 [0, 255]，无需归一化）
        array = array.toType(DataType.FLOAT32, false);
        if (log.isDebugEnabled()) {
            log.debug("Step 4 - float32 NCHW: {}", array.getShape());
        }

        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("FastNeuralStyle 模型输出: {}", output.getShape());
        }

        // NCHW -> CHW: 移除 batch 维度
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
            if (log.isDebugEnabled()) {
                log.debug("移除 batch 维度后: {}", output.getShape());
            }
        }

        // CHW -> HWC (transpose back)
        output = output.transpose(1, 2, 0);
        if (log.isDebugEnabled()) {
            log.debug("HWC 格式: {}", output.getShape());
        }

        // clip 到 [0, 255]
        output = output.clip(0, 255);

        // 转换为 uint8
        output = output.toType(DataType.UINT8, false);

        // 构建图像
        Image result = ImageFactory.getInstance().fromNDArray(output);

        // 如果输入被缩放过，恢复原始尺寸
        int maxDim = Math.max(originalWidth, originalHeight);
        if (maxDim > maxSize) {
            if (log.isDebugEnabled()) {
                log.debug("恢复原始尺寸: {}x{} -> {}x{}", result.getWidth(), result.getHeight(), originalWidth, originalHeight);
            }
            NDArray resizedArray = NDImageUtils.resize(
                    result.toNDArray(ctx.getNDManager()),
                    originalWidth,
                    originalHeight,
                    Image.Interpolation.BICUBIC
            );
            result = ImageFactory.getInstance().fromNDArray(resizedArray);
        }

        if (log.isDebugEnabled()) {
            log.debug("FastNeuralStyle 输出图像: {}x{}", result.getWidth(), result.getHeight());
        }

        return result;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     * 设置最大输入尺寸
     *
     * @param maxSize 最大尺寸（像素）
     * @return 当前实例
     */
    public FastNeuralStyleTranslator setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    /**
     * 获取最大输入尺寸
     *
     * @return 最大尺寸
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 获取原始图像尺寸
     *
     * @return [width, height]
     */
    public int[] getOriginalSize() {
        return new int[]{originalWidth, originalHeight};
    }
}