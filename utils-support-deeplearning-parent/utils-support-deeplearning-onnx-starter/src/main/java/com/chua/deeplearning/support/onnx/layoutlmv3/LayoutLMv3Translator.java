package com.chua.deeplearning.support.onnx.layoutlmv3;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;


/**
 * LayoutLMv3                    Translator
 * <p>
 *                            Document Layout Analysis                                                                                 
 * </p>
 * <p>
 *        Hugging Face                LayoutLMv3                               
 * -          pixel_values            RGB                     + input_ids         token   + bbox                           0-1000   
 * -          logits                               (batch_size, sequence_length, num_labels)     (batch_size, num_labels)
 * </p>
 * <p>
 *                 ONNX                                                                                                                      
 * </p>
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
@Spi("layoutlmv3")
public class LayoutLMv3Translator implements Translator<Image, LayoutLMv3Result> {

    /**
     *                             LayoutLMv3Config             224   
     */
    private static final int IMAGE_SIZE = 224;
    
    /**
     *                            LayoutLMv3        0-1000          
     */
    private static final int BBOX_NORMALIZE_RANGE = 1000;

    /**
     *                
     *
     * @param ctx                   
     * @throws Exception                         
     */
    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        log.info("[LayoutLMv3][         ]                                        ");
    }

    /**
     *             
     * <p>
     *                                              
     * </p>
     *
     * @param ctx                     
     * @param image             
     * @return NDList                   
     * @throws Exception                      
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image image) throws Exception {
        var manager = ctx.getNDManager();
        
        //                   
        var pixelValues = processImage(manager, image);
        
        return new NDList(pixelValues);
    }

    /**
     *             
     * <p>
     *                                           
     * </p>
     * <p>
     *        Hugging Face                LayoutLMv3                         
     * - logits             (batch_size, sequence_length, num_labels)     (batch_size, num_labels)
     * -        softmax + argmax                   
     * </p>
     * <p>
     *        ONNX                                                                   
     * - [batch_size, num_regions, 5]     [num_regions, 5]                      [x0, y0, x1, y1, class_id]
     * - [batch_size, num_regions, num_classes]                              
     * </p>
     *
     * @param ctx                    
     * @param list                   
     * @return LayoutLMv3                                              
     * @throws Exception                      
     */
    @Override
    public LayoutLMv3Result processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var regions = new ArrayList<DocumentRegion>();
        var output = list.singletonOrThrow();
        var shape = output.getShape();
        var manager = ctx.getNDManager();
        
        //                                     
        if (shape.dimension() == 3) {
            var batchSize = (int) shape.get(0);
            var dim1 = (int) shape.get(1);
            var dim2 = (int) shape.get(2);
            
            //       1          logits        [batch_size, sequence_length, num_labels]
            if (dim2 > 10) {
                //           logits          softmax + argmax
                log.info("[LayoutLMv3][            ]           logits       : [{}x{}x{}]", batchSize, dim1, dim2);
                processLogitsOutput(manager, output, regions, batchSize, dim1, dim2);
            } 
            //       2                      [batch_size, num_regions, 5]
            else if (dim2 == 5) {
                log.info("[LayoutLMv3][            ]                            : [{}x{}x{}]", batchSize, dim1, dim2);
                processRegionListOutput(output, regions, batchSize, dim1);
            } else {
                log.warn("[LayoutLMv3][            ]          3               : [{}x{}x{}]", batchSize, dim1, dim2);
            }
        } else if(shape.dimension() == 2) {
            var dim1 = (int) shape.get(0);
            var dim2 = (int) shape.get(1);
            
            //       1          logits        [sequence_length, num_labels]     [num_labels]
            if (dim2 > 10) {
                log.info("[LayoutLMv3][            ]           logits       : [{}x{}]", dim1, dim2);
                processLogitsOutput(manager, output, regions, 1, dim1, dim2);
            } 
            //       2                      [num_regions, 5]
            else if (dim2 == 5) {
                log.info("[LayoutLMv3][            ]                            : [{}x{}]", dim1, dim2);
                processRegionListOutput(output, regions, 1, dim1);
            } else {
                log.warn("[LayoutLMv3][            ]          2               : [{}x{}]", dim1, dim2);
            }
        } else {
            log.warn("[LayoutLMv3][            ]                         : {}", shape.dimension());
        }
        
        return new LayoutLMv3Result(regions);
    }
    
    /**
     *        logits                          LayoutLMv3          
     * <p>
     *     logits        softmax + argmax             
     * </p>
     *
     * @param manager  NDManager       
     * @param output               
     * @param regions                                
     * @param batchSize             
     * @param sequenceLength             
     * @param numLabels             
     */
    private void processLogitsOutput(NDManager manager, NDArray output, 
                                     ArrayList<DocumentRegion> regions, 
                                     int batchSize, int sequenceLength, int numLabels) {
        //                         
        var batchOutput = batchSize > 1 ? output.get(0) : output;
        
        //        softmax
        var expOutput = batchOutput.exp();
        var sumExp = expOutput.sum(new int[]{1}, true);
        var softmaxOutput = expOutput.div(sumExp);
        
        //                               argmax   
        var classIds = softmaxOutput.argMax(1);
        
        //                                     
        var classIdArray = classIds.toLongArray();
        var softmaxArray = softmaxOutput.toFloatArray();
        
        //          logits                                                             
        //                                      token                                        
        log.warn("[LayoutLMv3][            ] logits                                                                ");
        
        //                                                                   
        for (int i = 0; i < Math.min(sequenceLength, classIdArray.length); i++) {
            var classId = (int) classIdArray[i];
            
            //     softmax                                     
            var maxProb = 0.0f;
            if (i * numLabels + classId < softmaxArray.length) {
                maxProb = softmaxArray[i * numLabels + classId];
            }
            
            var type = mapClassIdToType(classId);
            
            //                                                                            
            regions.add(DocumentRegion.builder()
                    .type(type)
                    .confidence(maxProb)
                    .x0(0)
                    .y0(0)
                    .x1(0)
                    .y1(0)
                    .build());
        }
    }
    
    /**
     *                                                                   
     * <p>
     *                    [x0, y0, x1, y1, class_id]
     * </p>
     *
     * @param output                 
     * @param regions                                  
     * @param batchSize              
     * @param numRegions             
     */
    private void processRegionListOutput(NDArray output, ArrayList<DocumentRegion> regions, 
                                        int batchSize, int numRegions) {
        //                               
        for (int i = 0; i < numRegions; i++) {
            var regionData = batchSize > 1 ? output.get(0, i) : output.get(i);
            var values = regionData.toFloatArray();
            
            if (values.length >= 5) {
                var x0 = (int) values[0];
                var y0 = (int) values[1];
                var x1 = (int) values[2];
                var y1 = (int) values[3];
                var classId = (int) values[4];
                
                var type = mapClassIdToType(classId);
                
                regions.add(DocumentRegion.builder()
                        .type(type)
                        .confidence(1.0f)
                        .x0(x0)
                        .y0(y0)
                        .x1(x1)
                        .y1(y1)
                        .build());
            }
        }
    }

    /**
     *           ID                      
     *
     * @param classId        ID
     * @return                   
     */
    private String mapClassIdToType(int classId) {
        return switch (classId) {
            case 0 -> "      ";
            case 1 -> "      ";
            case 2 -> "      ";
            case 3 -> "      ";
            case 4 -> "      ";
            case 5 -> "      ";
            case 6 -> "      ";
            default -> "      ";
        };
    }

    /**
     *                   
     * <p>
     *        LayoutLMv3                            
     * -                 224x224
     * - RGB                 BGR   
     * -              [0, 1]
     * -        ImageNet                            
     * </p>
     *
     * @param manager NDManager       
     * @param image                
     * @return                          [1, 3, 224, 224]
     */
    private NDArray processImage(NDManager manager, Image image) {
        var img = image.toNDArray(manager);
        
        //                       224x224
        img = NDImageUtils.resize(img, IMAGE_SIZE, IMAGE_SIZE);
        
        //                    (HWC to CHW)                 [0, 1]
        //          LayoutLMv3        RGB                 BGR
        img = img.transpose(2, 0, 1).div(255.0f);
        
        //                                ImageNet                      
        //        LayoutLMv3ImageProcessor                ImageNet          
        img = NDImageUtils.normalize(
                img,
                new float[]{0.485f, 0.456f, 0.406f},
                new float[]{0.229f, 0.224f, 0.225f}
       );
        
        //                    [1, 3, 224, 224]
        img = img.expandDims(0);
        
        return img;
    }


    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}


