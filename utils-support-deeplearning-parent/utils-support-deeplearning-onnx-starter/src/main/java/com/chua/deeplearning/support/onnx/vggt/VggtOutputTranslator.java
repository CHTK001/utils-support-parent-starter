package com.chua.deeplearning.support.onnx.vggt;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;


/**
* VGGT              Translator
* <p>
*              {@link VggtOutput}                    Image   
*           3D                             Translator   
*
* @author CH
* @版本 4.0.0.32
* @since 2024/11/08
 */
@Slf4j
public class VggtOutputTranslator implements Translator<Image, VggtOutput> {

    /**
    *                         
     */
    private static final int INPUT_SIZE = 518;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        : {}x{}", input.getWidth(), input.getHeight());
        }

        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //                                        
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);

        //              [0, 1]
        array = array.div(255.0f);

        // DJL     normalize        CHW                                             
        array = array.transpose(2, 0, 1);

 // 镜像net
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        array = NDImageUtils.normalize(array, mean, std).expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public VggtOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        : {}          ", list.size());
        }

        if (list.isEmpty()) {
            log.warn("                  ");
            return new VggtOutput(new float[0], new long[]{0});
        }

        //                      3D                
        NDArray output = list.getFirst();
        long[] outputShape = output.getShape().getShape();

        log.info("VGGT             : shape={}, dtype={}",
                output.getShape(), output.getDataType());

        //        3D                 Java          
        float[] gaussianData = output.toFloatArray();

        log.info("       3D             : {}    float   ,       : {}",
                gaussianData.length, java.util.Arrays.toString(outputShape));

 // vggt输出
        VggtOutput vggtOutput = new VggtOutput(gaussianData, outputShape);

        log.info("3D                   : {}                ,                   : {}",
                vggtOutput.getNumGaussians(), vggtOutput.getFeatureDimension());

        return vggtOutput;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        //                   
        return null;
    }
}
