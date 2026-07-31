package com.chua.deeplearning.support.onnx.liveness;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 *                    Translator   
 * <p>
 *                                                                         
 * <ul>
 *   <li>minifasnet_v2     80  80 BGR, [0,1], 3     (            /            /            )</li>
 *   <li>dinov2_liveness     518  518 RGB, ImageNet          , 2     (            /            )</li>
 *   <li>vit_liveness     224  224 RGB, ImageNet          , 2     (            /            )</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2026-05-10
 */
@Slf4j
public class FaceAntiSpoofTranslator implements Translator<Image, Classifications> {

    /**
     * minifasnet_v2                      
     */
    private static final List<String> LABELS_3 = List.of("            ", "            ", "            ");

    /**
     * dinov2_liveness / vit_liveness                      
     */
    private static final List<String> LABELS_2 = List.of("            ", "            ");

    /**
     *                                      
     */
    private ImageClassificationTranslator delegate;

    /**
     *              
     */
    public FaceAntiSpoofTranslator() {
    }

    /**
     *                                                     
     *
     * @param ctx TranslatorContext          
     * @throws Exception                
     */
    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        Path modelPath = ctx.getModel().getModelPath();
        String pathStr = modelPath != null ? modelPath.toString().toLowerCase() : "";
        Map<String, Object> options = new LinkedHashMap<>();
        List<String> labels;

        if (pathStr.contains("minifasnet")) {
            options.put("width", 80);
            options.put("height", 80);
            options.put("flag", Image.Flag.COLOR);
            labels = LABELS_3;
        } else if (pathStr.contains("dinov2")) {
            options.put("width", 518);
            options.put("height", 518);
            options.put("mean", "0.485,0.456,0.406");
            options.put("std", "0.229,0.224,0.225");
            labels = LABELS_2;
        } else {
            options.put("width", 224);
            options.put("height", 224);
            options.put("mean", "0.485,0.456,0.406");
            options.put("std", "0.229,0.224,0.225");
            labels = LABELS_2;
        }

        options.put("resize", true);
        delegate = ImageClassificationTranslator.builder(options)
                .optApplySoftmax(true)
                .optTopK(labels.size())
                .optSynset(labels)
                .build();
        delegate.prepare(ctx);
    }

    /**
     *                                        
     *
     * @param ctx TranslatorContext          
     * @param input                       
     * @return NDList               
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return delegate.processInput(ctx, input);
    }

    /**
     *                                                        
     *
     * @param ctx TranslatorContext          
     * @param list NDList              
     * @return Classifications          
     */
    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        return delegate.processOutput(ctx, list);
    }

    /**
     *                           
     *
     * @return Batchifier          
     */
    @Override
    public Batchifier getBatchifier() {
        return delegate.getBatchifier();
    }
}
