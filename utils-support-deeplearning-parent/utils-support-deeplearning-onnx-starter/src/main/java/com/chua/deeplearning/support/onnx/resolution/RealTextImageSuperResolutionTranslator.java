package com.chua.deeplearning.support.onnx.resolution;

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
import lombok.extern.slf4j.Slf4j;

/**
 *                                  
 *                                                             
 *
 * @author CH
 * @since 2024/11/14 16:51
 */
@Slf4j
public class RealTextImageSuperResolutionTranslator implements Translator<Image, Image> {

    /**
     * ND                        ndarray
     */
    private NDManager manager;

    /**
     *                                                    
     */
    private final int detectResolution = 512;

    /**
     *                                           
     *
     * @param ctx                   
     */
    @Override
    public void prepare(TranslatorContext ctx) {
        this.manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "PyTorch");
    }

    /**
     * nd列表
     * <p>
     *                
     * 1.                   ndarray         FLOAT32
     * 2.                                                 
     * 3.                                  
     * 4.                      HWC -> CHW
     * 5.              [0, 1]                255   
     * 6.                   (array - 0.5) / 0.5
     *
     * @param ctx                     
     * @param input             
     * @return                         NDList
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
 // ndarray                        FLOAT32
        NDArray array = input.toNDArray(this.manager).toType(DataType.FLOAT32, false);

        //                      
        float upScale = (float) detectResolution / (float) input.getHeight();

        //                                              
        int resizedWidth = (int) (upScale * input.getWidth());

        //                   
        array = NDImageUtils.resize(array, resizedWidth, detectResolution);

        //                            0-1      
        array = array.transpose(2, 0, 1).div(255f);

        //                                  
        NDArray mean = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));

        //                                     
        NDArray std = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));

        //                         (array - mean) / std
        array.subi(mean).divi(std);
        return new NDList(array);
    }

    /**
     * nd列表
     * <p>
     *                
     * 1.                               
     * 2.                输出 * 0.5 + 0.5
     * 3.                   [0,1]         
     * 4.          UINT8                  [0,255]      
     * 5.    ndarray
     *
     * @param ctx                    
     * @param list                nd列表
     * @return                               
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        //                         
        NDArray outputImg = list.singletonOrThrow();

 // 输出 * 0.5 + 0.5
        outputImg = outputImg.mul(0.5f).add(0.5f);

        //                   0-1         
        outputImg = outputImg.clip(0, 1);

        //          0-255                  UINT8      
        outputImg = outputImg.mul(255.0f).round().toType(DataType.UINT8, false);

 // ndarray
        Image img = ImageFactory.getInstance().fromNDArray(outputImg);

        //             
        this.manager.close();

        return img;
    }

    /**
     *                      
     *
     * @return                               STACK      
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
