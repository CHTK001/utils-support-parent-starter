package com.chua.deeplearning.support.onnx.feature;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NDArrayUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * CLIP                      
 * <p>
 * CLIP vit-B/16     vit-B/32 Vision
 * CLIP-vit-B-16-镜像     CLIP-vit-B-32-镜像
 * </p>
 * <p>
 *                  
 * 1.                                224       
 * 2.                 224x224
 * 3.           RGB       
 * 4.              [0, 1]       
 * 5.                                        
 * 6.           NCHW       
 * </p>
 * <p>
 * 打开AI CLIP
 * - 镜像_mean: [0.48145466, 0.4578275, 0.40821073]
 * - 镜像_std: [0.26862954, 0.26130258, 0.27577711]
 * - crop_大小: 224x224
 * - resample: BICUBIC
 * </p>
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
public class ClipImageFeatureTranslator implements Translator<Image, float[]> {

    /**
     * CLIP                            
     * 打开AI CLIP
     */
    private static final float[] IMAGE_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};

    /**
     * CLIP                               
     * 打开AI CLIP
     */
    private static final float[] IMAGE_STD = {0.26862954f, 0.26130258f, 0.27577711f};

    /**
     *                         
     */
    private static final int IMAGE_SIZE = 224;

    /**
     *                
     * <p>
     * CLIP                                           
     * </p>
     *
     * @param ctx                   
     */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) {
        log.debug("[CLIP][      ] Translator             ");
    }

    /**
     *                   
     * <p>
     *                                                 
     * 1.                                      224   
     * 2.                 224x224
     * 3.              [0, 1]
     * 4.                                        
     * 5.           NCHW                            
     * </p>
     *
     * @param ctx                     
     * @param input             
     * @return              NDList                   [1, 3, 224, 224]          
     * @throws Exception                         
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) throws Exception {
        // 缩放与裁剪一律走 DJL Image API（BufferedImage 实现），不使用 NDImageUtils.resize/centerCrop：
        // 后两者内部走各引擎的 NDArrayEx（如 ai.djl.engine.rust.RsNDArrayEx.resize、
        // 部分 onnx 引擎实现），在缺实现时会直接抛 UnsupportedOperationException。Image API 与引擎无关。
        int width = input.getWidth();
        int height = input.getHeight();
        float percent = (float) IMAGE_SIZE / Math.min(width, height);
        int resizedWidth = Math.round(width * percent);
        int resizedHeight = Math.round(height * percent);
        Image resized = input.resize(resizedWidth, resizedHeight, false);
        int cropX = Math.max(0, (resizedWidth - IMAGE_SIZE) / 2);
        int cropY = Math.max(0, (resizedHeight - IMAGE_SIZE) / 2);
        Image cropped = resized.getSubImage(cropX, cropY, IMAGE_SIZE, IMAGE_SIZE);

        NDArray array = cropped.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //                       FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }

        // HWC -> CHW                 [0, 1]
        array = array.transpose(2, 0, 1).div(255f);

        //             (x - mean) / std
        NDArray mean = ctx.getNDManager().create(IMAGE_MEAN, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(IMAGE_STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        //                    [C, H, W] -> [1, C, H, W]
        array = array.expandDims(0);

        return new NDList(array);
    }

    /**
     *                   
     * <p>
     *                                                       
     * CLIP vit-B/16     vit-B/32           512
     * </p>
     *
     * @param ctx                    
     * @param list                 nd列表
     * @return                         
     */
    @Override
    @Nonnull
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.getFirst();

        //                                           
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            output = output.squeeze(0);
        }
        return NDArrayUtils.safeToFloatArray(output);
    }

    /**
     *                   
     *
     * @return null                     
     */
    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return null;
    }
}
