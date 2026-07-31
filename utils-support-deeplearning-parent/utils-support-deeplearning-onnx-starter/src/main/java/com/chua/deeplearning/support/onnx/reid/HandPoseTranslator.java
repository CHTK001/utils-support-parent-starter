package com.chua.deeplearning.support.onnx.reid;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenCV MediaPipe HandPose                       Translator
 *
 * <p>            : [1, 224, 224, 3] NHWC RGB       
 * <p>            :
 * - Identity: [1, 63]     21                       x,y,z                         
 * - Identity_1: [1, 1]                    
 * - Identity_2: [1, 1]                 0=      , 1=         
 * - Identity_3: [1, 63]                       
 *
 * <p>         :
 * -                       224x224
 * -              [0, 1]
 * -        HWC                    NHWC   
 *
 * <p>         :
 * -        float[65]: [63                   ,          ,          ]
 *
 * @author CH
 * @since 2026-05-10
 */
@Slf4j
public class HandPoseTranslator implements Translator<Image, float[]> {

    private static final int INPUT_SIZE = 224;
    private static final int NUM_KEYPOINTS = 21;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        //                 224x224
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);

        //           FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }

        //              [0, 1]
        array = array.div(255.0f);

        //        batch       : [H, W, 3]     [1, H, W, 3]          NHWC   
        array = array.expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("HandPose                   : shape={}", array.getShape());
        }

        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // Identity: [1, 63]     21              3 (x,y,z)
        NDArray landmarks = list.get(0);
        float[] landmarkData = landmarks.toFloatArray();

        // Identity_1: [1, 1]              
        NDArray confidenceArr = list.get(1);
        float confidence = confidenceArr.toFloatArray()[0];

        // Identity_2: [1, 1]              
        NDArray handednessArr = list.get(2);
        float handedness = handednessArr.toFloatArray()[0];

        //             : [63             ,          ,          ]
        float[] result = new float[NUM_KEYPOINTS * 3 + 2];
        System.arraycopy(landmarkData, 0, result, 0, landmarkData.length);
        result[landmarkData.length] = confidence;
        result[landmarkData.length + 1] = handedness;

        if (log.isDebugEnabled()) {
            log.debug("HandPose       : confidence={}, handedness={}, keypoints={}",
                    confidence, handedness, NUM_KEYPOINTS);
        }

        return result;
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
