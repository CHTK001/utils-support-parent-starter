package com.chua.deeplearning.support.onnx.seq2seq;

/**
* 达摩院中文 mt5-基础 Seq2Seq 翻译器（ORT 原生，中文对话改写/摘要）。
*
* <p>复用 {@link T5Seq2SeqOrtTranslator} 的自回归实现，替换模型定义为
* {@link Seq2SeqModelDefinition#MT5_ZH}（iic/nlp_mt5_dialogue-rewriting_chinese-base，
* 12 层 12 头，中文微调，fp16 ONNX 需本地放置）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class Mt5ZhSeq2SeqOrtTranslator extends T5Seq2SeqOrtTranslator {

    /**
    * 无参构造，使用达摩院中文 mt5-基础 模型定义。
    */
    public Mt5ZhSeq2SeqOrtTranslator() {
        super(Seq2SeqModelDefinition.MT5_ZH);
    }
}
