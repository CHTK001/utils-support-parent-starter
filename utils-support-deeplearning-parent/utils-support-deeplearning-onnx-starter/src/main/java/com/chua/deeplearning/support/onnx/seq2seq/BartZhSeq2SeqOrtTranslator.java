package com.chua.deeplearning.support.onnx.seq2seq;

/**
 * 中文 BART-large Seq2Seq 翻译器（ORT 原生，中文书面语摘要）。
 *
 * <p>复用 {@link T5Seq2SeqOrtTranslator} 的自回归实现，替换模型定义为
 * {@link Seq2SeqModelDefinition#BART_ZH}（fnlp/bart-large-chinese，12 层 16 头，decoderStart=eos=102）。
 * 中文书面语稳定性优于 mt5。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BartZhSeq2SeqOrtTranslator extends T5Seq2SeqOrtTranslator {

    /**
     * 无参构造，使用中文 BART-large 模型定义。
     */
    public BartZhSeq2SeqOrtTranslator() {
        super(Seq2SeqModelDefinition.BART_ZH);
    }
}
