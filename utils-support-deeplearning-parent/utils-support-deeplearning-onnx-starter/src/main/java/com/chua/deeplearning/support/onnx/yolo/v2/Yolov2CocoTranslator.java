package com.chua.deeplearning.support.onnx.yolo.v2;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;


/**
* yolov2-COCO
* <p>
* ONNX Runtime     yolov2 COCO
* <p>
*                
* -                416x416                608x608   
* -                                                    
* -             COCO   80             
* - 锚栓 Boxes          5                 锚栓 boxes
* <p>
*                
* -                [1, 3, 416, 416] - NCHW
* -                [1, 125, 13, 13] - (5 * (5 + 80))
* -                80
* - 锚栓          5
* <p>
*                
* -                YOLO9000: Better, Faster, Stronger
* - ONNX          https://github.com/onnx/models/tree/main/validated/vision/object_detection_segmentation/yolov2-coco
*
* @author CH
* @版本 4.0.0.32
* @since 2024/11/08
 */
@Slf4j
public class Yolov2CocoTranslator implements Translator<Image, DetectedObjects> {

    /**
    *                                        
    */
    private final int inputSize;

    /**
    *                
    */
    private final float threshold;

    /**
    * NMS (Non-Maximum Suppression)       
    */
    private final float nmsThreshold;

    /**
    * COCO                      
    */
    private static final int NUM_CLASSES = 80;

    /**
    * 锚栓 boxes
    */
    private static final int NUM_ANCHORS = 5;

    /**
    *                      13x13   
    */
    private static final int GRID_SIZE = 13;

    /**
    * yolov2     锚栓 boxes         ,
    * COCO                                   锚栓
    */
private static final float[][] ANCHORS = {
        {1.3221f, 1.73145f}, // 锚栓 0
        {3.19275f, 4.00944f}, // 锚栓 1
        {5.05587f, 8.09892f}, // 锚栓 2
        {9.47112f, 4.84053f}, // 锚栓 3
        {11.2364f, 10.0071f} // 锚栓 4
};

    /**
    * COCO                      
    */
    private final List<String> classes;

    /**
    *                                     
    */
    private int imageWidth;
    /** 图像高度 */
    /** 图片高度 */
    private int imageHeight;

    /**
    *                                     
    */
    public Yolov2CocoTranslator() {
        this(416, 0.3f, 0.45f);
    }

    /**
    *             
    *
    * @param inputSize                      
    * @param threshold                   
    * @param nmsThreshold NMS       
    */
    public Yolov2CocoTranslator(int inputSize, float threshold, float nmsThreshold) {
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = loadCocoClasses();

        log.info("YOLOv2-COCO                         ");
        log.info("  -             : {}x{}", inputSize, inputSize);
        log.info("  -                : {}", threshold);
        log.info("  - NMS       : {}", nmsThreshold);
        log.info("  -             : {}", NUM_CLASSES);
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        //                         
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("                  : {}x{}", imageWidth, imageHeight);
        }

        //                                        
        Image resized = input.resize(inputSize, inputSize, true);

 // ndarray
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //              [0, 1]
        array = array.div(255.0f);

        //           CHW       
        array = array.transpose(2, 0, 1);

