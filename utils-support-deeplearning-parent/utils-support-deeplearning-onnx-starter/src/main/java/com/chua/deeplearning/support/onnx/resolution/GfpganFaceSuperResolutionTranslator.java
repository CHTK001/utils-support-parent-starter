package com.chua.deeplearning.support.onnx.resolution;

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
import com.chua.deeplearning.support.utils.ImageUtils;


/**
 * GFPGAN                            
 * <p>
 *              GFPGAN                                              
 * GFPGAN                                                 
 * <p>
 *                
 * -           float32       
 * -              [0, 1]
 * -             mean=[0.5, 0.5, 0.5], std=[0.5, 0.5, 0.5]
 * -           CHW       
 * <p>
 *                
 * -           [-1, 1]       
 * -                 [0, 255]
 * -           UINT8       
 * -           Image       
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2024/11/08
 */
public class GfpganFaceSuperResolutionTranslator implements Translator<Image, Image> {

    /**
     *                      
     */
    private static final int[] MIN_MAX = new int[]{-1, 1};

    /**
     *                
     */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};

    /**
     *                   
     */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};
    private static final int INPUT_SIZE = 512;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        NDManager manager = ctx.getNDManager();

        // ONNX Runtime 引擎不支持 NDImageUtils.resize（Rs engine 抛 Not implemented），
        // 改用 ImageUtils：短边缩放 + 中心裁剪到 512x512 + mean/std(0.5) 归一化，
        // 直接产出 [3,512,512] 张量，避免在 ONNX NDArray 上做张量运算。
        float[] pixels = ImageUtils.toTensorCenterCrop(input, INPUT_SIZE, MEAN, STD);
        NDArray array = manager.create(pixels, new Shape(3, INPUT_SIZE, INPUT_SIZE));

        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) throws Exception {
        NDArray array = list.get(0);

        // 输出可能是 [1,C,H,W]（带 batch）或 [C,H,W]，统一取 CHW
        long[] shape = array.getShape().getShape();
        if (shape.length == 4) {
            array = array.squeeze(0);
            shape = array.getShape().getShape();
        }
        if (shape.length != 3) {
            throw new IllegalStateException("GFPGAN 输出维度异常: " + java.util.Arrays.toString(shape));
        }
        int c = (int) shape[0];
        int h = (int) shape[1];
        int w = (int) shape[2];

        // 直接读像素（CHW, [-1,1]）构造 BufferedImage，避免依赖 engine 的 NDArray→Image
        float[] data = array.toFloatArray();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int channels = Math.min(c, 3);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int r = toU8(data[idx]);
                int g = toU8(data[idx + h * w]);
                int b = toU8(data[idx + 2 * h * w]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return ImageFactory.getInstance().fromImage(img);
    }

    /**
     * 将 [-1,1] 归一化值转为 0~255。
     *
     * @param v 归一化像素值
     * @return 0~255
     */
    private static int toU8(float v) {
        // GFPGAN 输出近似 [-1,1]，转换为 [0,255]
        float x = Math.max(-1f, Math.min(1f, v));
        return (int) Math.round((x + 1f) / 2f * 255f);
    }

    @Override
    public Batchifier getBatchifier() {
        //        STACK             
        return Batchifier.STACK;
    }
}

