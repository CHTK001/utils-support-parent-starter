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

class Seq2SeqSimpleTest {

    @Test
    @DisplayName("门户: TextTranslator与ModelRegistry均可发现seq2seq")
    void registryAndPortal() {
        List<String> tr = TextTranslator.listModels();
        System.out.println("[门户] TextTranslator: " + tr);
        ModelRegistry.discoverAll();
        Assertions.assertNotNull(ModelRegistry.get("t5-seq2seq"));
        Assertions.assertNotNull(ModelRegistry.get("t5-base-seq2seq"));
        Assertions.assertNotNull(ModelRegistry.get("mt5-zh-seq2seq"));
        Assertions.assertNotNull(ModelRegistry.get("bart-zh-seq2seq"));
        System.out.println("[门户] ModelRegistry seq2seq: t5, t5-base, mt5-zh, bart-zh 均已注册");
    }

    @Test
    @DisplayName("bart-zh 中文书面语")
    @EnabledIfSystemProperty(named = "seq2seq.download", matches = "true")
    void bartZh() {
        ModelRegistry.discoverAll();
        ITranslator<String,String> zh = (ITranslator<String,String>)(ITranslator<?,?>)ModelRegistry.createTranslator("bart-zh-seq2seq", null);
        try {
            if (zh instanceof T5Seq2SeqOrtTranslator o) o.setMaxNewTokens(40);
            String r = zh.translate("北京大学公布今年本科招生计划，共设置81个专业，计划招收4300人。学校将推行大类招生与通识教育改革。");
            System.out.println("[bart-zh] " + r);
            Assertions.assertFalse(r.isBlank());
        } finally { close(zh); }
    }

    private static void close(Object o) { if (o instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) {} }
}