package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;


/**
   * smoldocling Vision Translator
 * <p>
 *                                                                    Vision                
 * <p>
 *                
 * -                                   (512x512)
 * -                      
   * -           5          : [批量, num_镜像, 通道, height, width]
   * -        pixel_attention_mask: [批量, num_镜像, height, width] (512x512)
 * <p>
 *                
 * -                   
 *
 * @author CH
   * @版本 4.0.0.32
 * @since 2025/01/22
 */
@Slf4j
public class SmolDoclingVisionTranslator implements Translator<Image, SmolDoclingVisionOutput> {

    /**
     *                         
     *                                mask                       512x512
     */
    private static final int INPUT_IMAGE_SIZE = 512;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        : {}x{}", input.getWidth(), input.getHeight());
        }

        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        //                                        
        array = NDImageUtils.resize(array, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE);

        //              [0, 1]
        array = array.div(255.0f);

        //           CHW       : [3, height, width]
 // nd镜像工具.normalize                       CHW
        array = array.transpose(2, 0, 1);

 // 镜像net
        //     CHW                         
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        array = NDImageUtils.normalize(array, mean, std);

 // 5          : [批量, num_镜像, 通道, height, width]
        //             : [3, height, width] (CHW)
 // num_镜像       : [1, 3, height, width]
        array = array.expandDims(0);
 // 批量       : [1, 1, 3, height, width]
        array = array.expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("pixel_values                : {}", array.getShape());
        }

        //        pixel_attention_mask                      true   bool          
 // pixel_attention_mask                    pixel_值                   512x512
 // 4     mask: [批量, num_镜像, height, width]
        long batchSize = 1; //           1
        long numImages = 1; //           1
        long maskHeight = INPUT_IMAGE_SIZE; //                 mask
        long maskWidth = INPUT_IMAGE_SIZE; //                 mask

        //                       mask: [batch, num_images, height, width] = [1, 1, 512, 512]
        Shape maskShapeObj = new Shape(batchSize, numImages, maskHeight, maskWidth);
        long totalSize = batchSize * numImages * maskHeight * maskWidth;

        log.info("       pixel_attention_mask:             ={},          ={}", maskShapeObj, totalSize);

        //       1                manager.create(data, shape)                                  
        NDArray pixelAttentionMask;
        try {
            boolean[] boolData = new boolean[(int) totalSize];
            java.util.Arrays.fill(boolData, true);
            //                                        
            pixelAttentionMask = manager.create(boolData, maskShapeObj);
            log.info("      1      :                                  , shape={}, rank={}, dtype={}",
                    pixelAttentionMask.getShape(),
                    pixelAttentionMask.getShape().dimension(),
                    pixelAttentionMask.getDataType());
        } catch (Exception e) {
            log.warn("      1      : {}", e.getMessage());
            //       2          ones                          bool
            try {
                NDArray onesArray = manager.ones(maskShapeObj);
                pixelAttentionMask = onesArray.gt(0).toType(DataType.BOOLEAN, false);
                //                   
                if (pixelAttentionMask.getShape().dimension() != 4) {
                    pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                }
                log.info("      2      :        ones       , shape={}, rank={}, dtype={}",
                        pixelAttentionMask.getShape(),
                        pixelAttentionMask.getShape().dimension(),
                        pixelAttentionMask.getDataType());
            } catch (Exception e2) {
                log.warn("      2      : {}", e2.getMessage());
                //       3         1               reshape
                boolean[] boolData = new boolean[(int) totalSize];
                java.util.Arrays.fill(boolData, true);
                NDArray tempArray = manager.create(boolData);
                pixelAttentionMask = tempArray.reshape(maskShapeObj);
                //                         
                if (!pixelAttentionMask.getDataType().equals(DataType.BOOLEAN)) {
                    pixelAttentionMask = pixelAttentionMask.toType(DataType.BOOLEAN, false);
                    //                         
                    if (pixelAttentionMask.getShape().dimension() != 4) {
                        pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                    }
                }
                log.info("      3      :          reshape, shape={}, rank={}, dtype={}",
                        pixelAttentionMask.getShape(),
                        pixelAttentionMask.getShape().dimension(),
                        pixelAttentionMask.getDataType());
            }
        }

        //                                           
        Shape finalShape = pixelAttentionMask.getShape();
        long finalRank = finalShape.dimension();
        DataType finalDtype = pixelAttentionMask.getDataType();

        log.info("pixel_attention_mask             : shape={}, rank={}, dtype={}",
                finalShape, finalRank, finalDtype);

        if (finalRank != 4) {
            log.error("pixel_attention_mask rank       :        4          {}, shape={}",
                    finalRank, finalShape);
            //                            reshape
            try {
                pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                log.info("      reshape   : shape={}, rank={}",
                        pixelAttentionMask.getShape(),
                        pixelAttentionMask.getShape().dimension());
                if (pixelAttentionMask.getShape().dimension() != 4) {
                    throw new IllegalStateException(
                            String.format("                            pixel_attention_mask: rank=%d, shape=%s",
                                    pixelAttentionMask.getShape().dimension(),
                                    pixelAttentionMask.getShape()));
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        String.format("                            pixel_attention_mask: rank=%d, shape=%s, error=%s",
                                finalRank, finalShape, e.getMessage()), e);
            }
        }

 // 布尔值
        if (!finalDtype.equals(DataType.BOOLEAN)) {
            log.warn("pixel_attention_mask                    BOOLEAN: {},             ", finalDtype);
            Shape shapeBeforeTypeChange = pixelAttentionMask.getShape();
            pixelAttentionMask = pixelAttentionMask.toType(DataType.BOOLEAN, false);
            //                            reshape
            if (pixelAttentionMask.getShape().dimension() != shapeBeforeTypeChange.dimension() ||
                pixelAttentionMask.getShape().dimension() != 4) {
                log.warn("                           : {} -> {},       reshape",
                        shapeBeforeTypeChange, pixelAttentionMask.getShape());
                pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
            }
        }

        //                                         [1, 1, 512, 512]
        finalShape = pixelAttentionMask.getShape();
        finalRank = finalShape.dimension();
        finalDtype = pixelAttentionMask.getDataType();

        log.info("pixel_attention_mask          : shape={}, rank={}, dtype={}",
                finalShape, finalRank, finalDtype);

        //                   4                                 
        if (finalRank != 4 || !finalShape.equals(maskShapeObj)) {
            log.warn("pixel_attention_mask                :        {},        {}",
                    maskShapeObj, finalShape);

            try {
 // rank                 expanddims
                if (finalRank != 4) {
                    long[] currentShape = finalShape.getShape();
                    int currentRank = currentShape.length;

                    if (currentRank == 1) {
 // reshape     [maskheight, maskwidth]
                        NDArray hwArray = pixelAttentionMask.reshape(new Shape(maskHeight, maskWidth));
 // num_镜像       : [1, maskheight, maskwidth]
                        NDArray imgArray = hwArray.expandDims(0);
 // 批量       : [1, 1, maskheight, maskwidth]
                        pixelAttentionMask = imgArray.expandDims(0);
                    } else if (currentRank == 2) {
 // num_镜像     批量
                        // onMask.expandDims(0); // [1, maskHeight, maskWidth]
                        NDArray imgArray = pixelAttentionMask.expandDims(0);
                        // gArray.expandDims(0); // [1, 1, maskHeight, maskWidth]
                        pixelAttentionMask = imgArray.expandDims(0);
                    } else if (currentRank == 3) {
 // 批量
                        // onMask.expandDims(0); // [1, 1, maskHeight, maskWidth]
                        pixelAttentionMask = pixelAttentionMask.expandDims(0);
                    }
                    log.info("       expandDims          : shape={}", pixelAttentionMask.getShape());
                }

                //                             reshape
                finalShape = pixelAttentionMask.getShape();
                if (!finalShape.equals(maskShapeObj)) {
                    long[] shapeArray = finalShape.getShape();
                    long totalElements = 1;
                    for (long dim : shapeArray) {
                        totalElements *= dim;
                    }

                    //                                reshape
                    if (totalElements == totalSize) {
                        pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                        log.info("reshape                : {}", pixelAttentionMask.getShape());
                    }
                }
            } catch (Exception e) {
                log.error("       pixel_attention_mask             : {}", e.getMessage(), e);
                throw new IllegalStateException(
                        String.format("                            pixel_attention_mask:        %s,        %s,       : %s",
                                maskShapeObj, pixelAttentionMask.getShape(), e.getMessage()), e);
            }
        }

        //             
        finalShape = pixelAttentionMask.getShape();
        finalRank = finalShape.dimension();
        finalDtype = pixelAttentionMask.getDataType();

        if (finalRank != 4 || !finalShape.equals(maskShapeObj)) {
            throw new IllegalStateException(
                    String.format("pixel_attention_mask                   :        %s,        shape=%s, rank=%d, dtype=%s",
                            maskShapeObj, finalShape, finalRank, finalDtype));
        }

        log.info("Vision                   : pixel_values shape={}, pixel_attention_mask shape={}, rank={}, dtype={}",
                array.getShape(), finalShape, finalRank, finalDtype);

        return new NDList(array, pixelAttentionMask);
    }

    @Override
    /** 处理输出 */
    public SmolDoclingVisionOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("             Vision             : {}          ", list.size());
        }

        if (list.isEmpty()) {
            log.warn("Vision                   ");
            return null;
        }

        //                   
        NDArray imageFeatures = list.get(0);
        long[] outputShape = imageFeatures.getShape().getShape();

        if (log.isDebugEnabled()) {
            log.debug("Vision             : image_features shape={}", imageFeatures.getShape());
        }

 // ndarray           float
        return new SmolDoclingVisionOutput(imageFeatures);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        //                   
        return null;
    }
}
