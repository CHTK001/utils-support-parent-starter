package com.chua.deeplearning.support.onnx.seq2seq;

/**
 * Randeng-T5-77M 中文 Seq2Seq 翻译器（ORT 原生，小型中文摘要专用）。
 *
 * <p>复用 {@link T5Seq2SeqOrtTranslator} 的自回归实现，替换模型定义为
 * {@link Seq2SeqModelDefinition#RANDENG_77M}（IDEA-CCNL/Randeng-T5-77M-Chinese，8 层 6 头，77M，嵌入式 80MB）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RandengT5OrtTranslator extends T5Seq2SeqOrtTranslator {

    /**
     * 无参构造，使用 Randeng-T5-77M 模型定义。
     */
    public RandengT5OrtTranslator() {
        super(Seq2SeqModelDefinition.RANDENG_77M);
    }
}