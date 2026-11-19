package com.chua.deeplearning.support.translator;

/**
 * 翻译器解包能力接口。
 *
 * <p>模型注册表对懒加载 translator 有一层（或多层）包装
 * （如 {@code LazyDjlTranslator} → {@code ITranslatorDelegate} → 真实 translator），
 * 能力判定（{@code instanceof}）如果打在最外层包装上，会被包装链吞掉。
 * 实现本接口的包装类可暴露最内层真实 translator，供调用方按真实能力分发
 * （如 {@link ConversationTranslator} 多轮对话能力）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface TranslatorUnwrappable {

    /**
     * 返回最内层真实 translator 实例（必要时先完成懒加载初始化）。
     *
     * @return 真实 translator；初始化失败时抛出运行时异常
     */
    Object unwrapTranslator();
}
