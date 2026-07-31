package com.chua.deeplearning.support.onnx.liveness;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * MiniVision MiniFASNetV1SE              Translator   
 * <p>
 * 80x80                                         vs                   
 * </p>
 *
 * @author CH
 * @since 2026-05-02
 */
@Slf4j
public class MiniVisionLivenessTranslator implements Translator<Image, Classifications> {

    /**
     *                           
     */
    static final List<String> LABELS = List.of("            ", "            ");

    /**
     *                                      
     */
    private final ImageClassificationTranslator delegate;

    /**
     *              
     */
    public MiniVisionLivenessTranslator() {
        this(Map.of());
    }

    /**
     *                              
     *
     * @param arguments                             
     */
    public MiniVisionLivenessTranslator(Map<String, ?> arguments) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (arguments != null && !arguments.isEmpty()) {
            options.putAll(arguments);
        }
        options.putIfAbsent("width", 80);
        options.putIfAbsent("height", 80);
        options.putIfAbsent("resize", true);
        // MiniFASNet        ImageNet          
        options.putIfAbsent("mean", "0.485,0.456,0.406");
        options.putIfAbsent("std", "0.229,0.224,0.225");

        this.delegate = ImageClassificationTranslator.builder(options)
                .optApplySoftmax(true)
                .optTopK(LABELS.size())
                .optSynset(LABELS)
                .build();
    }

    /**
     *                                                     
     *
     * @param ctx TranslatorContext          
     * @throws Exception                
     */
    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
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
