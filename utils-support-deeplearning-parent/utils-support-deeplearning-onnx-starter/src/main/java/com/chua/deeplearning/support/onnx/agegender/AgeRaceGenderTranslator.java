package com.chua.deeplearning.support.onnx.agegender;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.result.HumanPredictResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * AgeRaceGenderNet          
 * <p>
 *              AgeRaceGenderNet                                              
 *                                                          
 * -                            0-116      117            
 * -                            
 * -                            
 * <p>
 *                
 * -                       256x256
 * -              [0, 1]
 * - ImageNet             mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
 * -           NCHW       
 * <p>
 *                
 * -                                                 
 * -          softmax                         0-116   
 * -          softmax                   Male/Female   
 * -          softmax                   White/Black/Asian/Indian/Others   
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2024/11/08
 */
public class AgeRaceGenderTranslator implements Translator<Image, HumanPredictResult> {

    /** 日志记录器 */
    /** Logger */
    private static final Logger LOGGER = LoggerFactory.getLogger(AgeRaceGenderTranslator.class);

    /**
     *                   
     */
    private static final int INPUT_SIZE = 256;

    /**
     * ImageNet       
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
     * ImageNet          
     */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /**
     *             
     */
    private static final String[] GENDER_CLASSES = {"Male", "Female"};

    /**
     *             
     */
    private static final String[] RACE_CLASSES = {
            // White",   //
           
            // Black",   //
           
            // Asian",   //
           
            // Indian",  //
           
            // Others"   //
           
    };

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("                        : {}x{}", input.getWidth(), input.getHeight());
        }

        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        //                       256x256
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);

        //              [0, 1]
        array = array.div(255.0f);

        //           CHW       : [3, 256, 256]
        //          NDImageUtils.normalize                       CHW       
        array = array.transpose(2, 0, 1);

        // ImageNet                 CHW                   
        array = NDImageUtils.normalize(array, MEAN, STD);

        //        batch       : [1, 3, 256, 256]
        array = array.expandDims(0);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    @Override
    public HumanPredictResult processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("                        : {}          ", list.size());
        }

        long startTime = System.currentTimeMillis();

        //                               
        //                                                                      
        //                                                    

        int age = 0;
        float ageConfidence = 0.0f;
        String gender = "Unknown";
        float genderConfidence = 0.0f;
        String race = "Unknown";
        float raceConfidence = 0.0f;

        if (list.size() >= 3) {
            //                         [age, gender, race]
            //                                              

            // 1.                                                             
            NDArray ageOutput = list.get(0);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("             shape: {}", ageOutput.getShape());
            }

            //                                     shape     (1, num_ages)
            //           squeeze        batch                       softmax
            NDArray ageLogits = ageOutput.squeeze(0);
            NDArray ageProbs = ageLogits.softmax(-1);

            //                               
            int ageIdx = (int) ageProbs.argMax().getLong();
            age = ageIdx;
            ageConfidence = ageProbs.getFloat(ageIdx);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("            : {}    ,          : {}", age, ageConfidence);
            }

            // 2.                            
            NDArray genderOutput = list.get(1);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("             shape: {}", genderOutput.getShape());
            }

            //        batch                 softmax
            NDArray genderLogits = genderOutput.squeeze(0);
            NDArray genderProbs = genderLogits.softmax(-1);
            int genderIdx = (int) genderProbs.argMax().getLong();
            genderConfidence = genderProbs.getFloat(genderIdx);
            gender = GENDER_CLASSES[Math.min(genderIdx, GENDER_CLASSES.length - 1)];

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("            : {},          : {}", gender, genderConfidence);
            }

            // 3.                            
            NDArray raceOutput = list.get(2);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("             shape: {}", raceOutput.getShape());
            }

            //        batch                 softmax
            NDArray raceLogits = raceOutput.squeeze(0);
            NDArray raceProbs = raceLogits.softmax(-1);
            int raceIdx = (int) raceProbs.argMax().getLong();
            raceConfidence = raceProbs.getFloat(raceIdx);
            race = RACE_CLASSES[Math.min(raceIdx, RACE_CLASSES.length - 1)];

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("            : {},          : {}", race, raceConfidence);
            }

        } else if(list.size() == 1) {
            //                                                          
            NDArray output = list.get(0);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("             shape: {}", output.getShape());
            }

            //                               
            //                               
            long[] shape = output.getShape().getShape();

            if (shape.length >= 2 && shape[1] >= 7) {
                //                      [batch, features]
                // features                1          + 2          + 5          = 8         

                //             
                age = Math.round(output.getFloat(0, 0));
                ageConfidence = calculateAgeConfidence(age);

                //              logits
                NDArray genderLogits = output.get(":, 1:3");
                NDArray genderProbs = genderLogits.softmax(-1);
                int genderIdx = (int) genderProbs.argMax(-1).getLong();
                genderConfidence = genderProbs.getFloat(0, genderIdx);
                gender = GENDER_CLASSES[Math.min(genderIdx, GENDER_CLASSES.length - 1)];

                //              logits
                if (shape[1] >= 8) {
                    NDArray raceLogits = output.get(":, 3:8");
                    NDArray raceProbs = raceLogits.softmax(-1);
                    int raceIdx = (int) raceProbs.argMax(-1).getLong();
                    raceConfidence = raceProbs.getFloat(0, raceIdx);
                    race = RACE_CLASSES[Math.min(raceIdx, RACE_CLASSES.length - 1)];
                }
            }
        }

        long processingTime = System.currentTimeMillis() - startTime;

        LOGGER.info("            :       ={},       ={},       ={},       ={}ms",
                age, gender, race, processingTime);

        //        HumanPredictResult                                                                
        HumanPredictResult result = HumanPredictResult.builder()
                .processingTimeMs(processingTime)
                .confidence(Math.max(Math.max(ageConfidence, genderConfidence), raceConfidence))
                .value(String.format("Age: %d, Gender: %s, Race: %s", age, gender, race))
                .build();

        //                                  
        //                0-116          age >= 0              > 0       
        if (age >= 0 && ageConfidence > 0) {
            result.setAgeDetected(age, ageConfidence);
        }

        //                                  
        if (gender != null && !"Unknown".equals(gender) && genderConfidence > 0) {
            result.setGenderDetected(gender, genderConfidence);
        }

        //                                  
        if (race != null && !"Unknown".equals(race) && raceConfidence > 0) {
            result.setRaceDetected(race, raceConfidence);
        }

        return result;
    }

    /**
     *                      
     * <p>
     *                                              
     *
     * @param age                
     * @return           [0.0, 1.0]
     */
    private float calculateAgeConfidence(int age) {
        //                      0-116                               
        if (age < 0 || age > 116) {
            return 0.0f;
        }

        //           0-100                            
        if (age <= 100) {
            return 0.9f;
        }

        // 100-116                      
        return 0.5f;
    }

    @Override
    public Batchifier getBatchifier() {
        //           batchifier                         processInput                    batch       
        return null;
    }
}


