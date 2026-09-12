package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;



/**
   * LCM-lora unet
 * <p>
   * LCM-lora     unet
 *                               
 * </p>
 * <p>
 *                
 * -                       
 * -                       
   * -     unet
 * </p>
 * <p>
 *                   
 * 1.                   
 * 2.                
 * 3.                            
 * </p>
 *
 * @author CH
   * @版本 4.0.0.32
 * @since 2025/01/26
 */
@Slf4j
public class LcmLoraUnetTranslator implements Translator<Image, Image> {

    /**
     *                   
     */
    private final int width;

    /**
     *                   
     */
    private final int height;

    /**
     *              -                   
     */
    public LcmLoraUnetTranslator() {
        this(512, 512);
    }

    /**
     *              -                
     *
     * @param width                    
     * @param height                   
     */
    public LcmLoraUnetTranslator(int width, int height) {
        this.width = width;
        this.height = height;
        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA][Translator]                -             : {}x{}", width, height);
        }
    }

    /**
     *                   
     *
     * @param ctx                     
     * @param input             
     * @return              NDList
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);

        //                      
        array = NDImageUtils.resize(array, width, height);

        //        float32                 0~1
        array = array.toType(DataType.FLOAT32, false).div(255f);

        // HWC -> CHW
        array = array.transpose(2, 0, 1);

 // 镜像net
        var mean = manager.create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        var std = manager.create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA][Translator]                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }

        return new NDList(array);
    }

    /**
     *                   
     *
     * @param ctx                    
     * @param list              nd列表
     * @return                
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        try (NDManager manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "PyTorch")) {
            var output = list.singletonOrThrow();

            //             
            output = output.mul(0.5f).add(0.5f);

            //                   0-1         
            output = output.clip(0, 1);

            //          0-255                  UINT8      
            output = output.mul(255.0f).round().toType(DataType.UINT8, false);

            // CHW -> HWC
            output = output.transpose(1, 2, 0);

 // ndarray
            var img = ai.djl.modality.cv.ImageFactory.getInstance().fromNDArray(output);

            if (log.isDebugEnabled()) {
                log.debug("[LCM-LoRA][Translator]                  : width={}, height={}", img.getWidth(), img.getHeight());
            }

            return img;
        }
    }

    /**
     *                   
     *
     * @return STACK             
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}

