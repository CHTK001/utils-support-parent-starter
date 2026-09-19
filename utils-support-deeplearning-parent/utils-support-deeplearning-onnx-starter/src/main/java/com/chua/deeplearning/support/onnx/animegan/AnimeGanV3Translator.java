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

    /**
     *                   
     */
    private static final int INPUT_SIZE = 512;

    /**
     *                                           
     */
    private int originalWidth;
    /** 原始高度 */
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
        //                   
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : {}x{}", originalWidth, originalHeight);
        }

        //           NDArray (HWC       )
 // Rust 引擎未实现 ndarray.resize，先用 Java2D 缩放到目标尺寸
        java.awt.image.BufferedImage src = (java.awt.image.BufferedImage) input.getWrappedImage();
        java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                INPUT_SIZE, INPUT_SIZE, java.awt.image.BufferedImage.TYPE_INT_RGB);
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
            log.debug("Step 1 -                : {}", array.getShape());
        }

        //                             [-1, 1]
        // 先转 float32：整型数组 div 会截断为 0，导致输入全 -1
        array = array.toType(DataType.FLOAT32, false);
        array = array.div(255.0f).mul(2.0f).sub(1.0f);
        if (log.isDebugEnabled()) {
            log.debug("Step 3 -                   : {}", array.getShape());
        }

        //           3     (HWC)
        if (array.getShape().dimension() == 2) {
            array = array.expandDims(-1);
            if (log.isDebugEnabled()) {
                log.debug("Step 3.5 -                            : {}", array.getShape());
            }
        }

        //                       HWC 3      
        if (array.getShape().dimension() != 3) {
            log.error("ERROR:        3D                               : {}", array.getShape());
            throw new IllegalArgumentException("Invalid array dimension for transpose: " + array.getShape());
        }

        //                    3
        if (array.getShape().get(2) != 3) {
            log.warn("WARNING:        3                      : {}", array.getShape().get(2));
        }

        //                       HWC     NHWC                               HWC          
        array = array.expandDims(0);
        if (log.isDebugEnabled()) {
            log.debug("Step 4 -                            : {}", array.getShape());
        }

        //           float32
        array = array.toType(DataType.FLOAT32, false);
        if (log.isDebugEnabled()) {
            log.debug("Step 5 -             : {} (       [1, 512, 512, 3])", array.getShape());
        }

        //                   
        if (array.getShape().dimension() != 4) {
            throw new IllegalArgumentException(
                    "Invalid output shape: expected 4D (NHWC), got " + array.getShape().dimension() + "D"
           );
        }

        Shape shape = array.getShape();
        if (shape.get(0) != 1 || shape.get(1) != INPUT_SIZE || shape.get(2) != INPUT_SIZE || shape.get(3) != 3) { // [P3C 3.7 豁免] 张量形状维度下标
            log.warn("AnimeGANv3                                     :        [1, {}, {}, 3],       : {}",
                    INPUT_SIZE, INPUT_SIZE, shape);
        }

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : NHWC [batch=1, height={}, width={}, channels=3]", INPUT_SIZE, INPUT_SIZE);
        }

        return new NDList(array);
    }

    /**
     *                      
     *
     * @param ctx                   
     * @param list                         
     * @return                      
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : {}", output.getShape());
        }

        //                    NHWC -> HWC
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
            if (log.isDebugEnabled()) {
                log.debug("                           : {}", output.getShape());
            }
        }

        //                    [-1, 1]     [0, 255]
        output = output.add(1.0f).mul(127.5f);
        if (log.isDebugEnabled()) {
            log.debug("                     : {}", output.getShape());
        }

        //                    [0, 255]
        output = output.clip(0, 255);

        //                       uint8
        output = output.toType(DataType.UINT8, false);
        if (log.isDebugEnabled()) {
            log.debug("                           : {} (       HWC       )", output.getShape());
        }

        //                 HWC                         

        //             
        Image result = ImageFactory.getInstance().fromNDArray(output);

 // （Java2D 缩放，Rust 引擎未实现 ndarray.resize）
        if (result.getWidth() != originalWidth || result.getHeight() != originalHeight) {
            if (log.isDebugEnabled()) {
                log.debug("                      {}x{}     {}x{}", result.getWidth(), result.getHeight(), originalWidth, originalHeight);
            }
            java.awt.image.BufferedImage outBi =
                    (java.awt.image.BufferedImage) result.getWrappedImage();
            java.awt.image.BufferedImage scaledBack = new java.awt.image.BufferedImage(
                    originalWidth, originalHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
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
            log.debug("AnimeGANv3                               : {}x{}", result.getWidth(), result.getHeight());
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
