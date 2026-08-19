package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.nlp.TextTranslator;
import com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqOrtTranslator;
import com.chua.deeplearning.support.translator.ITranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Seq2Seq 文本生成能力测试。
 *
 * <p>两条链路：</p>
 * <ul>
 *   <li>{@link #opusEmbeddedTranslate()}：嵌入式模型 opus-mt-zh-en（中译英），无需网络；</li>
 *   <li>{@link #t5SmallSummarize()}：modelscope 自动下载 t5-seq2seq（T5-small 摘要），首次需下载约 200MB，
 *   通过系统属性 {@code seq2seq.download=true} 启用。</li>
 * </ul>
 *
  * @author CH
 * @since 4.0.0.42
 */
class Seq2SeqTranslationTest {

    /**
     * 嵌入式中译英翻译测试。
     */
    @Test
    @DisplayName("opus-mt-zh-en 嵌入式中译英")
    void opusEmbeddedTranslate() {
        TextTranslator translator = TextTranslator.create("opus-mt-zh-en");
        String source = "你好，欢迎使用深度学习框架。";
        String result = translator.translate(source);
        System.out.println("[opus-mt-zh-en] 原文: " + source);
        System.out.println("[opus-mt-zh-en] 译文: " + result);
        Assertions.assertNotNull(result, "翻译结果不应为 null");
        Assertions.assertFalse(result.isBlank(), "翻译结果不应为空白");
        Assertions.assertTrue(result.split(" ").length > 0, "翻译结果应有单词");
    }

    /**
     * T5-small modelscope 下载 + 摘要生成测试（需网络，首次下载较慢）。
     */
    @Test
    @DisplayName("t5-seq2seq modelscope 下载并摘要")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallSummarize() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        @SuppressWarnings("unchecked")
        ITranslator<String, String> t5 = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator("t5-seq2seq", null);
        String source = "The quick brown fox jumps over the lazy dog near the river bank, "
                + "and the dog wakes up and chases the fox across the field."
                + " The farmer watches the animals from his tractor and laughs.";
        String result = t5.translate(source);
        System.out.println("[t5-seq2seq] 输入: " + source);
        System.out.println("[t5-seq2seq] 摘要: " + result);
        Assertions.assertNotNull(result, "摘要结果不应为 null");
        Assertions.assertFalse(result.isBlank(), "摘要结果不应为空白");
    }

    /**
     * T5 翻译任务验证：证明同一 seq2seq 模型通过任务前缀可切换到翻译任务。
     */
    @Test
    @DisplayName("t5-seq2seq 翻译任务（同一模型，任务前缀切换）")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallTranslate() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("translate English to Chinese: ");
        @SuppressWarnings("unchecked")
        ITranslator<String, String> t5 = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator("t5-seq2seq", null);
        String source = "The cat sits on the mat.";
        String result = t5.translate(source);
        System.out.println("[t5-seq2seq] 输入: " + source);
        System.out.println("[t5-seq2seq] 译文: " + result);
        Assertions.assertNotNull(result, "翻译结果不应为 null");
        Assertions.assertFalse(result.isBlank(), "翻译结果不应为空白");
    }
}