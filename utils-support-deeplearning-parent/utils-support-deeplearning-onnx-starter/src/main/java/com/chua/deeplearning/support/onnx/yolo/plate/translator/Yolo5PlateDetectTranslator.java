package com.chua.deeplearning.support.onnx.yolo.plate.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;
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
import java.util.Collections;
import java.util.Map;


/**
* yolov5
* <p>
* yolov5
*                                                          
* </p>
* <p>
*                
* -                               Detection                                       
* -          yolov5-执照-铭牌                           /
* -                640x640               
* -                             [0, 1]
* </p>
* <p>
*                
* -                [1, 3, 640, 640] - NCHW   RGB          [0, 1]          
* -                [1, 25200, 15] - (x_center, y_center, w, h, obj_conf, 8            , 类1_conf, 类2_conf)
* -                    NMS                           
* </p>
*
* @author CH
* @版本 4.0.0.32
* @since 2025/01/22
 */
@Slf4j
public class Yolo5PlateDetectTranslator implements Translator<Image, DetectedObjects> {

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
    private final float confThreshold;

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
    *                                     
    * <p>
    *             
    * - 输入大小: 640x640
    * - conf阈值: 0.3
    * - iou阈值: 0.5
    * - topk: 100
    * </p>
     */
    public Yolo5PlateDetectTranslator() {
        this(640, 0.3f, 0.5f, 100);
    }

    /**
    * 映射
    *
    * @param arguments             
     */
    public Yolo5PlateDetectTranslator(Map<String, ?> arguments) {
        this.confThreshold = arguments.containsKey("confThreshold")
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

        this.minConfThreshold = 0.3f;
    }

    /**
    *             
    *
    * @param inputSize                                          
    * @param confThreshold                  [0.0, 1.0]
    * @param iouThreshold   IOU        [0.0, 1.0]
    * @param topK                             
     */
    public Yolo5PlateDetectTranslator(int inputSize, float confThreshold, float iouThreshold, int topK) {
        this.inputSize = inputSize;
        this.minConfThreshold = 0.3f;
        this.confThreshold = confThreshold;
        this.iouThreshold = iouThreshold;
        this.topK = topK;

        log.info("YOLOv5                               ");
        log.info("  -       :                      Detection   ");
        log.info("  -             : {}x{}", inputSize, inputSize);
        log.info("  -                : {}", confThreshold);
        log.info("  - IOU       : {}", iouThreshold);
        log.info("  -                   : {}", topK);
        log.info("  -             :           [0, 1]");
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);
        imageWidth = (int) array.getShape().get(1);
        imageHeight = (int) array.getShape().get(0);

        // Letter box resize 640x640 with padding (                        )
        var letterBoxResult = LetterBoxUtils.letterbox(manager, array, inputSize, inputSize, 114f, LetterBoxUtils.PaddingPosition.CENTER);
        ctx.setAttachment("letterBoxResult", letterBoxResult);
        array = letterBoxResult.image;

        array = array.toType(DataType.FLOAT32, false).div(55f); // HWC
        array = array.transpose(2, 1, 0); // HWC -> CHW
        return new NDList(array.expandDims(0));
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        var manager = ctx.getNDManager();
        var letterBoxResult = (LetterBoxUtils.ResizeResult) ctx.getAttachment("letterBoxResult");

 // [x_center, y_center, w, h, obj_conf, 8 keypoints, 类1_conf, 类2_conf]
        var dets = list.singletonOrThrow();
        var dets0 = dets.get(0);
        // obj_conf [5:13] 类置信度 [13:15]
        var conf = dets0.get(":4");
        var mask = conf.gt(minConfThreshold);
        var detsFiltered = dets0.get(mask);

        var clsLogits = detsFiltered.get(":, 13:15");
        var confFiltered = detsFiltered.get(":, 4").reshape(-1, 1);
        clsLogits = clsLogits.mul(confFiltered);

        var jointScore = clsLogits.max(new int[]{1});
        var jointMask = jointScore.gt(confThreshold);
        detsFiltered = detsFiltered.get(jointMask);

        if (detsFiltered.isEmpty()) {
            return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        clsLogits = clsLogits.get(jointMask);

        var xywh = detsFiltered.get(":, :4");
        var halfWH = xywh.get(":, 2:4").div(2f);
        var xy1 = xywh.get(":, 0:2").sub(halfWH);
        var xy2 = xywh.get(":, 0:2").add(halfWH);
        var boxes = NDArrays.concat(new NDList(xy1, xy2), 1);

        var scores = clsLogits.max(new int[]{1}, true);
        var indices = clsLogits.argMax(1).reshape(-1, 1).toType(DataType.FLOAT32, false);

        var keyPoints = detsFiltered.get(":, 5:13");

        var output = NDArrays.concat(new NDList(boxes, scores, keyPoints, indices), 1);

        int[] keepIndices = NMSUtils.nms(boxes, scores.squeeze(), iouThreshold);
        if (keepIndices.length == 0) {
            return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        var kept = output.get(manager.create(keepIndices));
        if (keepIndices.length > topK) {
            int[] topkIndices = new int[topK];
            System.arraycopy(keepIndices, 0, topkIndices, 0, topK);
            keepIndices = topkIndices;
        }
        var restored = LetterBoxUtils.restoreBox(kept, letterBoxResult.r, letterBoxResult.left, letterBoxResult.top, 5, 8);

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
            // row         (x1, y1, x2, y2, score, kp1,..., kp8, classIndex)
            float x1 = row[0];
            float y1 = row[1];
            float x2 = row[2];
            float y2 = row[3];
            float score = row[4];
            int classIndex = (int) row[13];

            double prob = score;
            String className = classIndex == 0 ? "single" : "double";

            //                   DJL   Rectangle                  0~1   
            double rectX = x1 / imageWidth;
            double rectY = y1 / imageHeight;
            double rectW = (x2 - x1) / imageWidth;
            double rectH = (y2 - y1) / imageHeight;

            //        Polygon             
            var pointsSrc = new ArrayList<Point>();
            pointsSrc.add(new Point(row[5], row[6]));
            pointsSrc.add(new Point(row[7], row[8]));
            pointsSrc.add(new Point(row[9], row[10]));
            pointsSrc.add(new Point(row[11], row[12]));

            var box = new Landmark(rectX, rectY, rectW, rectH, pointsSrc);
            classNames.add(className);
            probabilities.add(prob);
            boundingBoxes.add(box);
        }
        return new DetectedObjects(classNames, probabilities, boundingBoxes);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}


