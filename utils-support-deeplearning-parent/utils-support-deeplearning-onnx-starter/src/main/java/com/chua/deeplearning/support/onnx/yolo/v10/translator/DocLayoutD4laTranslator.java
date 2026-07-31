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
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * DocLayout-YOLO D4LA                            
 * <p>
 *        YOLOv10                                            D4LA                
 *                https://github.com/opendatalab/DocLayout-YOLO
 *                https://github.com/RapidAI/RapidLayout
 *             D4LA (Document for Layout Analysis)
 * <p>
 * D4LA                   
 * -        11,092                            
 * -        IIT-CDIP          
 * -        12                
 * -           27          
 * <p>
 * YOLOv10                            
 * -        shape: [1, num_boxes, 6]        [1, 300, 6]
 * -                : [x1, y1, x2, y2, confidence, class_id]
 * -                                              
 * - YOLOv10        NMS                                    
 * <p>
 *                         27         
 * - DocTitle:             
 * - ParaTitle:             
 * - ParaText:             
 * - ListText:             
 * - RegionTitle:             
 * - Date:       
 * - LetterHead:       
 * - LetterDear:       
 * - LetterSign:       
 * - Question:       
 * - OtherText:             
 * - RegionKV:             
 * - RegionList:             
 * - Abstract:       
 * - Author:       
 * - TableName:             
 * - Table:       
 * - Figure:       
 * - FigureName:             
 * - Equation:       
 * - Reference:             
 * - Footer:       
 * - PageHeader:       
 * - PageFooter:       
 * - Number:       
 * - Catalog:       
 * - PageNumber:       
 * <p>
 *                
 * -                   
 * -                      
 * -                                              
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/11/28
 */
@Slf4j
public class DocLayoutD4laTranslator implements Translator<Image, DetectedObjects> {

    /**
     * D4LA                                  27      
     *          https://github.com/RapidAI/RapidLayout
     * <p>
     *                                           
     */
public static final List<String> D4LA_CLASSES = Arrays.asList(
            "DocTitle",      // 0 -
            "ParaTitle",     // 1 -
            "ParaText",      // 2 -
            "ListText",      // 3 -
            "RegionTitle",   // 4 -
            "Date",          // 5 -
            "LetterHead",    // 6 -
            "LetterDear",    // 7 -
            "LetterSign",    // 8 -
            "Letter",        // 9 -
            "Question",      // 10 -
            "OtherText",     // 11 -
            "RegionKV",      // 12 -
            "RegionList",    // 13 -
            "RegionCt",      // 14 -
            "Abstract",      // 15 -
            "Author",        // 16 -
            "TableName",     // 17 -
            "Table",         // 18 -
            "Figure",        // 19 -
            "FigureName",    // 20 -
            "Equation",      // 21 -
            "Reference",     // 22 -
            "Footer",        // 23 -
            "PageHeader",    // 24 -
            "PageFooter",    // 25 -
            "PageNumber",    // 26 -
            "Number",        // 27 -
            "Catalog"        // 28 -
    );

    /**
     *                   
     */
    private static final int DEFAULT_INPUT_SIZE = 1600;

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
    private int imageHeight;

    /**
     *                    -                   
     */
    public DocLayoutD4laTranslator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD, D4LA_CLASSES);
    }

    /**
     *              -                      
     *
     * @param inputSize                                  
     */
    public DocLayoutD4laTranslator(int inputSize) {
        this(inputSize, DEFAULT_THRESHOLD, D4LA_CLASSES);
    }

    /**
     *              -                
     *
     * @param inputSize                   
     * @param threshold                
     */
    public DocLayoutD4laTranslator(int inputSize, float threshold) {
        this(inputSize, threshold, D4LA_CLASSES);
    }

    /**
     *                    -                         
     *
     * @param inputSize                   
     * @param threshold                
     * @param classes               
     */
    public DocLayoutD4laTranslator(int inputSize, float threshold, List<String> classes) {
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.classes = new ArrayList<>(classes);
        this.normalizeCoordinates = true;
        log.info("          DocLayout-YOLO D4LA                             -             : {}x{},       : {},          : {}",
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
            log.debug("             DocLayout-YOLO D4LA             ");
        }

        //             
        NDArray output = list.get(0);
        log.info("             shape: {}", output.getShape());

        //        batch       
        if (output.getShape().dimension() == 3) {
            output = output.squeeze(0);
        }

        long numBoxes = output.getShape().get(0);
        long numFeatures = output.getShape().get(1);
        log.info("               : {},             : {}", numBoxes, numFeatures);

        //                   
        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        //                      
        for (int i = 0; i < numBoxes; i++) {
            // YOLOv10             : [x1, y1, x2, y2, confidence, class_id]
            float x1 = output.get(i, 0).getFloat();
            float y1 = output.get(i, 1).getFloat();
            float x2 = output.get(i, 2).getFloat();
            float y2 = output.get(i, 3).getFloat();
            float confidence = output.get(i, 4).getFloat();
            int classId = (int) output.get(i, 5).getFloat();

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
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     *                            
     *
     * @return D4LA                         
     */
    public static List<String> getSupportedClasses() {
        return D4LA_CLASSES;
    }
}
