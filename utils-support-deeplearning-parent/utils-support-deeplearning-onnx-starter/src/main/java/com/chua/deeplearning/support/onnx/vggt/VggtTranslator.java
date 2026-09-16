package com.chua.deeplearning.support.onnx.vggt;

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
* VGGT Translator
* <p>
*                                                                                  
* <p>
*                
* -                 512x512
* -                      
* -           NCHW       
* <p>
*                
* -        3D                   
* -                    translator上下文
* -              镜像                      上下文
*
* @author CH
* @版本 4.0.0.32
* @since 2024/11/08
 */
@Slf4j
public class VggtTranslator implements Translator<Image, Image> {

    /**
    *                         
     */
    private static final int INPUT_SIZE = 512;

    /**
    * 上下文           3D
     */
    public static final String VGGT_OUTPUT_KEY = "vggt_3d_output";

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

 // 镜像net
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        array = NDImageUtils.normalize(array, mean, std);

        //           NCHW       : [1, 3, 512, 512]
        array = array.transpose(2, 0, 1).expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        : {}          ", list.size());
        }

        if (list.isEmpty()) {
            log.warn("                  ");
            return createPlaceholderImage(ctx.getNDManager());
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

 // 3D                 上下文
 // Translator                          镜像
        ctx.setAttachment(VGGT_OUTPUT_KEY, vggtOutput);

        log.info("3D                : {}                ,                   : {}",
                vggtOutput.getNumGaussians(), vggtOutput.getFeatureDimension());

 // 上下文
        return createPlaceholderImage(ctx.getNDManager());
    }

    /**
    *                   
    * <p>
    *        3D                                                                
    * 3D              translator上下文
    *
    * @param manager nd管理器
    * @return             
     */
    private Image createPlaceholderImage(NDManager manager) {
        //                                              
        //        uint8                    [0, 255]   HWC       
        NDArray imageArray = manager.zeros(new Shape(INPUT_SIZE, INPUT_SIZE, 3))
                .toType(DataType.UINT8, false);
        return ImageFactory.getInstance().fromNDArray(imageArray);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        //                   
        return null;
    }
}
