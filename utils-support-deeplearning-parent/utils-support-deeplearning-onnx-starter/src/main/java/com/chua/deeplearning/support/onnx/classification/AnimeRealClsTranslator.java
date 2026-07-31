package com.chua.deeplearning.support.onnx.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepGHS anime/real                    translator   
 *
 * @author CH
 * @since 2026-04-09
 */
public class AnimeRealClsTranslator implements Translator<Image, Classifications> {

    static final List<String> LABELS = List.of("anime", "real");
    static final String MODEL_NAME = "anime_real_cls";
    static final String MODEL_RELATIVE_PATH = "vision/classification/anime/anime_real_cls/mobilenetv3_v1.4_dist/model.onnx";

    private final ImageClassificationTranslator delegate;

    public AnimeRealClsTranslator() {
        this(Map.of());
    }

    public AnimeRealClsTranslator(Map<String, ?> arguments) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (arguments != null && !arguments.isEmpty()) {
            options.putAll(arguments);
        }
        options.putIfAbsent("width", 384);
        options.putIfAbsent("height", 384);
        options.putIfAbsent("resize", true);
        options.putIfAbsent("normalize", true);

        this.delegate = ImageClassificationTranslator.builder(options)
                .optApplySoftmax(true)
                .optTopK(LABELS.size())
                .optSynset(LABELS)
                .build();
    }

    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        delegate.prepare(ctx);
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return delegate.processInput(ctx, input);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        return delegate.processOutput(ctx, list);
    }

    @Override
    public Batchifier getBatchifier() {
        return delegate.getBatchifier();
    }
}