 // 批量          Batchifier.STACK
        // array = array.expandDims(0);  //                                   batch       

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        ");
        }

        //                [1, 125, 13, 13]
        // 125 = 5 anchors * (5 + 80) = 5 * (tx, ty, tw, th, confidence + 80 classes)
        NDArray output = list.getFirst();
        if (log.isDebugEnabled()) {
            log.debug("       shape: {}", output.getShape());
        }

 // 批量          [125, 13, 13]
        output = output.squeeze(0);

        //                   
        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        //                               
        for (int cy = 0; cy < GRID_SIZE; cy++) {
            for (int cx = 0; cx < GRID_SIZE; cx++) {
 // 锚栓 box
                for (int b = 0; b < NUM_ANCHORS; b++) {
                    //           125                         
                    int baseIndex = b * (5 + NUM_CLASSES);

                    //                      
                    float tx = output.get(baseIndex + 0, cy, cx).getFloat();
                    float ty = output.get(baseIndex + 1, cy, cx).getFloat();
                    float tw = output.get(baseIndex + 2, cy, cx).getFloat();
                    float th = output.get(baseIndex + 3, cy, cx).getFloat();
                    float confidence = sigmoid(output.get(baseIndex + 4, cy, cx).getFloat());

                    //                      
                    if (confidence < threshold) {
                        continue;
                    }

                    //                            
                    float bx = (sigmoid(tx) + cx) / GRID_SIZE;
                    float by = (sigmoid(ty) + cy) / GRID_SIZE;
                    float bw = (float) (Math.exp(tw) * ANCHORS[b][0] / GRID_SIZE);
                    float bh = (float) (Math.exp(th) * ANCHORS[b][1] / GRID_SIZE);

                    //                   
                    int bestClass = -1;
                    float bestClassProb = 0.0f;

                    for (int c = 0; c < NUM_CLASSES; c++) {
                        float classProb = sigmoid(output.get(baseIndex + 5 + c, cy, cx).getFloat());
                        if (classProb > bestClassProb) {
                            bestClassProb = classProb;
                            bestClass = c;
                        }
                    }

                    //                   
                    float score = confidence * bestClassProb;

                    //                   
                    if (score < threshold || bestClass == -1) {
                        continue;
                    }

                    //                                     
                    float xmin = Math.max(0, bx - bw / 2);
                    float ymin = Math.max(0, by - bh / 2);
                    float width = Math.min(1 - xmin, bw);
                    float height = Math.min(1 - ymin, bh);

                    //                   
                    boxes.add(new Rectangle(xmin, ymin, width, height));
                    classNames.add(classes.get(bestClass));
                    probabilities.add((double) score);
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("          {}                       NMS       ", boxes.size());
        }

        //        NMS
        List<Integer> keepIndices = nms(boxes, probabilities, nmsThreshold);
        if (log.isDebugEnabled()) {
            log.debug("NMS           {}             ", keepIndices.size());
        }

        //                   
        List<String> finalNames = new ArrayList<>();
        List<Double> finalProbs = new ArrayList<>();
        List<BoundingBox> finalBoxes = new ArrayList<>();

        for (int idx : keepIndices) {
            finalNames.add(classNames.get(idx));
            finalProbs.add(probabilities.get(idx));
            finalBoxes.add(boxes.get(idx));
        }

        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    /**
    * Sigmoid             
    *
    * @param x          
    * @return sigmoid(x)
    */
    private float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /**
    * Non-Maximum Suppression (NMS)
    * <p>
    *                                                 
    *
    * @param boxes                       
    * @param probabilities             
    * @param nmsThreshold  NMS       
    * @return                      
    */
    private List<Integer> nms(List<BoundingBox> boxes, List<Double> probabilities, float nmsThreshold) {
        List<Integer> indices = new ArrayList<>();
        if (boxes.isEmpty()) {
            return indices;
        }

        //                      
        List<Integer> sortedIndices = new ArrayList<>();
        for (int i = 0; i < probabilities.size(); i++) {
            sortedIndices.add(i);
        }
        sortedIndices.sort((i1, i2) -> Double.compare(probabilities.get(i2), probabilities.get(i1)));

        // NMS       
        boolean[] suppressed = new boolean[boxes.size()];

        for (int idx : sortedIndices) {
            if (suppressed[idx]) {
                continue;
            }

            indices.add(idx);
            BoundingBox box1 = boxes.get(idx);

            for (int j = 0; j < boxes.size(); j++) {
                if (j == idx || suppressed[j]) {
                    continue;
                }

                BoundingBox box2 = boxes.get(j);
                double iou = calculateIoU(box1, box2);

                if (iou > nmsThreshold) {
                    suppressed[j] = true;
                }
            }
        }

        return indices;
    }

    /**
    * iou (Intersection over Union)
    *
    * @param box1           1
    * @param box2           2
    * @return IoU    
    */
    private double calculateIoU(BoundingBox box1, BoundingBox box2) {
        Rectangle rect1 = box1.getBounds();
        Rectangle rect2 = box2.getBounds();

        double x1 = Math.max(rect1.getX(), rect2.getX());
        double y1 = Math.max(rect1.getY(), rect2.getY());
        double x2 = Math.min(rect1.getX() + rect1.getWidth(), rect2.getX() + rect2.getWidth());
        double y2 = Math.min(rect1.getY() + rect1.getHeight(), rect2.getY() + rect2.getHeight());

        double intersectionArea = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double box1Area = rect1.getWidth() * rect1.getHeight();
        double box2Area = rect2.getWidth() * rect2.getHeight();
        double unionArea = box1Area + box2Area - intersectionArea;

        return unionArea > 0 ? intersectionArea / unionArea : 0;
    }

    /**
    *        COCO                      
    *
    * @return                   
    */
    private List<String> loadCocoClasses() {
        // COCO           80          
        String[] cocoClasses = {
                "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
                "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
                "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
                "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
                "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
                "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
                "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
                "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
                "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
                "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
                "toothbrush"
        };

        List<String> classList = new ArrayList<>();
        for (String className : cocoClasses) {
            classList.add(className);
        }

        if (log.isDebugEnabled()) {
            log.debug("          {}     COCO       ", classList.size());
        }
        return classList;
    }

    /**
    *                   
    * <p>
    * STACK                    批量
    * 处理输入        [C, H, W] = [3, 416, 416]
    * Batchifier.STACK                    [1, C, H, W] = [1, 3, 416, 416]
    *
    * @return STACK             
    */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}

