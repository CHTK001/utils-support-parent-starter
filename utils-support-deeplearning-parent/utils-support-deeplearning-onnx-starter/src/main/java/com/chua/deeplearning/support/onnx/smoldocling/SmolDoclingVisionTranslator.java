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
import java.util.Arrays;


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
 * @since 2025/01/22
 */
@Slf4j
public class SmolDoclingVisionTranslator implements Translator<Image, SmolDoclingVisionOutput> {

    /**
     *
     *                                mask                       512x512
     */
    private static final int INPUT_IMAGE_SIZE = 512;

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("输入图像尺寸: {}x{}", input.getWidth(), input.getHeight());
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
            log.debug("pixel_values 形状: {}", array.getShape());
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

        log.info("构造 pixel_attention_mask: shape={}, 总元素数={}", maskShapeObj, totalSize);

        // 方案 1：直接 manager.create(data, shape) 构造布尔张量
        NDArray pixelAttentionMask;
        try {
            boolean[] boolData = new boolean[(int) totalSize];
            Arrays.fill(boolData, true);
            // 元素数必须与形状元素总数一致
            pixelAttentionMask = manager.create(boolData, maskShapeObj);
            log.info("方案 1 成功: 直接 create 布尔数组, shape={}, rank={}, dtype={}",
                    pixelAttentionMask.getShape(),
                    pixelAttentionMask.getShape().dimension(),
                    pixelAttentionMask.getDataType());
        } catch (Exception e) {
            log.warn("方案 1 失败: {}", e.getMessage(), e);
            // 方案 2：先用 ones 构造再转 bool
            try {
                NDArray onesArray = manager.ones(maskShapeObj);
                pixelAttentionMask = onesArray.gt(0).toType(DataType.BOOLEAN, false);
                // 确保 4 维
                if (pixelAttentionMask.getShape().dimension() != 4) {
                    pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                }
                log.info("方案 2 成功: 基于 ones 转布尔, shape={}, rank={}, dtype={}",
                        pixelAttentionMask.getShape(),
                        pixelAttentionMask.getShape().dimension(),
                        pixelAttentionMask.getDataType());
            } catch (Exception e2) {
                log.warn("方案 2 失败: {}", e2.getMessage(), e2);
                // 方案 3：先建 1 维数组再 reshape
                boolean[] boolData = new boolean[(int) totalSize];
                Arrays.fill(boolData, true);
                NDArray tempArray = manager.create(boolData);
                pixelAttentionMask = tempArray.reshape(maskShapeObj);
                // 校验数据类型为布尔
                if (!pixelAttentionMask.getDataType().equals(DataType.BOOLEAN)) {
                    pixelAttentionMask = pixelAttentionMask.toType(DataType.BOOLEAN, false);
                    // 保持 4 维
                    if (pixelAttentionMask.getShape().dimension() != 4) {
                        pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                    }
                }
                log.info("方案 3 成功: 一维数组 reshape, shape={}, rank={}, dtype={}",
                        pixelAttentionMask.getShape(),
                        pixelAttentionMask.getShape().dimension(),
                        pixelAttentionMask.getDataType());
            }
        }

        // 记录最终形状用于校验
        Shape finalShape = pixelAttentionMask.getShape();
        long finalRank = finalShape.dimension();
        DataType finalDtype = pixelAttentionMask.getDataType();

        log.info("pixel_attention_mask 最终形状: shape={}, rank={}, dtype={}",
                finalShape, finalRank, finalDtype);

