package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.nlp.TextTranslator;
import com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqOrtTranslator;
import com.chua.deeplearning.support.translator.ITranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

class Seq2SeqComprehensiveTest {

    @Test
    @DisplayName("门户: TextTranslator与ModelRegistry均可发现seq2seq")
    void registryAndPortal() {
        List<String> tr = TextTranslator.listModels();
        Assertions.assertTrue(tr.contains("opus-mt-zh-en"));
        ModelRegistry.discoverAll();
        Assertions.assertNotNull(ModelRegistry.get("t5-seq2seq"));
        Assertions.assertNotNull(ModelRegistry.get("t5-base-seq2seq"));
        Assertions.assertNotNull(ModelRegistry.get("mt5-zh-seq2seq"));
        Assertions.assertNotNull(ModelRegistry.get("bart-zh-seq2seq"));
        System.out.println("[门户] TextTranslator: " + tr);
        System.out.println("[门户] ModelRegistry seq2seq: t5, t5-base, mt5-zh, bart-zh 均已注册");
    }

    @Test
    @DisplayName("opus 多样中文翻译")
    void opusMultiple() {
        TextTranslator tr = TextTranslator.create("opus-mt-zh-en");
        try {
            for (String s : List.of("早上好", "今天天气不错", "请帮我总结一下这段文字")) {
                String r = tr.translate(s);
                System.out.println("[opus] " + s + " -> " + r);
                Assertions.assertFalse(r.isBlank());
            }
        } finally { close(tr); }
    }

    @Test
    @DisplayName("t5-small 短输入摘要")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallShort() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        ITranslator<String,String> t5 = tr("t5-seq2seq");
        try {
            String r = t5.translate("The cat sits on the mat. It is very cute.");
            System.out.println("[t5-small 短] " + r);
            Assertions.assertFalse(r.isBlank());
        } finally { close(t5); }
    }

    @Test
    @DisplayName("t5-small 长输入摘要")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallLong() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        ITranslator<String,String> t5 = tr("t5-seq2seq");
        try {
            String src = "Artificial intelligence has made great progress in recent years. "
                    + "Deep learning models can now understand and generate human language. "
                    + "These models are used in translation, summarization and question answering.";
            String r = t5.translate(src);
            System.out.println("[t5-small 长] " + r);
            Assertions.assertFalse(r.isBlank());
        } finally { close(t5); }
    }

    @Test
    @DisplayName("t5-small token上限生效")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallTokenLimit() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        ITranslator<String,String> t5 = tr("t5-seq2seq");
        try {
            if (t5 instanceof T5Seq2SeqOrtTranslator o) { o.setMaxNewTokens(8); o.setMinNewTokens(2); }
            String r = t5.translate("The quick brown fox jumps over the lazy dog near the river bank, and the dog wakes up and chases the fox.");
            System.out.println("[t5-small max8] " + r);
            Assertions.assertFalse(r.isBlank());
            if (t5 instanceof T5Seq2SeqOrtTranslator o) { o.setMaxNewTokens(128); o.setMinNewTokens(10); }
        } finally { close(t5); }
    }

    @Test
    @DisplayName("t5-small 空与单句边界")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void t5SmallEdge() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
        ITranslator<String,String> t5 = tr("t5-seq2seq");
        try {
            Assertions.assertEquals("", t5.translate(""));
            Assertions.assertEquals("   ", t5.translate("   "));
            String r = t5.translate("Hello world.");
            System.out.println("[t5-small 单句] " + r);
            Assertions.assertFalse(r.isBlank());
        } finally { close(t5); }
    }

    @Test
    @DisplayName("bart-zh 中文口语可用")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void bartZhShort() {
        ModelRegistry.discoverAll();
        ITranslator<String,String> zh = tr("bart-zh-seq2seq");
        try {
            if (zh instanceof T5Seq2SeqOrtTranslator o) o.setMaxNewTokens(32);
            String r = zh.translate("近期全国多地气温骤降，医院门诊量明显上升。医生提醒要注意添衣保暖。");
            System.out.println("[bart-zh 短] " + r);
            Assertions.assertFalse(r.isBlank());
        } finally { close(zh); }
    }

    @Test
    @DisplayName("bart-zh 中文书面语多样本")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void bartZhRealSamples() {
        ModelRegistry.discoverAll();
        ITranslator<String,String> zh = tr("bart-zh-seq2seq");
        try {
            if (zh instanceof T5Seq2SeqOrtTranslator o) o.setMaxNewTokens(40);
            for (String s : List.of(
                    "北京大学公布今年本科招生计划，共设置81个专业，计划招收4300人。学校将推行大类招生与通识教育改革。",
                    "专家表示睡眠不足会明显影响记忆力和判断力，长期熬夜还可能增加心血管疾病风险。建议保持规律作息。",
                    "因系统升级，今晚24点至次日凌晨2点网银将暂停服务。期间资金结算不受影响，请您谅解。")) {
                String r = zh.translate(s);
                System.out.println("[bart-zh] 输入: " + s.substring(0, Math.min(30, s.length())) + "... -> " + r);
                Assertions.assertFalse(r.isBlank());
            }
        } finally { close(zh); }
    }

    @Test
    @DisplayName("mt5-zh 口语可用（达摩院）")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void mt5ZhShort() {
        ModelRegistry.discoverAll();
        T5Seq2SeqOrtTranslator.setTaskPrefix("摘要：");
        ITranslator<String,String> zh = tr("mt5-zh-seq2seq");
        try {
            String r = zh.translate("近期全国多地气温骤降，医院门诊量明显上升。医生提醒要注意添衣保暖。");
            System.out.println("[mt5-zh 短] " + r);
            Assertions.assertFalse(r.isBlank());
        } finally { close(zh); }
    }

    @SuppressWarnings("unchecked")
    private ITranslator<String,String> tr(String id) {
        return (ITranslator<String,String>)(ITranslator<?,?>)ModelRegistry.createTranslator(id, null);
    }
    private static void close(Object o) {
        if (o instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) {}
    }
}