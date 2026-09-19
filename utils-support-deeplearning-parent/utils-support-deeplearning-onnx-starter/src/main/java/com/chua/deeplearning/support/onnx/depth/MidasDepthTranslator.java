package com.chua.deeplearning.support.onnx.depth;

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
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import java.awt.image.BufferedImage;


/**
 * midas
 * <p>
 * midas
 * </p>
 *
 * @author CH
 * @since 2025-01-20
 */
@Slf4j
public class MidasDepthTranslator implements Translator<Image, Image> {

    /** 模型图像尺寸 */
    private static final int MODEL_IMAGE_SIZE = 256;
    
    /**
     *             
     */
    protected Batchifier batchifier = Batchifier.STACK;

    /**
     *                   
     */
    private int width;

    /**
     *                   
     */
    private int height;

    /**
     *                          512
     */
    private int detectResolution = 512;

    /**
     *                          512
     */
    private int imageResolution = 512;

    /**
     *                   
     */
    public MidasDepthTranslator() {
        this(MODEL_IMAGE_SIZE, MODEL_IMAGE_SIZE);
    }

    /**
     *             
     *
     * @param detectResolution                
     * @param imageResolution                 
     */
    public MidasDepthTranslator(int detectResolution, int imageResolution) {
        this.detectResolution = detectResolution;
        this.imageResolution = imageResolution;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();

        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, detectResolution, detectResolution, Image.Interpolation.BICUBIC);

        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        
        //              [-1, 1]
        array = array.div(127.5f).sub(1.0f);
        //           CHW       
        array = array.transpose(2, 0, 1);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDManager manager = ctx.getNDManager();

        NDArray depthPt = list.singletonOrThrow();
        if (depthPt.getShape().dimension() > 2 && depthPt.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            depthPt = depthPt.squeeze(0);
        }
        
        //                       [0, 255]
        NDArray min = depthPt.min();
        depthPt = depthPt.sub(min);
        NDArray max = depthPt.max();
        float maxValue = max.getFloat();
        if (maxValue <= 0f) {
            maxValue = 1f;
        }
        depthPt = depthPt.div(maxValue);
        depthPt = depthPt.mul(255.0).clip(0, 255).toType(DataType.UINT8, false);

        int outHeight = (int) depthPt.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        int outWidth = (int) depthPt.getShape().get(1);
        byte[] values = depthPt.toByteArray();
        BufferedImage bufferedImage = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_3BYTE_BGR);
        int index = 0;
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int gray = values[index++] & 0xff;
                int rgb = (gray << 16) | (gray << 8) | gray;
                bufferedImage.setRGB(x, y, rgb);
            }
        }

        BufferedImage scaled = ImageUtils.resize(bufferedImage, width, height, org.opencv.imgproc.Imgproc.INTER_LINEAR);

        return ImageFactory.getInstance().fromImage(scaled);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return batchifier;
    }
}

