package com.chua.deeplearning.support.onnx.seq2seq;

/**
* mt5-基础 多语言 Seq2Seq 翻译器（ORT 原生，中文摘要）。
*
* <p>复用 {@link T5Seq2SeqOrtTranslator} 的自回归实现，替换模型定义为
* {@link Seq2SeqModelDefinition#MT5_BASE}（modelscope 下载，12 层 12 头 d_model=768，
* fp16 编码器 + int8 解码器）。较 mt5-small 参数量约 2 倍，中文摘要质量更好、更稳定。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class Mt5BaseSeq2SeqOrtTranslator extends T5Seq2SeqOrtTranslator {

    /**
    * 无参构造，使用内置 mt5-基础 模型定义。
    */
    public Mt5BaseSeq2SeqOrtTranslator() {
        super(Seq2SeqModelDefinition.MT5_BASE);
    }
}
