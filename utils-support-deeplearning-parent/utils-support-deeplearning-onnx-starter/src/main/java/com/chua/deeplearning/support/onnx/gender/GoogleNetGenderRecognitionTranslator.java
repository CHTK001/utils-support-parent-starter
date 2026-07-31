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
 * GoogleNet              Translator       
 *
 *          https://github.com/onnx/models/tree/main/validated/vision/body_analysis/age_gender
 *
 *                                                                                                    
 *
 *              (gender_googlenet):
 * -       : [batch_size=1, channels=3, height=224, width=224] float32
 * -       : [batch_size, 2] float32 (2                              )
 *
 *                   :
 * - 0: "Female"
 * - 1: "Male"
 *
 * @author CH
 * @version 1.0.0
 * @since 2025/11/06
 */
@Slf4j
public class GoogleNetGenderRecognitionTranslator implements Translator<Image, PredictResult> {

    /**
     *                   
     */
    private static final int IMAGE_SIZE = 224;

    /**
     *                
     */
    private static final int CHANNELS = 3;

    /**
     * ImageNet                 -       
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
     * ImageNet                 -          
     */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

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
        log.info("GoogleNetGenderRecognitionTranslator                ");
    }

    /**
     *              -     Image                          NDArray
     *
     *             :
     * 1.     Image           NDArray
     * 2.           224  224
     * 3.           CHW       
     * 4.           float32
     * 5.        ImageNet          
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

            //        Image     NDArray
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

            //          : (x - mean) / std
            //                    [3, 1, 1]
            NDArray meanArray = manager.create(MEAN, new Shape(3, 1, 1));
            array = array.sub(meanArray);

            //                       [3, 1, 1]
            NDArray stdArray = manager.create(STD, new Shape(3, 1, 1));
            array = array.div(stdArray);

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
     *              -                 NDList                         
     *
     *             :
     * 1.     NDList                      
     * 2.                      
     * 3.                                  
     * 4.                            
     *
     * @param ctx                   
     * @param list                 NDList
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
            NDArray output = list.get(0);
            if (log.isDebugEnabled()) {
                log.debug("                        : {}", output.getShape());
            }

            //                   
            float[] probs = null;

            //           2D        [1, 2]               
            if (output.getShape().dimension() == 2) {
                NDArray firstRow = output.get(0);
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


