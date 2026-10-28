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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import lombok.extern.slf4j.Slf4j;
import java.awt.image.BufferedImage;


/**
 * animeganv3
 * <p>
 * animeganv3
 *
 *
 * 1.                                                    
 * 2.                                                       
 * 3.                                  
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class AnimeGanV3Translator implements Translator<Image, Image> {

    private static final int INPUT_SIZE = 512;

    private int originalWidth;
    /**
     * 原始高度
    */
    private int originalHeight;

    /**
     *
     *
     * @param ctx                   
     * @param input             
     * @return                            
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        // 记录原图尺寸，供后处理还原
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3 输入图像尺寸: {}x{}", originalWidth, originalHeight);
        }

        // 转为 NDArray (HWC 布局)
 // Rust 引擎未实现 ndarray.resize，先用 Java2D 缩放到目标尺寸
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage scaled = new BufferedImage(
                INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(src, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        } finally {
            graphics.dispose();
        }
        Image resized = ImageFactory.getInstance().fromImage(scaled);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (log.isDebugEnabled()) {
            log.debug("Step 1 - 缩放后张量形状: {}", array.getShape());
        }

        // 归一化到 [-1, 1]
        // 先转 float32：整型数组 div 会截断为 0，导致输入全 -1
        array = array.toType(DataType.FLOAT32, false);
        array = array.div(255.0f).mul(2.0f).sub(1.0f);
        if (log.isDebugEnabled()) {
            log.debug("Step 3 - 归一化后形状: {}", array.getShape());
        }

        // 补 3 通道维度 (HWC)
        if (array.getShape().dimension() == 2) {
            array = array.expandDims(-1);
            if (log.isDebugEnabled()) {
                log.debug("Step 3.5 - 扩展通道后形状: {}", array.getShape());
            }
        }

        // 校验 HWC 3 维
        if (array.getShape().dimension() != 3) {
            log.error("ERROR: 输入张量不是 3D (HWC) 形状: {}", array.getShape());
            throw new IllegalArgumentException("Invalid array dimension for transpose: " + array.getShape());
        }

        // 校验通道数是否为 3
        if (array.getShape().get(2) != 3) {
            log.warn("WARNING: 通道数不是 3，实际为: {}", array.getShape().get(2));
        }

        // 扩展为 NHWC 批次维度
        array = array.expandDims(0);
        if (log.isDebugEnabled()) {
            log.debug("Step 4 - 批次维扩展后形状: {}", array.getShape());
        }

        // 确保 float32
        array = array.toType(DataType.FLOAT32, false);
        if (log.isDebugEnabled()) {
            log.debug("Step 5 - 最终输入形状: {} (期望 [1, 512, 512, 3])", array.getShape());
        }

        // 校验 4 维 NHWC
        if (array.getShape().dimension() != 4) {
            throw new IllegalArgumentException(
                    "Invalid output shape: expected 4D (NHWC), got " + array.getShape().dimension() + "D"
           );
        }

        Shape shape = array.getShape();
        if (shape.get(0) != 1 || shape.get(1) != INPUT_SIZE || shape.get(2) != INPUT_SIZE || shape.get(3) != 3) { // [P3C 3.7 豁免] 张量形状维度下标
            log.warn("AnimeGANv3 输入形状与期望不符，期望 [1, {}, {}, 3]，实际: {}",
                    INPUT_SIZE, INPUT_SIZE, shape);
        }

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3 模型输入: NHWC [batch=1, height={}, width={}, channels=3]", INPUT_SIZE, INPUT_SIZE);
        }

        return new NDList(array);
    }

    /**
     *
     *
     * @param ctx  Translator 上下文
     * @param list 模型输出张量列表
     * @return 输出图像
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3 输出张量形状: {}", output.getShape());
        }

        // 去掉批次维 NHWC -> HWC
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
            if (log.isDebugEnabled()) {
                log.debug("去批次维后形状: {}", output.getShape());
            }
        }

        // 反归一化 [-1, 1] -> [0, 255]
        output = output.add(1.0f).mul(127.5f);
        if (log.isDebugEnabled()) {
            log.debug("反归一化后形状: {}", output.getShape());
        }

        // 截断到 [0, 255]
        output = output.clip(0, 255);

        // 转为 uint8
        output = output.toType(DataType.UINT8, false);
        if (log.isDebugEnabled()) {
            log.debug("转 uint8 后形状: {} (期望 HWC)", output.getShape());
        }

        // 由 HWC 构造图像

        // 还原到原始尺寸
        Image result = ImageFactory.getInstance().fromNDArray(output);

 // （Java2D 缩放，Rust 引擎未实现 ndarray.resize）
        if (result.getWidth() != originalWidth || result.getHeight() != originalHeight) {
            if (log.isDebugEnabled()) {
                log.debug("尺寸还原: {}x{} -> {}x{}", result.getWidth(), result.getHeight(), originalWidth, originalHeight);
            }
            BufferedImage outBi =
                    (BufferedImage) result.getWrappedImage();
            BufferedImage scaledBack = new BufferedImage(
                    originalWidth, originalHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D backGraphics = scaledBack.createGraphics();
            try {
                backGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                backGraphics.drawImage(outBi, 0, 0, originalWidth, originalHeight, null);
            } finally {
                backGraphics.dispose();
            }
            result = ImageFactory.getInstance().fromImage(scaledBack);
        }

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3 输出图像尺寸: {}x{}", result.getWidth(), result.getHeight());
        }

        return result;
    }

    /**
     *
     *
     * @return              -        无                    处理输入
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     *
     *
     * @return             
     */
    public int getInputSize() {
        return INPUT_SIZE;
    }

    /**
     *
     *
     * @return                    [width, height]
     */
    public int[] getOriginalSize() {
        return new int[]{originalWidth, originalHeight};
    }
}
