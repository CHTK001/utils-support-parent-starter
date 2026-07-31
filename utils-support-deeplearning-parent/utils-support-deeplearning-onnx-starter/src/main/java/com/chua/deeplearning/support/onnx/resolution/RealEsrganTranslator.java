package com.chua.deeplearning.support.onnx.resolution;

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
import lombok.extern.slf4j.Slf4j;

/**
 * Real-ESRGAN ONNX                      
 *
 * <p>       RRDBNet                                     
 *
 * <p>         :
 * <ul>
 *   <li>HWC     CHW                       [0, 1]          255.0   
 *   <li>          mean/std             RRDBNet              [0, 1]          
 * </ul>
 *
 * <p>         :
 * <ul>
 *   <li>          [0, 1]          [0, 255] uint8
 *   <li>          Image
 * </ul>
 *
 * <p>      : <a href="https://github.com/xinntao/Real-ESRGAN">Real-ESRGAN</a>
 *
 * @author CH
 * @since 2026-05-02
 */
@Slf4j
public class RealEsrganTranslator implements Translator<Image, Image> {

    /**
     *                                                             
     */
    private final int scale;

    /**
     * NDArray
     */
    private NDManager manager;

    /**
     *                              
     */
    public RealEsrganTranslator() {
        this(4);
    }

    /**
     *                              
     *
     * @param scale                   2     4   
     */
    public RealEsrganTranslator(int scale) {
        this.scale = scale;
    }

    @Override
    public void prepare(TranslatorContext ctx) {
        this.manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "OnnxRuntime");
        if (log.isDebugEnabled()) {
            log.debug("Real-ESRGAN ONNX                   : scale={}x", scale);
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);

        // HWC     CHW                 [0, 1]
        array = array.transpose(2, 0, 1).div(255.0f);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();

        //           [0, 1]
        outputImg = outputImg.clip(0.0f, 1.0f);

        //           [0, 255] uint8
        outputImg = outputImg.mul(255.0f).round().toType(DataType.UINT8, false);

        Image img = ImageFactory.getInstance().fromNDArray(outputImg);

        if (log.isDebugEnabled()) {
            log.debug("                  : {}x{}", img.getWidth(), img.getHeight());
        }

        manager.close();
        return img;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
