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
 * <p>覆盖 seq2seq 能力：</p>
 * <ul>
 *   <li>嵌入式：{@code opus-mt-zh-en}（中译英）；</li>
 *   <li>modelscope 下载：{@code t5-seq2seq}（T5-small 英文摘要/生成/翻译）、{@code t5-base-seq2seq}（英文摘要）、
 *   {@code mt5-base-seq2seq}（中文多句 → 一句总结）。</li>
 * </ul>
 *
 * <p>中文用例为 {@link #mt5ChineseSummarize()} 与 {@link #mt5ChineseRealSamples()}；
 * 中文正式语料在 mT5 上退化，见 §14.1 真实语料评估。</p>
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
        try {
            String source = "你好，欢迎使用深度学习框架。";
            String result = translator.translate(source);
            System.out.println("[opus-mt-zh-en] 原文: " + source);
            System.out.println("[opus-mt-zh-en] 译文: " + result);
            Assertions.assertNotNull(result, "翻译结果不应为 null");
            Assertions.assertFalse(result.isBlank(), "翻译结果不应为空白");
            Assertions.assertTrue(result.split(" ").length > 0, "翻译结果应有单词");
        } finally {
            closeIfPossible(translator);
        }
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
        try {
            String source = "The quick brown fox jumps over the lazy dog near the river bank, "
                    + "and the dog wakes up and chases the fox across the field."
                    + " The farmer watches the animals from his tractor and laughs.";
            String result = t5.translate(source);
            System.out.println("[t5-seq2seq] 输入: " + source);
            System.out.println("[t5-seq2seq] 摘要: " + result);
            Assertions.assertNotNull(result, "摘要结果不应为 null");
            Assertions.assertFalse(result.isBlank(), "摘要结果不应为空白");
        } finally {
            closeIfPossible(t5);
        }
    }

    /**
     * T5-base 英文摘要测试（modelscope 下载，int8，官方推荐 beam 参数）。
     */
    @Test
    @DisplayName("t5-base-seq2seq 英文摘要（beam=4）")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5BaseSummarize() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        @SuppressWarnings("unchecked")
        ITranslator<String, String> t5b = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator("t5-base-seq2seq", null);
        try {
            if (t5b instanceof T5Seq2SeqOrtTranslator ort) {
                ort.setNumBeams(4);
                ort.setMinNewTokens(30);
                ort.setMaxNewTokens(200);
            }
            String source = "The quick brown fox jumps over the lazy dog near the river bank, "
                    + "and the dog wakes up and chases the fox across the field."
                    + " The farmer watches the animals from his tractor and laughs.";
            String result = t5b.translate(source);
            System.out.println("[t5-base-seq2seq] 摘要: " + result);
            Assertions.assertNotNull(result, "摘要结果不应为 null");
            Assertions.assertFalse(result.isBlank(), "摘要结果不应为空白");
        } finally {
            closeIfPossible(t5b);
        }
    }

    /**
     * 输出 token 大小可控验证：设置 maxNewTokens 后输出在受限范围内，不抛异常。
     */
    @Test
    @DisplayName("t5-seq2seq 指定输出 token 上限")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallSummarizeLimitedTokens() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        @SuppressWarnings("unchecked")
        ITranslator<String, String> t5 = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator("t5-seq2seq", null);
        try {
            if (t5 instanceof T5Seq2SeqOrtTranslator ort) {
                ort.setMinNewTokens(3);
                ort.setMaxNewTokens(16);
            }
            String source = "The quick brown fox jumps over the lazy dog near the river bank, "
                    + "and the dog wakes up and chases the fox across the field."
                    + " The farmer watches the animals from his tractor and laughs.";
            String result = t5.translate(source);
            System.out.println("[t5-seq2seq] maxNewTokens=16 摘要: " + result);
            Assertions.assertNotNull(result, "摘要结果不应为 null");
            Assertions.assertFalse(result.isBlank(), "摘要结果不应为空白");
        } finally {
            closeIfPossible(t5);
        }
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
        try {
            String source = "The cat sits on the mat.";
            String result = t5.translate(source);
            System.out.println("[t5-seq2seq] 输入: " + source);
            System.out.println("[t5-seq2seq] 译文: " + result);
            Assertions.assertNotNull(result, "翻译结果不应为 null");
            Assertions.assertFalse(result.isBlank(), "翻译结果不应为空白");
        } finally {
            closeIfPossible(t5);
        }
    }

    /**
     * mT5-base 中文多句 → 单句摘要测试（modelscope 下载，支持中文，口语文本可用）。
     */
    @Test
    @DisplayName("mt5-seq2seq 中文输入多句 → 输出一句总结")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void mt5ChineseSummarize() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        @SuppressWarnings("unchecked")
        ITranslator<String, String> mt5 = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator("mt5-base-seq2seq", null);
        try {
            String source = "近期全国多地气温骤降，医院门诊量明显上升。医生提醒，降温期间要注意添衣保暖，"
                    + "尤其是老人和儿童，抵抗力较弱，容易受凉感冒。如果出现发热、咳嗽等症状，应及时就医，"
                    + "不要硬扛，居家时也要保持室内通风。";
            String result = mt5.translate(source);
            System.out.println("[mt5-seq2seq] 输入(多句): " + source);
            System.out.println("[mt5-seq2seq] 摘要(一句话): " + result);
            Assertions.assertNotNull(result, "摘要结果不应为 null");
            Assertions.assertFalse(result.isBlank(), "摘要结果不应为空白");
        } finally {
            closeIfPossible(mt5);
        }
    }

    /**
     * 真实语料样本评估：不同文体的中文文本走 mT5-base，展示真实输出边界。
     */
    @Test
    @DisplayName("mt5-seq2seq 真实语料样本评估")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void mt5ChineseRealSamples() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        @SuppressWarnings("unchecked")
        ITranslator<String, String> mt5 = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator("mt5-base-seq2seq", null);
        try {
            java.util.List<String> samples = java.util.List.of(
                    "北京大学公布今年本科招生计划，共设置81个专业，计划招收4300人。学校表示，将逐步推行大类招生与通识教育改革，提高学生选课自主性。",
                    "专家表示，睡眠不足会明显影响记忆力和判断力，长期熬夜还可能增加心血管疾病风险。建议成年人每天保持七到八小时睡眠，睡前少看手机，保持规律作息。",
                    "尊敬的用户，因系统升级，今晚24点至次日凌晨2点个人网银将暂停服务。升级期间资金结算不受影响，给您带来不便敬请谅解。");
            int idx = 1;
            for (String s : samples) {
                String out = mt5.translate(s);
                System.out.println("[真实样本 " + idx++ + "] 输入: " + s);
                System.out.println("                输出: " + out);
                Assertions.assertNotNull(out);
            }
        } finally {
            closeIfPossible(mt5);
        }
    }

    /**
     * 释放可关闭资源，避免多个模型在同一 JVM 中常驻导致内存不足。
     *
     * @param resource 待释放资源
     */
    private static void closeIfPossible(Object resource) {
        if (resource instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}