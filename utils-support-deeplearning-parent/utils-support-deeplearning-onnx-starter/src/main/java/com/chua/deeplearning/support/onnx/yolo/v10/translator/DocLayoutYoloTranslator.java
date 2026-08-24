package com.chua.deeplearning.support.onnx.yolo.v10.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * DocLayout-YOLO                               DocStructBench             
 * <p>
 *        YOLOv10                                                                               
 *                https://github.com/opendatalab/DocLayout-YOLO
 *                https://github.com/RapidAI/RapidLayout
 *             DocStructBench
 * <p>
 * DocLayout-YOLO                                                                    YOLOv10          
 *                                                                                     
 * <p>
 * YOLOv10                            
 * -        shape: [1, num_boxes, 6]        [1, 300, 6]
 * -                : [x1, y1, x2, y2, confidence, class_id]
 * -                                              
 * - YOLOv10        NMS                                    
 * <p>
 *                         10         
 * - title:       
 * - plain text:       /         
 * - abandon:             /      
 * - figure:       
 * - figure_caption:             
 * - table:       
 * - table_caption:             
 * - table_footnote:             
 * - isolate_formula:             
 * - formula_caption:             
 * <p>
 *                
 * -                                   1024x1024   
 * -        DocStructBench                
 * -                                                                                     
 * -        NMS                  
 * <p>
 *                
 * - PDF             
 * -                      
 * -                   
 * -        OCR          
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/11/28
 */
@Slf4j
public class DocLayoutYoloTranslator implements Translator<Image, DetectedObjects> {

    /**
     * DocStructBench                               
     *          https://github.com/RapidAI/RapidLayout
     *          https://github.com/opendatalab/DocLayout-YOLO
     * <p>
     * 10                            
     */
public static final List<String> DOCSTRUCTBENCH_CLASSES = Arrays.asList(
            "title", // 0 -
            "plain_text", // 1 -
            "abandon", // 2 -
            "figure", // 3 -
            "figure_caption", // 4 -
            "table", // 5 -
            "table_caption", // 6 -
            "table_footnote", // 7 -
            "isolate_formula", // 8 -
            "formula" // 9 -
    );

    /**
     *                      DocLayout-YOLO        1024   
     */
    private static final int DEFAULT_INPUT_SIZE = 1280;

    /**
     *                      
     */
    private static final float DEFAULT_THRESHOLD = 0.2f;

    /**
     *                   
     */
    private final int inputSize;

    /**
     *                
     */
    private final float threshold;

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
     * 创建 Translator（支持外部阈值覆盖，未提供时使用内置默认值）。
     *
     * @param configuration 检测配置（可空）
     */
    public DocLayoutYoloTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        this(DEFAULT_INPUT_SIZE,
                configuration == null ? DEFAULT_THRESHOLD
                        : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, DEFAULT_THRESHOLD),
                DOCSTRUCTBENCH_CLASSES);
    }

