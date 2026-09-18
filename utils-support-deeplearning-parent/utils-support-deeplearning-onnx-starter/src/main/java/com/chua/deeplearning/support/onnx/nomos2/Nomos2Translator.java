package com.chua.deeplearning.support.onnx.nomos2;

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
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;


/**
* 4xnomos2 ESRGAN
* <p>
*                                                                   
* <p>
*                
* -           float32       
* -              [0, 1]
* -           CHW          通道, Height, Width
* <p>
*                
* -           [0, 1]       
* -           [0, 255]
* -           UINT8       
* -           镜像
*
* @author CH
* @版本 4.0.0.32
* @since 2024/11/08
 */
@Slf4j
public class Nomos2Translator implements Translator<Image, Image> {

    /**
    *                                                    {@link NDList}   
    *
    * @param ctx translator上下文
    * @param input                       
    * @return NDList               
    * @throws Exception                
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        : {}x{}", input.getWidth(), input.getHeight());
        }

        NDManager manager = ctx.getNDManager();
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        int h = buffered.getHeight();
        int w = buffered.getWidth();

        // HWC -> CHW, [0, 255] -> [0, 1]，手动像素拷贝规避 ONNX NDArray 不支持的 transpose
        float[] pixels = new float[3 * h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = buffered.getRGB(x, y);
                int idx = y * w + x;
                pixels[idx] = ((argb >> 16) & 0xFF) / 255.0f;
                pixels[h * w + idx] = ((argb >> 8) & 0xFF) / 255.0f;
                pixels[2 * h * w + idx] = (argb & 0xFF) / 255.0f;
            }
        }
        NDArray array = manager.create(pixels, new Shape(1, 3, h, w));

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    /**
    *                                                  {@link Image}   
    *
    * @param ctx translator上下文
    * @param list nd列表
    * @return Image               
    * @throws Exception                
    */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        ");
        }

        NDArray array = list.getFirst();
        long[] shape = array.getShape().getShape();
        if (log.isDebugEnabled()) {
            log.debug("       shape: {}, dtype: {}", array.getShape(), array.getDataType());
        }

 // 兼容 [1, C, H, W] 与 [C, H, W]，不调用 squeeze（ONNX ndarray 会递归崩溃）
        int off = shape.length == 4 ? 1 : 0;
        int h = (int) shape[off + 1];
        int w = (int) shape[off + 2];
        float[] data = array.toFloatArray();

        // CHW [0, 1] -> HWC [0, 255] uint8，手动构建 BufferedImage
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int stride = h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int r = clampU8(data[idx]);
                int g = clampU8(data[idx + stride]);
                int b = clampU8(data[idx + 2 * stride]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("                  : {}x{}", w, h);
        }
        return ImageFactory.getInstance().fromImage(img);
    }

    /**
    * 将 [0, 1] 浮点像素钳制并转为 [0, 255] uint8。
    *
    * @param v 浮点像素值
    * @return 0-255 整数
    */
    private static int clampU8(float v) {
        float x = Math.max(0.0f, Math.min(1.0f, v));
        return (int) Math.round(x * 255.0f);
    }

    /**
    *                           
    * <p>
    *        STACK             
    * </p>
    *
    * @return Batchifier          
    */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
