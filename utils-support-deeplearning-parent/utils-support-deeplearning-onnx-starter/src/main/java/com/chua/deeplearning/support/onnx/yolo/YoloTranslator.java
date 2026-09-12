package com.chua.deeplearning.support.onnx.yolo;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
* YOLO                                  
* <p>
* yolov8, yolov10, yolov11, yolov12
*                 ONNX             /                  
* <p>
*                
* -                  ONNX                                        
* -                                      
* -                                                vs                   
* -                                            
* -               NMS                                 
* <p>
*           ONNX                
* <p>
* 1   [1, num_特征, num_boxes] -
* -          [1, 5, 8400]     [1, 5+num_classes, num_boxes]
* -                                                       640x640   
* -                      2      
* <p>
* 2   [1, num_boxes, num_特征] -
* -          [1, 8400, 5]     [1, num_boxes, 5+num_classes]
* -                                              
* -          (cx, cy, w, h, 信心, [类_scores...])
* <p>
*                      
* <pre>{@code
* // YOLOv10
* public class Yolo10Translator extends YoloTranslator {
*     public Yolo10Translator(int inputSize, float threshold, float nmsThreshold, List<String> classes) {
*         super(inputSize, threshold, nmsThreshold, classes, true);
*     }
* }
*
* // YOLOv11
* public class Yolo11Translator extends YoloTranslator {
*     public Yolo11Translator(int inputSize, float threshold, float nmsThreshold, List<String> classes) {
*         super(inputSize, threshold, nmsThreshold, classes, true);
*     }
* }
*
* // YOLOv12
* public class Yolo12Translator extends YoloTranslator {
*     public Yolo12Translator(int inputSize, float threshold, float nmsThreshold, List<String> classes) {
*         super(inputSize, threshold, nmsThreshold, classes, true);
*     }
* }
* }</pre><String> 类) {
* 父(输入大小, 阈值, nms阈值, 类, true);
*     }
* }
* }</pre>
*
* @author CH
* @版本 4.0.0.32
* @since 2025/11/17
 */
@Slf4j
class YoloTranslator implements Translator<Image, DetectedObjects> {

    /** COCO 数据集 80 个类别名称 */
    /** Coco_80_classes */
    private static final List<String> COCO_80_CLASSES = Arrays.asList(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
    );

    /**
    *                                        
     */
    protected final int inputSize;

    /**
    *                
     */
    protected final float threshold;

    /**
    * NMS (Non-Maximum Suppression)       
     */
    protected final float nmsThreshold;

    /**
    *                   
     */
    protected final List<String> classes;

    /**
    *                             [0, 1]
    * true:                       (0-1)                              
    * false:                    (0-镜像width/镜像height)
     */
    protected final boolean normalizeCoordinates;

    /**
    *                                     
     */
    protected int imageWidth;

    /**
    *                                     
     */
    protected int imageHeight;

    /**
    *                               
    *
    * @param inputSize                               
    * @param threshold                            
    * @param nmsThreshold          NMS       
    * @param classes                                 
    * @param normalizeCoordinates                              [0, 1]
     */
    protected YoloTranslator(int inputSize, float threshold, float nmsThreshold, List<String> classes,
                            boolean normalizeCoordinates) {
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = new ArrayList<>(classes);
        this.normalizeCoordinates = normalizeCoordinates;

        log.info("{}                                     ", getYoloVersion());
        log.info("  -             : {}x{}", inputSize, inputSize);
        log.info("  -                : {}", threshold);
        log.info("  - NMS       : {}", nmsThreshold);
        log.info("  -             : {}", this.classes.size());
        log.info("  -             : {}", normalizeCoordinates ? "          [0, 1]" : "            ");
        if (log.isDebugEnabled()) {
            log.debug("  -             : {}", this.classes);
        }
    }

