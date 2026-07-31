package com.chua.deeplearning.support.pytorch.style;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorFactory;
import ai.djl.util.Pair;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 风格迁移 Translator 工厂。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class StyleTransferTranslatorFactory implements TranslatorFactory {

    @Override
    public Set<Pair<Type, Type>> getSupportedTypes() {
        return Collections.singleton(new Pair<>(Image.class, Image.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> Translator<I, O> newInstance(Class<I> input,
                                               Class<O> output,
                                               Model model,
                                               Map<String, ?> arguments) throws TranslateException {
        if (!isSupported(input, output)) {
            throw new IllegalArgumentException("不支持的输入/输出类型: " + input + " -> " + output);
        }
        return (Translator<I, O>) new StyleTransferTranslator();
    }
}
