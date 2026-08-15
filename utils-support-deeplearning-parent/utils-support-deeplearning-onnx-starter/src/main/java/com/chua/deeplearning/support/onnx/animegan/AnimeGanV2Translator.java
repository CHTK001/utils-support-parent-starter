package com.chua.deeplearning.support.onnx.animegan;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * AnimeGANv2 Translator
 * <p>
 * AnimeGANv2 动漫风格迁移，支持以下风格：
 * <ul>
 *   <li>Hayao - 宫崎骏风格（来自 AnimeGANv2 原始权重）</li>
 *   <li>Shinkai - 新海诚风格（来自 AnimeGANv2 原始权重）</li>
 *   <li>Paprika - 今敏/红辣椒风格（来自 AnimeGANv2 原始权重）</li>
 *   <li>Face Portrait V2 - 人像动漫化风格（来自 bryandlee/animegan2-pytorch）</li>
 * </ul>
 * <p>
 * 模型输入: NHWC [1, 512, 512, 3] float32，RGB 归一化到 [-1, 1]
 * 模型输出: NHWC [1, 512, 512, 3] float32，RGB 归一化到 [-1, 1]
 * <p>
 * 参考: https://github.com/bryandlee/animegan2-pytorch
 * 参考: https://github.com/TachibanaYoshino/AnimeGANv2
 *
 * @author CH
 * @version 4.0.0.42
 * @since 2026/8/15
 */
@Slf4j
public class AnimeGanV2Translator implements Translator<Image, Image> {

    /**
     * 输入尺寸（AnimeGANv2 固定 512x512）
     */
    private static final int INPUT_SIZE = 512;

    /**
     * 原始图像宽度
     */
    private int originalWidth;

    /**
     * 原始图像高度
     */
    private int originalHeight;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2 输入图像尺寸: {}x{}", originalWidth, originalHeight);
        }

        // 转换为 NDArray (HWC)
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        // 缩放到 512x512
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE, Image.Interpolation.BICUBIC);
        if (log.isDebugEnabled()) {
            log.debug("Step 1 - 缩放到 512x512: {}", array.getShape());
        }

        // 归一化到 [-1, 1]: (pixel / 255) * 2 - 1
        array = array.div(255.0f).mul(2.0f).sub(1.0f);
        if (log.isDebugEnabled()) {
            log.debug("Step 2 - 归一化到 [-1, 1]: {}", array.getShape());
        }

        // 确保是 3 通道 (HWC)
        if (array.getShape().dimension() == 2) {
            array = array.expandDims(-1);
        }

        // 添加 batch 维度: HWC -> NHWC [1, 512, 512, 3]
        array = array.expandDims(0);
        if (log.isDebugEnabled()) {
            log.debug("Step 3 - NHWC: {}", array.getShape());
        }

        // 转换为 float32
        array = array.toType(DataType.FLOAT32, false);
        if (log.isDebugEnabled()) {
            log.debug("Step 4 - float32 NHWC: {}", array.getShape());
        }

        // 验证形状
        Shape shape = array.getShape();
        if (shape.get(0) != 1 || shape.get(1) != INPUT_SIZE || shape.get(2) != INPUT_SIZE || shape.get(3) != 3) {
            log.warn("AnimeGANv2 输入形状不匹配: 期望 [1, {}, {}, 3], 实际: {}", INPUT_SIZE, INPUT_SIZE, shape);
        }

        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2 模型输出: {}", output.getShape());
        }

        // NHWC -> HWC: 移除 batch 维度
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
            if (log.isDebugEnabled()) {
                log.debug("移除 batch 维度后: {}", output.getShape());
            }
        }

        // 反归一化: [-1, 1] -> [0, 255]
        output = output.add(1.0f).mul(127.5f);
        if (log.isDebugEnabled()) {
            log.debug("反归一化后: {}", output.getShape());
        }

        // clip 到 [0, 255]
        output = output.clip(0, 255);

        // 转换为 uint8
        output = output.toType(DataType.UINT8, false);

        // 构建图像
        Image result = ImageFactory.getInstance().fromNDArray(output);

        // 恢复原始尺寸
        if (result.getWidth() != originalWidth || result.getHeight() != originalHeight) {
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
            log.debug("AnimeGANv2 输出图像: {}x{}", result.getWidth(), result.getHeight());
        }

        return result;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     * 获取输入尺寸
     *
     * @return 输入尺寸
     */
    public int getInputSize() {
        return INPUT_SIZE;
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