package com.chua.deeplearning.support.translator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 翻译器注册表。
* <p>按名称管理所有 {@link ITranslator} 实例。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class TranslatorRegistry {

    /**
    * 名称到翻译器的映射
     */
    private final Map<String, ITranslator<?, ?>> registry = new ConcurrentHashMap<>();

    /**
    * 注册翻译器。
    *
    * @param name       翻译器名称
    * @param translator 翻译器实例
     */
    public void register(String name, ITranslator<?, ?> translator) {
        registry.put(name, translator);
    }

    /**
    * 按名称获取翻译器。
    *
    * @param name 翻译器名称
    * @param <I>  输入类型
    * @param <O>  输出类型
    * @return 翻译器实例
     */
    @SuppressWarnings("unchecked")
    public <I, O> ITranslator<I, O> get(String name) {
        return (ITranslator<I, O>) registry.get(name);
    }

    /**
    * 获取所有已注册的翻译器。
    *
    * @return 名称到翻译器的不可变视图
     */
    public Map<String, ITranslator<?, ?>> all() {
        return registry;
    }
}
