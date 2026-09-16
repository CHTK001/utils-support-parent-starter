package com.chua.deeplearning.support.onnx.gender;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.result.PredictResult;
import lombok.extern.slf4j.Slf4j;


/**
* VGG-16              Translator       
*
*          https://github.com/onnx/models/tree/main/validated/vision/body_analysis/age_gender
*
*                                                                                                    
*
*              (vgg_ilsvrc_16_gender_imdb_wiki):
* -       : [批量_大小=1, 通道=3, height=224, width=224] float32
* -       : [批量_大小, 2] float32 (2                              )
*
*                   :
* - 0: "Female"
* - 1: "Male"
*
* @author CH
* @版本 1.0.0
* @since 2025/11/06
 */
@Slf4j
public class VggGenderRecognitionTranslator implements Translator<Image, PredictResult> {

    /**
    *                   
     */
    private static final int IMAGE_SIZE = 224;

    /**
    *                
     */
    private static final int CHANNELS = 3;

    /**
    *             
     */
    private static final String[] GENDER_LABELS = {"Female", "Male"};

    /**
    *                 -                
    *
    * @param ctx                   
     */
    @Override
    public void prepare(TranslatorContext ctx) {
        log.info("VggGenderRecognitionTranslator                ");
    }

    /**
    * -     镜像                          ndarray
    *
    *             :
    * 1.     镜像           ndarray
    * 2.           224  224
    * 3.           CHW       
    * 4.           float32
    * 5.                          127,           128   
    *
    * @param ctx                   
    * @param input             
    * @return NDList                   
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("                                 : {}x{}", input.getWidth(), input.getHeight());
            }

            NDManager manager = ctx.getNDManager();

 // 镜像     ndarray
            NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

            //           224  224
            array = NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE, Image.Interpolation.AREA);
            if (log.isDebugEnabled()) {
                log.debug("                  : {}x{}", IMAGE_SIZE, IMAGE_SIZE);
            }

            //           CHW        (HWC -> CHW)
            array = array.transpose(2, 0, 1);
            if (log.isDebugEnabled()) {
                log.debug("          CHW       ");
            }

            //           float32
            if (!array.getDataType().equals(DataType.FLOAT32)) {
                array = array.toType(DataType.FLOAT32, false);
            }

            //          : (x - 127) / 128
            //                    [3, 1, 1]
            NDArray meanArray = manager.create(new float[]{127f, 127f, 127f}, new Shape(3, 1, 1));
            array = array.sub(meanArray);

            //                 128
            array = array.div(128f);

            if (log.isDebugEnabled()) {
                log.debug("                                    : {}", array.getShape());
            }

            return new NDList(array);

        } catch (Exception e) {
            log.error("                  : {}", e.getMessage(), e);
            throw new RuntimeException("                  ", e);
        }
    }

    /**
    * -                 nd列表
    *
    *             :
    * 1.     nd列表
    * 2.                      
    * 3.                                  
    * 4.                            
    *
    * @param ctx                   
    * @param list                 nd列表
    * @return                             "Male"   
     */
    @Override
    public PredictResult processOutput(TranslatorContext ctx, NDList list) {
        try {
            if (list.isEmpty()) {
                log.warn("                  ");
                return PredictResult.empty();
            }

            //                   
            NDArray output = list.getFirst();
            if (log.isDebugEnabled()) {
                log.debug("                        : {}", output.getShape());
            }

            //                   
            float[] probs = null;

            //           2D        [1, 2]               
            if (output.getShape().dimension() == 2) {
                NDArray firstRow = output.get(0); // [P3C 四十一 豁免] NDArray 张量下标访问（非 List/Collection）
                probs = firstRow.toFloatArray();
            }
            //           1D        [2]               
            else if (output.getShape().dimension() == 1) {
                probs = output.toFloatArray();
            }

            if (probs != null && probs.length >= GENDER_LABELS.length) {
                //                                  
                int maxIdx = argMax(probs);

                if (maxIdx >= 0 && maxIdx < GENDER_LABELS.length) {
                    String gender = GENDER_LABELS[maxIdx];
                    double confidence = (double) probs[maxIdx];

                    log.info("                  : {} (         : {})", gender, confidence);

                    return PredictResult
                        .builder()
                        .value(gender)
                        .confidence(confidence)
                        .build();
                }
            } else {
                log.warn("                     : {}", probs != null ? probs.length : "null");
            }

            return PredictResult.empty();

        } catch (Exception e) {
            log.error("                  : {}", e.getMessage(), e);
            return PredictResult.empty();
        }
    }

    /**
    *                                  
    *
    * @param array             
    * @return                   
     */
    private int argMax(float[] array) {
        if (array.length == 0) {
            return -1;
        }

        int maxIdx = 0;
        float maxVal = array[0];

        for (int i = 1; i < array.length; i++) {
            if (array[i] > maxVal) {
                maxVal = array[i];
                maxIdx = i;
            }
        }

        return maxIdx;
    }

    /**
    *        Batchifier
    *
    * @return Batchifier.STACK
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}