    /**
    *        YOLO                                  
    *
    * @return                       "YOLOv8", "yolov10", "yolov11", "yolov12"
     */
    protected String getYoloVersion() {
        return "YOLO";
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

        // OpenCVImage                    transpose/div     NDArrayAdapter                                           HWC->CHW          
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR).toType(DataType.FLOAT32, false);
        array = toNormalizedChw(ctx, array);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    /**
    * 转为normalizedchw
    *
    * @param ctx ctx
    * @param array array
    * @return 转为normalizedchw的结果
     */
    private NDArray toNormalizedChw(TranslatorContext ctx, NDArray array) {
        Shape shape = array.getShape();
        if (shape.dimension() != 3) {
            throw new IllegalArgumentException("YOLO                          HWC                       shape=" + shape);
        }

        int height = Math.toIntExact(shape.get(0));
        int width = Math.toIntExact(shape.get(1));
        int channels = Math.toIntExact(shape.get(2));
        float[] source = array.toFloatArray();
        float[] chw = new float[source.length];
        int planeSize = height * width;

        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int hwOffset = h * width + w;
                int sourceOffset = hwOffset * channels;
                for (int c = 0; c < channels; c++) {
                    chw[c * planeSize + hwOffset] = source[sourceOffset + c] / 255.0f;
                }
            }
        }

        return ctx.getNDManager().create(chw, new Shape(channels, height, width));
    }

    /**
    *                   
    * <p>
    *              ONNX                
    * 1. [1, num_特征, num_boxes] = [1, 5, 8400] -
    * 2. [1, num_boxes, num_特征] = [1, 8400, 5+num_类] -
    * <p>
    *                
    * 1.                                                 
    * 2.                                              
    * 3.                                                    
    * 4.                                [0, 1]
    * 5.        NMS                      
    *
    * @param ctx                    
    * @param list              nd列表
    * @return             
    * @throws Exception             
     */
    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("             {}             ", getYoloVersion());
        }

        //             
        NDArray output = list.get(0);
        Shape outputShape = output.getShape();
        log.info("       shape: {}", outputShape);
        if (log.isDebugEnabled()) {
            log.debug("                  : {}", output.getDataType());
        }

        int rows;
        int cols;
        if (outputShape.dimension() == 3 && outputShape.get(0) == 1) {
            rows = Math.toIntExact(outputShape.get(1));
            cols = Math.toIntExact(outputShape.get(2));
        } else if (outputShape.dimension() == 2) {
            rows = Math.toIntExact(outputShape.get(0));
            cols = Math.toIntExact(outputShape.get(1));
        } else {
            throw new IllegalArgumentException("YOLO                 [1, F, N]     [N, F]                 shape=" + outputShape);
        }
        float[] outputData = output.toFloatArray();

        //                                           
        long dim0 = rows;
        long dim1 = cols;
        boolean transposedView = false;

        //        dim0 < dim1             [num_features, num_boxes]                          [num_boxes, num_features]
        if (dim0 < dim1) {
            if (log.isDebugEnabled()) {
                log.debug("          [num_features={}, num_boxes={}]                      ", dim0, dim1);
            }
            transposedView = true;
            long temp = dim0;
            dim0 = dim1;
            dim1 = temp;
        }

        long numBoxes = dim0;
        long numFeatures = dim1;
        log.info("          -                : {},             : {}", numBoxes, numFeatures);

        if (numFeatures < 4) {
            log.warn("                  : {}                4 (cx, cy, w, h)", numFeatures);
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        boolean hasObjectness = numFeatures == classes.size() + 5;
        int classStartIndex = hasObjectness ? 5 : 4;

        //                   
        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        //                      
        for (int i = 0; i < numBoxes; i++) {
            // YOLO                               
 // 1. [cx, cy, w, h, 对象, 类_scores...]
            // 2. [cx, cy, w, h, class_scores...] (YOLOv8 official ONNX)
            float cx = readMatrixValue(outputData, rows, cols, transposedView, i, 0);
            float cy = readMatrixValue(outputData, rows, cols, transposedView, i, 1);
            float w = readMatrixValue(outputData, rows, cols, transposedView, i, 2);
            float h = readMatrixValue(outputData, rows, cols, transposedView, i, 3);

            int classId = 0;
            float objectness = hasObjectness ? readMatrixValue(outputData, rows, cols, transposedView, i, 4) : 1.0f;
            float maxClassScore = hasObjectness && numFeatures == 5 ? 1.0f : 0f;

            if (numFeatures > classStartIndex) {
                for (int c = classStartIndex; c < numFeatures; c++) {
                    float classScore = readMatrixValue(outputData, rows, cols, transposedView, i, c);
                    if (classScore > maxClassScore) {
                        maxClassScore = classScore;
                        classId = c - classStartIndex;
                    }
                }
            }

            float confidence = hasObjectness ? objectness * maxClassScore : maxClassScore;
            if (confidence < threshold) {
                continue;
            }

            String className = resolveClassName(classId, numFeatures, classStartIndex);
            if (className == null) {
                if (log.isDebugEnabled()) {
                    log.debug("                  ID: classId={},             ={},             ={}",
                            classId, classes.size(), numFeatures);
                }
                continue;
            }

 // 输入大小
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

 // [0, 输入大小]
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
                    i, className,
                    String.format("%.3f", confidence),
                    String.format("%.1f", finalX), String.format("%.1f", finalY),
                    String.format("%.1f", finalW), String.format("%.1f", finalH));

            //                   
            boxes.add(new Rectangle(finalX, finalY, finalW, finalH));
            classNames.add(className);
            probabilities.add((double) confidence);
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
    * <p>
    *                                                 
    *
    * @param boxes                       
    * @param probabilities             
    * @param nmsThreshold  NMS       
    * @return                      
     */
    protected List<Integer> nms(List<BoundingBox> boxes, List<Double> probabilities, float nmsThreshold) {
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
    protected double calculateIoU(BoundingBox box1, BoundingBox box2) {
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
    *                   
    *
    * @return STACK             
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * materializearray
    *
    * @param ctx ctx
    * @param array array
    * @return materializeArray的结果
     */
    protected NDArray materializeArray(TranslatorContext ctx, NDArray array) {
        return ctx.getNDManager().create(array.toFloatArray(), array.getShape());
    }

    /**
    * Transposed
    *
    * @param ctx ctx
    * @param array array
    * @return transpose2d的结果
     */
    protected NDArray transpose2d(TranslatorContext ctx, NDArray array) {
        Shape shape = array.getShape();
        if (shape.dimension() != 2) {
            throw new IllegalArgumentException("                                     shape=" + shape);
        }

        int rows = Math.toIntExact(shape.get(0));
        int cols = Math.toIntExact(shape.get(1));
        float[] source = array.toFloatArray();
        float[] transposed = new float[source.length];

        for (int row = 0; row < rows; row++) {
            int rowOffset = row * cols;
            for (int col = 0; col < cols; col++) {
                transposed[col * rows + row] = source[rowOffset + col];
            }
        }

        return ctx.getNDManager().create(transposed, new Shape(cols, rows));
    }

    /**
    * 读取matrix值
    *
    * @param data 数据
    * @param rows rows
    * @param cols cols
    * @param transposedView transposedview
    * @param row row
    * @param col col
    * @return 读取matrix值的结果
     */
    protected float readMatrixValue(float[] data, int rows, int cols, boolean transposedView, long row, long col) {
        int rowIndex = Math.toIntExact(row);
        int colIndex = Math.toIntExact(col);
        return transposedView
                ? data[colIndex * cols + rowIndex]
                : data[rowIndex * cols + colIndex];
    }

    /**
    * 解析类名称
    *
    * @param classId 类标识
    * @param numFeatures num特征
    * @param classStartIndex 类启动索引
    * @return resolve类名称的结果
     */
    private String resolveClassName(int classId, long numFeatures, int classStartIndex) {
        if (classId >= 0 && classId < classes.size()) {
            return classes.get(classId);
        }
        if (classId < 0) {
            return null;
        }
        if (classes.size() == 1 && "object".equalsIgnoreCase(classes.get(0))) {
            int inferredClassCount = Math.max(0, (int) numFeatures - classStartIndex);
            if (inferredClassCount == COCO_80_CLASSES.size() && classId < COCO_80_CLASSES.size()) {
                return COCO_80_CLASSES.get(classId);
            }
            return "object-" + classId;
        }
        return null;
    }
}