        if (finalRank != 4) {
            log.error("pixel_attention_mask rank 不是 4: 实际 {}, shape={}",
                    finalRank, finalShape);
            // 维度不符时尝试重新 reshape
            try {
                pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
                log.info("重新 reshape 后: shape={}, rank={}",
                        pixelAttentionMask.getShape(),
                        pixelAttentionMask.getShape().dimension());
                if (pixelAttentionMask.getShape().dimension() != 4) {
                    throw new IllegalStateException(
                            String.format("无法将 pixel_attention_mask 调整为 4 维: rank=%d, shape=%s",
                                    pixelAttentionMask.getShape().dimension(),
                                    pixelAttentionMask.getShape()));
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        String.format("调整 pixel_attention_mask 失败: rank=%d, shape=%s, error=%s",
                                finalRank, finalShape, e.getMessage()), e);
            }
        }

 // 布尔值校验
        if (!finalDtype.equals(DataType.BOOLEAN)) {
            log.warn("pixel_attention_mask 数据类型不是 BOOLEAN: {}, 将自动转换", finalDtype);
            Shape shapeBeforeTypeChange = pixelAttentionMask.getShape();
            pixelAttentionMask = pixelAttentionMask.toType(DataType.BOOLEAN, false);
            // 类型转换可能改变维度，必要时重新 reshape
            if (pixelAttentionMask.getShape().dimension() != shapeBeforeTypeChange.dimension() ||
                pixelAttentionMask.getShape().dimension() != 4) {
                log.warn("类型转换改变了形状: {} -> {}，将重新 reshape",
                        shapeBeforeTypeChange, pixelAttentionMask.getShape());
                pixelAttentionMask = pixelAttentionMask.reshape(maskShapeObj);
            }
        }

        // 期望形状 [1, 1, 512, 512]
        finalShape = pixelAttentionMask.getShape();
        finalRank = finalShape.dimension();
        finalDtype = pixelAttentionMask.getDataType();

        log.info("pixel_attention_mask 规整后: shape={}, rank={}, dtype={}",
                finalShape, finalRank, finalDtype);

        // 必须为 4 维且与目标形状一致
        if (finalRank != 4 || !finalShape.equals(maskShapeObj)) {
            log.warn("pixel_attention_mask 形状不符合预期: 期望 {}, 实际 {}",
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
            log.info("expandDims 后形状: shape={}", pixelAttentionMask.getShape());
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
        log.info("reshape 后形状: {}", pixelAttentionMask.getShape());
                    }
                }
            } catch (Exception e) {
            log.error("构造 pixel_attention_mask 失败: {}", e.getMessage(), e);
                throw new IllegalStateException(
                        String.format("构造 pixel_attention_mask 失败: shape=%s, rank=%s, 错误: %s",
                                maskShapeObj, pixelAttentionMask.getShape(), e.getMessage()), e);
            }
        }

        // 最终校验：必须为 4 维且与目标形状一致
        finalShape = pixelAttentionMask.getShape();
        finalRank = finalShape.dimension();
        finalDtype = pixelAttentionMask.getDataType();

        if (finalRank != 4 || !finalShape.equals(maskShapeObj)) {
            throw new IllegalStateException(
                    String.format("pixel_attention_mask 校验失败: 期望 shape=%s, 实际 shape=%s, rank=%d, dtype=%s",
                            maskShapeObj, finalShape, finalRank, finalDtype));
        }

        log.info("Vision 输入: pixel_values shape={}, pixel_attention_mask shape={}, rank={}, dtype={}",
                array.getShape(), finalShape, finalRank, finalDtype);

        return new NDList(array, pixelAttentionMask);
    }

    /**
     * 处理输出
    */
    @Override
    public SmolDoclingVisionOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Vision 模型输出 {} 个张量", list.size());
        }

        if (list.isEmpty()) {
            log.warn("Vision 模型输出为空");
            return null;
        }

        //                   
        NDArray imageFeatures = list.getFirst();
        long[] outputShape = imageFeatures.getShape().getShape();

        if (log.isDebugEnabled()) {
        log.debug("Vision 输出: image_features shape={}", imageFeatures.getShape());
        }

 // ndarray           float
        return new SmolDoclingVisionOutput(imageFeatures);
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        //                   
        return null;
    }
}
