package com.chua.deeplearning.support.onnx.yolo.v8.translator;

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
import java.util.Arrays;
import java.util.List;


/**
 * YOLOv8                                  
 * <p>
 *        YOLOv8                                                                         
 *                https://github.com/CharleyXu/layout4j
 *                https://github.com/RapidAI/RapidLayout
 * <p>
 *          yolov8n_layout_general6
 * <p>
 * YOLOv8                            
 * -        shape: [1, 4+num_classes, num_boxes]        [1, 10, 8400]
 * -          : [num_boxes, 4+num_classes]        [8400, 10]
 * -     4              (cx, cy, w, h)
 * -                                                           confidence          
 * - confidence = max(class_scores)
 * <p>
 *                         6         
 * - Text:       /      
 * - Title:       
 * - Figure:       /      
 * - Table:       
 * - Caption:             /      
 * - Equation:       /      
 * <p>
 *                
 * -                                   640x640   
 * -           nano                   
 * -                                  
 * <p>
 *                
 * - PDF             
 * -                      
 * -                   
 * -        OCR          
 * -                                  
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/11/28
 */
@Slf4j
public class Yolo8GeneralLayoutTranslator implements Translator<Image, DetectedObjects> {

    /**
     *                                     
     *          https://github.com/CharleyXu/layout4j
     *          https://github.com/RapidAI/RapidLayout
     * <p>
     * 6                            
     */
    public static final List<String> GENERAL_LAYOUT_CLASSES = Arrays.asList(
        // // 0 -       /
        "Text",     
        // // 1 -
        "Title",    
        // // 2 -       /
        "Figure",   
        // // 3 -
        "Table",    
        // // 4 -             /
        "Caption",  
        // // 5 -       /
        "Equation"  
   );

    /**
     *                   
     */
    private static final int DEFAULT_INPUT_SIZE = 640;

    /**
     *                      
     */
    private static final float DEFAULT_THRESHOLD = 0.25f;

    /**
     *        NMS       
     */
    private static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    /**
     *                   
     */
    private final int inputSize;

    /**
     *                
     */
    private final float threshold;

    /**
     * NMS       
     */
    private final float nmsThreshold;

    /**
     *             
     */
    private final List<String> classes;

    /**
     *                      
     */
    private final boolean normalizeCoordinates;

    /**
     *                   
     */
    private int imageWidth;
    /** 图像高度 */
    /** 图片高度 */
    private int imageHeight;

