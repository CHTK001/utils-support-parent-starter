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

        NDArray hwc = cropped.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (!hwc.getDataType().equals(DataType.FLOAT32)) {
            hwc = hwc.toType(DataType.FLOAT32, false);
        }

        // 归一化在 Java 侧完成，避免使用 transpose / div / sub / expandDims 等 NDArray 运算：
        // 这些运算在 NDArrayAdapter（ONNX Runtime 引擎的 NDArray 实现）下需要借助「替代引擎」
        // 转换（getAlternativeArray），而当前类路径只有 onnxruntime 与 tokenizers(Rust) 两个引擎，
        // Rust 引擎几乎未实现这些算子，会直接抛 UnsupportedOperationException。
        // 这里只保留 toNDArray / toFloatArray / create 这类各引擎都支持的基础能力。
        float[] pixels = hwc.toFloatArray();
        int h = cropped.getHeight();
        int w = cropped.getWidth();
        float[] nchw = new float[3 * h * w];
        int planeSize = h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int hwcIndex = (y * w + x) * 3;
                int spatial = y * w + x;
                for (int c = 0; c < 3; c++) {
                    float v = pixels[hwcIndex + c];
                    if (v > 1f) {
                        v = v / 255f;
                    }
                    nchw[c * planeSize + spatial] = (v - IMAGE_MEAN[c]) / IMAGE_STD[c];
                }
            }
        }

        // [1, C, H, W]
        NDArray array = ctx.getNDManager().create(nchw, new Shape(1, 3, h, w));

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

        // 不使用 squeeze/reshape 等运算：NDArrayAdapter 下这些算子需要「替代引擎」转换而当前环境没有，
        // 会抛 UnsupportedOperationException 甚至 StackOverflowError。特征向量取扁平结果即可，
        // [1, N] 与 [N] 对后续余弦相似度等价。
        if (!output.getDataType().equals(DataType.FLOAT32)) {
            output = output.toType(DataType.FLOAT32, false);
        }
        return output.toFloatArray();
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
