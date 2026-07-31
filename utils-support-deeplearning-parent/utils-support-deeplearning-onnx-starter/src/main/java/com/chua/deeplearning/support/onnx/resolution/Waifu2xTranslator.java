package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * waifu2x ONNX                   /                        
 *
 * <p>         : HWC     CHW                       [0, 1]
 *
 * <p>         :           [0, 1]                   [0, 255] uint8             Image
 *
 * <p>      : <a href="https://github.com/nagadomi/waifu2x">waifu2x</a>
 *
 * @author CH
 * @since 2026-05-09
 */
@Slf4j
public class Waifu2xTranslator implements Translator<Image, Image> {

    /**
     * waifu2x ONNX NDManager                                                      
     */
    private NDManager manager;

    @Override
    public void prepare(TranslatorContext ctx) {
        this.manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "OnnxRuntime");
        if (log.isDebugEnabled()) {
            log.debug("waifu2x ONNX                   ");
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);

        // HWC     CHW                 [0, 1]
        array = array.transpose(2, 0, 1).div(255.0f);

        if (log.isDebugEnabled()) {
            log.debug("waifu2x                   : shape={}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();

        //           [0, 1]              [0, 255]
        outputImg = outputImg.clip(0.0f, 1.0f).mul(255.0f).round().toType(DataType.UINT8, false);

        Image img = ImageFactory.getInstance().fromNDArray(outputImg);

        if (log.isDebugEnabled()) {
            log.debug("waifu2x                   : {}x{}", img.getWidth(), img.getHeight());
        }

        manager.close();
        return img;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
