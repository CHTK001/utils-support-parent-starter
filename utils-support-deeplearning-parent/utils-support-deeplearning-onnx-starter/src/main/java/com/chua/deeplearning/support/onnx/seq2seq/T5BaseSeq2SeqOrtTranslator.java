package com.chua.deeplearning.support.onnx.seq2seq;

/**
* T5-基础 英文 Seq2Seq 翻译器（ORT 原生）。
*
* <p>复用 {@link T5Seq2SeqOrtTranslator} 的自回归实现，替换模型定义为
* {@link Seq2SeqModelDefinition#T5_BASE}（modelscope 下载，12 层 12 头 d_model=768，int8 量化）。
* 英文摘要/生成质量优于 T5-small。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class T5BaseSeq2SeqOrtTranslator extends T5Seq2SeqOrtTranslator {

    /**
    * 无参构造，使用内置 t5-基础 模型定义。
     */
    public T5BaseSeq2SeqOrtTranslator() {
        super(Seq2SeqModelDefinition.T5_BASE);
    }
}