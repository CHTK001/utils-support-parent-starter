package com.chua.deeplearning.support.onnx.yolo.plate.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NMSUtils;
import com.chua.deeplearning.support.utils.LetterBoxUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Map;


/**
 * YOLOv8                      
 * <p>
 *        YOLOv8                                     
 *                                  
 * </p>
 * <p>
 *                
 * -                               Detection                           
 * -          YOLOv8-License-Plate                           /         
 * -                640x640               
 * -                             [0, 1]
 * </p>
 * <p>
 *                
 * -                [1, 3, 640, 640] - NCHW   RGB          [0, 1]          
 * -                [1, 6, 8400] - (x_center, y_center, w, h, class1_conf, class2_conf)
 * -                    NMS                           
 * </p>
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/01/22
 */
@Slf4j
public class Yolo8PlateDetectTranslator implements Translator<Image, DetectedObjects> {

    /**
     *                             640x640   
     */
    private final int inputSize;

    /**
     *                      
     */
    private final float minConfThreshold;

    /**
     * IOU       
     */
    private final float iouThreshold;

    /**
     *                   
     */
    private final int topK;

    /**
     *                   
     */
    private int imageWidth;

    /**
     *                   
     */
    private int imageHeight;

    /**
     * LetterBox             
     */
    private LetterBoxUtils.ResizeResult letterBoxResult;

    /**
     *                                     
     * <p>
     *             
     * - inputSize: 640x640
     * - minConfThreshold: 0.3
     * - iouThreshold: 0.5
     * - topK: 100
     * </p>
     */
    public Yolo8PlateDetectTranslator() {
        this(640, 0.3f, 0.5f, 100);
    }

    /**
     *                       Map          
     *
     * @param arguments             
     */
    public Yolo8PlateDetectTranslator(Map<String, ?> arguments) {
        this.minConfThreshold = arguments.containsKey("confThreshold")
                ? Float.parseFloat(arguments.get("confThreshold").toString())
                : 0.3f;

        this.iouThreshold = arguments.containsKey("iouThreshold")
                ? Float.parseFloat(arguments.get("iouThreshold").toString())
                : 0.5f;

        this.topK = arguments.containsKey("topk")
                ? Integer.parseInt(arguments.get("topk").toString())
                : 100;

        this.inputSize = arguments.containsKey("inputSize")
                ? Integer.parseInt(arguments.get("inputSize").toString())
                : 640;
    }

    /**
     *             
     *
     * @param inputSize                                            
     * @param minConfThreshold                       [0.0, 1.0]
     * @param iouThreshold     IOU        [0.0, 1.0]
     * @param topK                                
     */
    public Yolo8PlateDetectTranslator(int inputSize, float minConfThreshold, float iouThreshold, int topK) {
        this.inputSize = inputSize;
        this.minConfThreshold = minConfThreshold;
        this.iouThreshold = iouThreshold;
        this.topK = topK;

        log.info("YOLOv8                               ");
        log.info("  -       :                      Detection   ");
        log.info("  -             : {}x{}", inputSize, inputSize);
        log.info("  -                      : {}", minConfThreshold);
        log.info("  - IOU       : {}", iouThreshold);
        log.info("  -                   : {}", topK);
        log.info("  -             :           [0, 1]");
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);
        imageWidth = (int) array.getShape().get(1);
        imageHeight = (int) array.getShape().get(0);

        // Letter box resize 640x640 with padding (                        )
        letterBoxResult = LetterBoxUtils.letterbox(manager, array, inputSize, inputSize, 114f, LetterBoxUtils.PaddingPosition.CENTER);
        array = letterBoxResult.image;

        array = array.toType(DataType.FLOAT32, false).div(55f); // HWC
        array = array.transpose(2, 1, 0); // HWC -> CHW
        return new NDList(array.expandDims(0));
    }

    @Override
    /** 处理Output */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        var manager = ctx.getNDManager();

        var preds = list.singletonOrThrow();
        preds = preds.squeeze(0).transpose(1, 0);

        // preds shape: (8400, 6)
        var classScores = preds.get(":6");

        // classScores.max(axis=1) like Python .amax(1)
        var maxScores = classScores.max(new int[]{1});

        // mask   score > conf
        var confMask = maxScores.gt(minConfThreshold);

        // mask       
        preds = preds.get(confMask);

        if (preds.isEmpty()) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        // box (xywh)             xyxy
        var boxes = preds.get(":, :4");
        boxes = xywh2xyxy(boxes);

        // 1.
        var scoresAndClasses = preds.get(":, 4:6");
        var scores = scoresAndClasses.max(new int[]{1}, true);
        var index = scoresAndClasses.argMax(1).expandDims(1);

        // 4.       
        var result = NDArrays.concat(new NDList(boxes, scores, index), 1);

        // NMS                   
        int[] keepIndices = NMSUtils.nms(boxes, scores.squeeze(), iouThreshold);
        var kept = result.get(manager.create(keepIndices));
        //              topK            
        if (keepIndices.length > topK) {
            int[] topkIndices = new int[topK];
            System.arraycopy(keepIndices, 0, topkIndices, 0, topK);
            keepIndices = topkIndices;
        }
        var restored = LetterBoxUtils.restoreBox(kept, letterBoxResult.r, letterBoxResult.left, letterBoxResult.top, 5, 0);

        var classNames = new ArrayList<String>();
        var probabilities = new ArrayList<Double>();
        var boundingBoxes = new ArrayList<BoundingBox>();

        float[] flatData = restored.toFloatArray();
        long[] shape = restored.getShape().getShape();
        int rows = (int) shape[0];
        int cols = (int) shape[1];

        //                                     
        float[][] data = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(flatData, i * cols, data[i], 0, cols);
        }

        for (float[] row : data) {
            // row         (x1, y1, x2, y2, score, classIndex)
            float x1 = row[0];
            float y1 = row[1];
            float x2 = row[2];
            float y2 = row[3];
            float score = row[4];
            int classIndex = (int) row[5];

            double prob = score;
            String className = classIndex == 0 ? "single" : "double";

            //                   DJL   Rectangle                  0~1   
            double rectX = x1 / imageWidth;
            double rectY = y1 / imageHeight;
            double rectW = (x2 - x1) / imageWidth;
            double rectH = (y2 - y1) / imageHeight;

            var rectangle = new Rectangle(rectX, rectY, rectW, rectH);
            classNames.add(className);
            probabilities.add(prob);
            boundingBoxes.add(rectangle);
        }
        return new DetectedObjects(classNames, probabilities, boundingBoxes);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     *                          (x_center, y_center, w, h)                             (x1, y1, x2, y2)
     *
     * @param xywh                                  
     * @return                               
     */
    public static NDArray xywh2xyxy(NDArray xywh) {
        var x = xywh.get(":, 0");
        var y = xywh.get(":, 1");
        var w = xywh.get(":, 2").div(2);
        var h = xywh.get(":, 3").div(2);
        var x1 = x.sub(w);
        var y1 = y.sub(h);
        var x2 = x.add(w);
        var y2 = y.add(h);
        return NDArrays.stack(new NDList(x1, y1, x2, y2), 1);
    }
}