    /**
     *                    -                   
     */
    public Yolo8GeneralLayoutTranslator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, GENERAL_LAYOUT_CLASSES);
    }

    /**
     *              -                      
     *
     * @param inputSize                                  
     */
    public Yolo8GeneralLayoutTranslator(int inputSize) {
        this(inputSize, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, GENERAL_LAYOUT_CLASSES);
    }

    /**
     *              -                
     *
     * @param inputSize                      
     * @param threshold                   
     * @param nmsThreshold NMS       
     */
    public Yolo8GeneralLayoutTranslator(int inputSize, float threshold, float nmsThreshold) {
        this(inputSize, threshold, nmsThreshold, GENERAL_LAYOUT_CLASSES);
    }

    /**
     *                    -                         
     *
     * @param inputSize                      
     * @param threshold                   
     * @param nmsThreshold NMS       
     * @param classes                  
     */
    public Yolo8GeneralLayoutTranslator(int inputSize, float threshold, float nmsThreshold, List<String> classes) {
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = new ArrayList<>(classes);
        this.normalizeCoordinates = true;
        log.info("          YOLOv8                                   -             : {}x{},       : {}, NMS: {},          : {}",
                 inputSize, inputSize, threshold, nmsThreshold, classes.size());
    }

    /**
     *                   
     *
     * @param ctx                     
     * @param input             
     * @return              NDList
     * @throws Exception             
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        //                         
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("                  : {}x{}", imageWidth, imageHeight);
        }

        //                                        
        Image resized = input.resize(inputSize, inputSize, true);

        //           NDArray             
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //              [0, 1]
        array = array.div(255.0f);

        //           CHW       
        array = array.transpose(2, 0, 1);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    /**
     *                   
     * <p>
     * YOLOv8                
     * -        shape: [1, 4+num_classes, num_boxes]        [1, 10, 8400]
     * -          : [num_boxes, 4+num_classes]        [8400, 10]
     * -     4              (cx, cy, w, h)
     * -                                        
     * - confidence = max(class_scores)
     *
     * @param ctx                    
     * @param list              NDList
     * @return             
     * @throws Exception             
     */
    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("             YOLOv8-GeneralLayout             ");
        }

        //             
        NDArray output = list.get(0);
        log.info("             shape: {}", output.getShape());

        //        batch       
        if (output.getShape().dimension() == 3) {
            output = output.squeeze(0);
        }

        //                   
        // YOLOv8             : [4+num_classes, num_boxes]        [10, 8400]
        //                : [num_boxes, 4+num_classes]        [8400, 10]
        long dim0 = output.getShape().get(0);
        long dim1 = output.getShape().get(1);

        log.info("squeeze     shape: [{}, {}]", dim0, dim1);

        //        dim0 < dim1             [num_features, num_boxes]                      
        if (dim0 < dim1) {
            if (log.isDebugEnabled()) {
                log.debug("          [num_features={}, num_boxes={}]                      ", dim0, dim1);
            }
            output = output.transpose();
            dim0 = output.getShape().get(0);
            dim1 = output.getShape().get(1);
        }

        long numBoxes = dim0;
        long numFeatures = dim1;
        log.info("          -                : {},             : {} (      : 4+{}={})", 
                numBoxes, numFeatures, classes.size(), 4 + classes.size());

        //                   
        int expectedFeatures = 4 + classes.size();
        if (numFeatures != expectedFeatures) {
            log.warn("                     :       ={},       ={} (4+{})", numFeatures, expectedFeatures, classes.size());
        }

        //                   
        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        //                      
        for (int i = 0; i < numBoxes; i++) {
            // YOLOv8             : [cx, cy, w, h, class_score_0, class_score_1, ..., class_score_n]
            float cx = output.get(i, 0).getFloat();
            float cy = output.get(i, 1).getFloat();
            float w = output.get(i, 2).getFloat();
            float h = output.get(i, 3).getFloat();

            //                                   confidence
            int classId = 0;
            float maxClassScore = 0f;

            for (int c = 0; c < classes.size(); c++) {
                float classScore = output.get(i, 4 + c).getFloat();
                if (classScore > maxClassScore) {
                    maxClassScore = classScore;
                    classId = c;
                }
            }

            //                      
            if (maxClassScore < threshold) {
                continue;
            }

            //             ID         
            if (classId < 0 || classId >= classes.size()) {
                if (log.isDebugEnabled()) {
                    log.debug("                  ID: classId={},             ={}", classId, classes.size());
                }
                continue;
            }

            //                      
            if (cx < 0 || cy < 0 || w <= 0 || h <= 0) {
                if (log.isDebugEnabled()) {
                    log.debug("                  : cx={}, cy={}, w={}, h={}", cx, cy, w, h);
                }
                continue;
            }

            //                                     +                              +               
            double x0 = cx - w / 2.0;
            double y0 = cy - h / 2.0;
            double x1 = cx + w / 2.0;
            double y1 = cy + h / 2.0;

            //                       [0, inputSize]
            x0 = Math.max(0, Math.min(inputSize, x0));
            y0 = Math.max(0, Math.min(inputSize, y0));
            x1 = Math.max(0, Math.min(inputSize, x1));
            y1 = Math.max(0, Math.min(inputSize, y1));

            //                                                    
            double scaleX = (double) imageWidth / inputSize;
            double scaleY = (double) imageHeight / inputSize;

            double origX = x0 * scaleX;
            double origY = y0 * scaleY;
            double origW = (x1 - x0) * scaleX;
            double origH = (y1 - y0) * scaleY;

            //                            
            origX = Math.max(0, Math.min(imageWidth - 1, origX));
            origY = Math.max(0, Math.min(imageHeight - 1, origY));
            origW = Math.min(imageWidth - origX, origW);
            origH = Math.min(imageHeight - origY, origH);

            //                   
            if (origW <= 0 || origH <= 0) {
                if (log.isDebugEnabled()) {
                    log.debug("                              : w={}, h={}", origW, origH);
                }
                continue;
            }

            //                            
            double finalX, finalY, finalW, finalH;

            if (normalizeCoordinates) {
                //                 [0, 1]
                finalX = origX / imageWidth;
                finalY = origY / imageHeight;
                finalW = origW / imageWidth;
                finalH = origH / imageHeight;

                //                          [0, 1]          
                finalX = Math.max(0.0, Math.min(1.0, finalX));
                finalY = Math.max(0.0, Math.min(1.0, finalY));
                finalW = Math.max(0.0, Math.min(1.0 - finalX, finalW));
                finalH = Math.max(0.0, Math.min(1.0 - finalY, finalH));
            } else {
                //             
                finalX = origX;
                finalY = origY;
                finalW = origW;
                finalH = origH;
            }

            log.debug("               [{}]: class={}, confidence={},       ({}, {}, {}, {})",
                    i, classes.get(classId), String.format("%.3f", maxClassScore),
                    String.format("%.1f", finalX), String.format("%.1f", finalY),
                    String.format("%.1f", finalW), String.format("%.1f", finalH));

            //                   
            boxes.add(new Rectangle(finalX, finalY, finalW, finalH));
            classNames.add(classes.get(classId));
            probabilities.add((double) maxClassScore);
        }

        log.info("          {}                       NMS       ", boxes.size());

        //        NMS
        List<Integer> keepIndices = nms(boxes, probabilities, nmsThreshold);
        log.info("NMS           {}             ", keepIndices.size());

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
     * Non-Maximum Suppression (NMS)
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
     *                          IoU
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

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     *        YOLO       
     *
     * @return "YOLOv8-GeneralLayout"
     */
    public String getYoloVersion() {
        return "YOLOv8-GeneralLayout";
    }

    /**
     *                   
     *
     * @return                   
     */
    public String getModelDescription() {
        return "YOLOv8 General Document Layout Detection (layout4j) - " +
               "Supports 6 document element types: " +
               "Text, Title, Figure, Table, Caption, Equation";
    }

    /**
     *                            
     *
     * @return                   
     */
    public static int[] getRecommendedSizes() {
        return new int[]{640, 800, 1024};
    }

    /**
     *                            
     *
     * @return                               
     */
    public static List<String> getSupportedClasses() {
        return GENERAL_LAYOUT_CLASSES;
    }
}
