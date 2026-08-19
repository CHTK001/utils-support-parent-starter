package com.chua.deeplearning.support.onnx.depth;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
 * Depth-Anything V2 ONNX                 
 * <p>
 * Depth-Anything V2                                   
 *       monocular depth estimation                                
 *       single RGB image -> depth map                                   
 * </p>
 * <p>
 *      : 518x518 RGB  ImageNet normalize
 *      : [1, 3, 518, 518] -> [1, 1, H/14, W/14]                
 *      : Image                       
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DepthAnythingTranslator implements Translator<Image, Image> {

    /** 模型尺寸 */
    /** Model_size */
    private static final int MODEL_SIZE = 518;
    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();

        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        array = NDImageUtils.resize(array, MODEL_SIZE, MODEL_SIZE, Image.Interpolation.BICUBIC);

        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }

        array = array.transpose(2, 0, 1).div(255.0f);

        NDArray mean = manager.create(MEAN, new Shape(3, 1, 1));
        NDArray std = manager.create(STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        array = array.expandDims(0);

        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray depth = list.singletonOrThrow();

        if (depth.getShape().dimension() > 3 && depth.getShape().get(0) == 1) {
            depth = depth.squeeze(0);
        }
        if (depth.getShape().dimension() == 3 && depth.getShape().get(0) == 1) {
            depth = depth.squeeze(0);
        }

        NDArray min = depth.min();
        depth = depth.sub(min);
        NDArray max = depth.max();
        float maxValue = max.getFloat();
        if (maxValue <= 0f) {
            maxValue = 1f;
        }
        depth = depth.div(maxValue).mul(255.0f).clip(0, 255).toType(DataType.UINT8, false);

        int outH = (int) depth.getShape().get(0);
        int outW = (int) depth.getShape().get(1);
        byte[] values = depth.toByteArray();

        BufferedImage buf = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
        int idx = 0;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int gray = values[idx++] & 0xff;
                int rgb = (gray << 16) | (gray << 8) | gray;
                buf.setRGB(x, y, rgb);
            }
        }

        BufferedImage scaled = ImageUtils.resize(buf, width, height, org.opencv.imgproc.Imgproc.INTER_LINEAR);

        return ImageFactory.getInstance().fromImage(scaled);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
