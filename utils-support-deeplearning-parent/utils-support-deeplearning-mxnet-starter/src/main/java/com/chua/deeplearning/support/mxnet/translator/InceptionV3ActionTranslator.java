package com.chua.deeplearning.support.mxnet.translator;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MXNet InceptionV3 图像分类 Translator。
 * <p>输入图像将被缩放至 299x299 并归一化，输出 ImageNet 类别概率分布。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InceptionV3ActionTranslator implements Translator<Image, Classifications> {

    /**
     * 输入图像宽度（像素）
     */
    private static final int DEFAULT_WIDTH = 299;

    /**
     * 输入图像高度（像素）
     */
    private static final int DEFAULT_HEIGHT = 299;

    /**
     * 类别标签文件名
     */
    private static final String DEFAULT_SYNSET_FILE = "classes.txt";

    /** 委托对象 */
    private final ImageClassificationTranslator delegate;

    public InceptionV3ActionTranslator() {
        this(Map.of());
    }

    public InceptionV3ActionTranslator(Map<String, ?> arguments) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (arguments != null && !arguments.isEmpty()) {
            options.putAll(arguments);
        }
        options.putIfAbsent("width", DEFAULT_WIDTH);
        options.putIfAbsent("height", DEFAULT_HEIGHT);
        options.putIfAbsent("resize", true);
        options.putIfAbsent("normalize", true);
        options.putIfAbsent("synsetFileName", DEFAULT_SYNSET_FILE);
        options.putIfAbsent("applySoftmax", true);

        this.delegate = ImageClassificationTranslator.builder(options)
                .optApplySoftmax(true)
                .build();
    }

    @Override
    public Batchifier getBatchifier() {
        return delegate.getBatchifier();
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return delegate.processInput(ctx, input);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        return delegate.processOutput(ctx, list);
    }
}
