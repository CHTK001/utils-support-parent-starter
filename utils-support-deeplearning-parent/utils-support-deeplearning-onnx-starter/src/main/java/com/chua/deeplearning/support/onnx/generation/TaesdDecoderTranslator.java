package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * TAESD (Tiny Auto Encoder for Stable Diffusion) VAE                      
 *
 * <p>    Stable Diffusion              latents           RGB          
 *              SD VAE (~350MB)   TAESD     ~10MB                      5-10       </p>
 *
 * <p>         (1, 4, latent_h, latent_w) latents</p>
 * <p>         (1, 3, h*8, w*8) RGB                 [0, 1]</p>
 *
 * @author CH
 * @since 2026-05-02
 */
public class TaesdDecoderTranslator implements Translator<NDList, Image> {

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, NDList input) {
        NDArray latent = input.singletonOrThrow();
        // Batchifier.STACK                 batch                          batch dim             
        if (latent.getShape().dimension() >= 4 && latent.getShape().get(0) == 1) {
            latent = latent.squeeze(0);
        }
        return new NDList(latent);
    }

    @Override
    /** 处理Output */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        try (NDManager manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "PyTorch")) {
            NDArray output = list.singletonOrThrow();

            // TAESD           (1, 3, h*8, w*8)          batch       
            if (output.getShape().dimension() >= 4 && output.getShape().get(0) == 1) {
                output = output.squeeze(0);
            }

            // TAESD                 [0, 1]             SD VAE           mul(0.5).add(0.5)             
            output = output.clip(0, 1);

            //        uint8 [0, 255]
            output = output.mul(255.0f).round().toType(DataType.UINT8, false);

            // CHW -> HWC
            output = output.transpose(1, 2, 0);

            return ImageFactory.getInstance().fromNDArray(output);
        }
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
