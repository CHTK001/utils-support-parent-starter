package com.chua.deeplearning.support.onnx.age;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * VGG-16              Translator       
 *
 *          https://github.com/onnx/models/tree/main/validated/vision/body_analysis/age_gender
 *
 *                                                                                                          
 *
 *              (vgg_ilsvrc_16_age_imdb_wiki):
 * -       : [批量_大小=1, 通道=3, height=224, width=224] float32
 * -       : [批量_大小, 101] float32 (0-100                   )
 *
 *                      
 * age = sum(probability[i] * i for i 入 范围(0, 101))
 *                 age                    [0.01, 0.05, ..., 0.8, 0.1, ...]
 *                 = 0*0.01 + 1*0.05 + ... + 25*0.8 + 26*0.1 + ...
 *
 * @author CH
 * @since 2025/11/06
 */
public class VggAgeRecognitionTranslator implements Translator<Image, PredictResult> {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger(VggAgeRecognitionTranslator.class);

    /**
     *                   
     */
    private static final int IMAGE_SIZE = 224;

    /**
     *                
     */
    private static final int CHANNELS = 3;

    /**
     *                0-100       
     */
    private static final int AGE_RANGE = 101;

    /**
     *                 -                
     *
     * @param ctx                   
     */
    @Override
    public void prepare(TranslatorContext ctx) {
        LOGGER.info("VggAgeRecognitionTranslator                ");
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
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("                                 : {}x{}", input.getWidth(), input.getHeight());
            }

            NDManager manager = ctx.getNDManager();

 // 镜像     ndarray
            NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

            //           224  224
            array = NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE, Image.Interpolation.AREA);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("                  : {}x{}", IMAGE_SIZE, IMAGE_SIZE);
            }

            //           CHW        (HWC -> CHW)
            array = array.transpose(2, 0, 1);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("          CHW       ");
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

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("                                    : {}", array.getShape());
            }

            return new NDList(array);

        } catch (Exception e) {
            LOGGER.error("                  : {}", e.getMessage(), e);
            throw new RuntimeException("                  ", e);
        }
    }

    /**
     * -                 nd列表
     *
     *             :
     * 1.     nd列表                         0-100
     * 2.                      
     * 3.                         
     * 4.                         
     *
     * @param ctx                   
     * @param list                 nd列表
     * @return                          "25.5    "   
     */
    @Override
    public PredictResult processOutput(TranslatorContext ctx, NDList list) {
        try {
            if (list.isEmpty()) {
                LOGGER.warn("                  ");
                return PredictResult.empty();
            }

            //                   
            NDArray output = list.getFirst();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("                        : {}", output.getShape());
            }

            //                   
            float[] probs = null;

            //           2D        [1, 101]               
            if (output.getShape().dimension() == 2) {
                NDArray firstRow = output.get(0); // [P3C 3.7 豁免] NDArray 张量下标访问
                probs = firstRow.toFloatArray();
            }
            //           1D        [101]               
            else if (output.getShape().dimension() == 1) {
                probs = output.toFloatArray();
            }

            if (probs != null && probs.length == AGE_RANGE) {
                //                         
                double predictedAge = calculateWeightedAge(probs);

                LOGGER.info("                  : {}    ", predictedAge);

                return PredictResult
                    .builder()
                    .value(String.format("%.1f", predictedAge))
                    .confidence(getMaxProbability(probs))
                    .build();
            } else {
                LOGGER.warn("                     : {}", probs != null ? probs.length : "null");
            }

            return PredictResult.empty();

        } catch (Exception e) {
            LOGGER.error("                  : {}", e.getMessage(), e);
            return PredictResult.empty();
        }
    }

    /**
     *                         
     *
     * : age = sum(probability[i] * i for i 入 范围(0, 101))
     *
     * @param probs 0-100                         
     * @return                   
     */
    private double calculateWeightedAge(float[] probs) {
        //                            
        int maxIndex = 0;
        float maxProb = probs[0];

        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > maxProb) {
                maxProb = probs[i];
                maxIndex = i;
            }
        }

        //                                  
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("                  : {}     (         : {})", maxIndex, String.format("%.4f", maxProb));
        }
        return (double) maxIndex;
    }

    /**
     *                      
     *
     * @param probs             
     * @return                
     */
    private double getMaxProbability(float[] probs) {
        float max = 0f;
        for (float prob : probs) {
            if (prob > max) {
                max = prob;
            }
        }
        return (double) max;
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


