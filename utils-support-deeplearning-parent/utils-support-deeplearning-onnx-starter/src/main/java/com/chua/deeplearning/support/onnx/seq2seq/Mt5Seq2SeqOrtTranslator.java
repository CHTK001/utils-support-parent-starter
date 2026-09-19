package com.chua.deeplearning.support.onnx.seq2seq;

/**
 * mt5-small 多语言 Seq2Seq 翻译器（ORT 原生，支持中文摘要/生成）。
 *
 * <p>复用 {@link T5Seq2SeqOrtTranslator} 的自回归实现，仅替换模型定义为
 * {@link Seq2SeqModelDefinition#MT5_SMALL}（modelscope 下载，词表 250112，8 层 6 头多语言 T5）。
 * 用于中文文本的摘要/生成（如输入多句话、输出一句总结）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Mt5Seq2SeqOrtTranslator extends T5Seq2SeqOrtTranslator {

    /**
     * 无参构造，使用内置 mt5-small 模型定义。
     */
    public Mt5Seq2SeqOrtTranslator() {
        super(Seq2SeqModelDefinition.MT5_SMALL);
    }
}