/**
     *                    -                   
     */
    public DocLayoutYoloTranslator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD, DOCSTRUCTBENCH_CLASSES);
    }

    /**
     *              -                      
     *
     * @param inputSize                                  
     */
    public DocLayoutYoloTranslator(int inputSize) {
        this(inputSize, DEFAULT_THRESHOLD, DOCSTRUCTBENCH_CLASSES);
    }

    /**
     *              -                
     *
     * @param inputSize                   
     * @param threshold                
     */
    public DocLayoutYoloTranslator(int inputSize, float threshold) {
        this(inputSize, threshold, DOCSTRUCTBENCH_CLASSES);
    }

    /**
     *                    -                         
     *
     * @param inputSize                   
     * @param threshold                
     * @param classes               
     */
    public DocLayoutYoloTranslator(int inputSize, float threshold, List<String> classes) {
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.classes = new ArrayList<>(classes);
        this.normalizeCoordinates = true;
        log.info("          DocLayout-YOLO                             -             : {}x{},       : {},          : {}",
                inputSize, inputSize, threshold, classes.size());
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

        // 提取 BufferedImage，AWT 缩放至 inputSize（规避 DJL Image.resize 走 NDArray）
        Object wrapped = input.getWrappedImage();
        java.awt.image.BufferedImage src = wrapped instanceof java.awt.image.BufferedImage b
                ? b
                : (java.awt.image.BufferedImage) ai.djl.modality.cv.BufferedImageFactory.getInstance().fromImage(input).getWrappedImage();
        java.awt.image.BufferedImage resized = ImageUtils.resize(src, inputSize, inputSize, org.opencv.imgproc.Imgproc.INTER_CUBIC);

        // 提取 HWC 像素（RGB [0,255]），手动转 CHW + /255，规避 onnxruntime 不支持的 transpose/div
        int w = resized.getWidth();
        int h = resized.getHeight();
        int[] rgb = resized.getRGB(0, 0, w, h, null, 0, w);
        float[] chw = new float[3 * h * w];
        for (int i = 0; i < h * w; i++) {
            chw[i] = ((rgb[i] >> 16) & 0xFF) / 255f;
            chw[h * w + i] = ((rgb[i] >> 8) & 0xFF) / 255f;
            chw[2 * h * w + i] = (rgb[i] & 0xFF) / 255f;
        }

        NDArray array = ctx.getNDManager().create(chw, new ai.djl.ndarray.types.Shape(1, 3, h, w));

        if (log.isDebugEnabled()) {
            log.debug("DocLayout-YOLO 输入: shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    /**
     *                   
     * <p>
     * YOLOv10                       NMS      
     * -        shape: [1, num_boxes, 6]        [1, 300, 6]
     * -                : [x1, y1, x2, y2, confidence, class_id]
     * -                                              
     *
     * @param ctx                    
     * @param list              NDList
     * @return             
     * @throws Exception             
     */
    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("             DocLayout-YOLO             ");
        }

        // 输出 [1, num_boxes, 6]，用 toFloatArray 手动索引（规避 squeeze/get 不支持）
        NDArray output = list.get(0);
        long[] shape = output.getShape().getShape();
        int dims = shape.length;
        long numBoxes = dims >= 3 ? shape[1] : (dims >= 2 ? shape[0] : 1);
        long numFeatures = shape[dims - 1];
        float[] flat = output.toFloatArray();

        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            int base = i * (int) numFeatures;
            // YOLOv10 输出: [x1, y1, x2, y2, confidence, class_id]
            float x1 = flat[base];
            float y1 = flat[base + 1];
            float x2 = flat[base + 2];
            float y2 = flat[base + 3];
            float confidence = flat[base + 4];
            int classId = (int) flat[base + 5];

            //                      
            if (confidence < threshold) {
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
            if (x1 < 0 || y1 < 0 || x2 <= x1 || y2 <= y1) {
                if (log.isDebugEnabled()) {
                    log.debug("                  : x1={}, y1={}, x2={}, y2={}", x1, y1, x2, y2);
                }
                continue;
            }

            //                       [0, inputSize]
            x1 = Math.max(0, Math.min(inputSize, x1));
            y1 = Math.max(0, Math.min(inputSize, y1));
            x2 = Math.max(0, Math.min(inputSize, x2));
            y2 = Math.max(0, Math.min(inputSize, y2));

            //             
            float w = x2 - x1;
            float h = y2 - y1;

            //                                                    
            double scaleX = (double) imageWidth / inputSize;
            double scaleY = (double) imageHeight / inputSize;

            double origX = x1 * scaleX;
            double origY = y1 * scaleY;
            double origW = w * scaleX;
            double origH = h * scaleY;

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
                    i, classes.get(classId), String.format("%.3f", confidence),
                    String.format("%.3f", finalX), String.format("%.3f", finalY),
                    String.format("%.3f", finalW), String.format("%.3f", finalH));

            //                   
            boxes.add(new Rectangle(finalX, finalY, finalW, finalH));
            classNames.add(classes.get(classId));
            probabilities.add((double) confidence);
        }

        log.info("          {}                   ", boxes.size());

        return new DetectedObjects(classNames, probabilities, boxes);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     *        YOLO       
     *
     * @return "DocLayout-YOLO"
     */
    public String getYoloVersion() {
        return "DocLayout-YOLO";
    }

    /**
     *                   
     *
     * @return                   
     */
    public String getModelDescription() {
        return "DocLayout-YOLO Document Layout Detection (DocStructBench) - " +
                "Supports 10 document element types: " +
                "title, plain text, abandon, figure, figure_caption, table, table_caption, table_footnote, isolate_formula, formula_caption";
    }

    /**
     *                            
     *
     * @return                   
     */
    public static int[] getRecommendedSizes() {
        return new int[]{640, 800, 1024, 1280};
    }

    /**
     *                            
     *
     * @return DocStructBench                         
     */
    public static List<String> getSupportedClasses() {
        return DOCSTRUCTBENCH_CLASSES;
    }
}
