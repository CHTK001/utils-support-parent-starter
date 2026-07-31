package com.chua.deeplearning.support.onnx.donut;

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

import java.util.Arrays;


/**
 * Donut                    Translator
 * <p>
 * Donut                                                                                         JSON       
 * </p>
 * <p>
 *        Hugging Face                Donut                               
 * -          pixel_values            RGB                                        2560x1920
 * -          logits   token                       (batch_size, sequence_length, vocab_size)
 * -              token           JSON       
 * </p>
 * <p>
 *          https://github.com/clovaai/donut
 * </p>
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
@Spi("donut")
public class DonutTranslator implements Translator<Image, DonutResult> {

    /**
     *                      Donut                
     */
    private static final int IMAGE_WIDTH = 2560;

    /**
     *                      Donut                
     */
    private static final int IMAGE_HEIGHT = 1920;

    /**
     *                         ImageNet          
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
     *                            ImageNet          
     */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /**
     *                
     *
     * @param ctx                   
     * @throws Exception                         
     */
    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        log.info("[Donut][         ]                                  ");
    }

    /**
     *             
     * <p>
     *                                                 
     * 1.                 2560x1920
     * 2.           RGB       
     * 3.              [0, 1]
     * 4.        ImageNet                            
     * </p>
     *
     * @param ctx                     
     * @param image              
     * @return NDList                    [1, 3, 1920, 2560]
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
     *                    token                 JSON       
     * </p>
     * <p>
     * Donut                
     * - logits             (batch_size, sequence_length, vocab_size)
     * -        argmax        token IDs                        
     * </p>
     *
     * @param ctx                    
     * @param list                   
     * @return Donut                                         JSON       
     * @throws Exception                      
     */
    @Override
    public DonutResult processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var output = list.singletonOrThrow();
        var shape = output.getShape();

        //                                     
        if (shape.dimension() == 3) {
            //        logits        [batch_size, sequence_length, vocab_size]
            var batchSize = (int) shape.get(0);
            var sequenceLength = (int) shape.get(1);
            var vocabSize = (int) shape.get(2);

            log.info("[Donut][            ]           logits       : [{}x{}x{}]", batchSize, sequenceLength, vocabSize);

            //                      
            var batchOutput = batchSize > 1 ? output.get("0") : output;

            //                       token ID   argmax   
            var tokenIds = new long[sequenceLength];
            for (int i = 0; i < sequenceLength; i++) {
                var positionLogits = batchOutput.get("{}", i);
                var tokenId = positionLogits.argMax().getLong();
                tokenIds[i] = tokenId;
            }

            //        token                
            var jsonText = decodeTokens(tokenIds);

            return DonutResult.builder()
                    .jsonText(jsonText)
                    .tokenIds(tokenIds)
                    .confidence(1.0f)
                    .build();

        } else if(shape.dimension() == 2) {
            //           [sequence_length, vocab_size]     [batch_size, sequence_length]
            var dim1 = (int) shape.get(0);
            var dim2 = (int) shape.get(1);

            if (dim2 > 1000) {
                //           [sequence_length, vocab_size]
                log.info("[Donut][            ]           logits       : [{}x{}]", dim1, dim2);
                var tokenIds = new long[dim1];
                for (int i = 0; i < dim1; i++) {
                    var positionLogits = output.get("{}", i);
                    var tokenId = positionLogits.argMax().getLong();
                    tokenIds[i] = tokenId;
                }
                var jsonText = decodeTokens(tokenIds);
                return DonutResult.builder()
                        .jsonText(jsonText)
                        .tokenIds(tokenIds)
                        .confidence(1.0f)
                        .build();
            } else {
                //                    token IDs [batch_size, sequence_length]
                log.info("[Donut][            ]           token IDs       : [{}x{}]", dim1, dim2);
                var batchOutput = dim1 > 1 ? output.get("0") : output;
                var tokenIds = batchOutput.toLongArray();
                var jsonText = decodeTokens(tokenIds);
                return DonutResult.builder()
                        .jsonText(jsonText)
                        .tokenIds(tokenIds)
                        .confidence(1.0f)
                        .build();
            }
        } else {
            log.warn("[Donut][            ]                         : {}", shape.dimension());
            return DonutResult.builder()
                    .jsonText("")
                    .confidence(0.0f)
                    .build();
        }
    }

    /**
     *                   
     * <p>
     *        Donut                            
     * -                 2560x1920                            letterbox   
     * - RGB                 BGR   
     * -              [0, 1]
     * -        ImageNet                            
     * </p>
     *
     * @param manager NDManager       
     * @param image               
     * @return                          [1, 3, 1920, 2560]
     */
    private NDArray processImage(NDManager manager, Image image) {
        var img = image.toNDArray(manager, Image.Flag.COLOR);

        //                       2560x1920                     
        img = NDImageUtils.resize(img, IMAGE_WIDTH, IMAGE_HEIGHT, Image.Interpolation.BILINEAR);

        //                    (HWC to CHW)                 [0, 1]
        img = img.transpose(2, 0, 1).div(255.0f);

        //                                ImageNet                      
        img = NDImageUtils.normalize(img, MEAN, STD);

        //                    [1, 3, 1920, 2560]
        img = img.expandDims(0);

        return img;
    }

    /**
     *        token                
     * <p>
     *                                                                 BART tokenizer
     *                          token IDs                              
     * </p>
     *
     * @param tokenIds token ID       
     * @return                   
     */
    private String decodeTokens(long[] tokenIds) {
        if (tokenIds == null || tokenIds.length == 0) {
            return "";
        }

        try {
            //              token       pad   eos       
            var validTokens = Arrays.stream(tokenIds)
                    .filter(tokenId -> tokenId > 0 && tokenId < 65536)
                    .toArray();

            if (validTokens.length == 0) {
                return "";
            }

            //                       token ID                
            //                             BART tokenizer             
            var sb = new StringBuilder();
            for (var tokenId : validTokens) {
                //                       token
                if (tokenId == 0 || tokenId == 1 || tokenId == 2) {
                    continue;
                }
                //        token ID     ASCII                         
                if (tokenId >= 32 && tokenId < 127) {
                    sb.append((char) tokenId);
                } else if(tokenId < 65536) {
                    // Unicode       
                    sb.append((char) tokenId);
                }
            }

            var result = sb.toString().trim();
            log.debug("[Donut][      ] token       : {},                   : {}", tokenIds.length, result.length());

            return result;

        } catch (Exception e) {
            log.error("[Donut][      ] token             : {}", e.getMessage(), e);
            return "";
        }
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}


