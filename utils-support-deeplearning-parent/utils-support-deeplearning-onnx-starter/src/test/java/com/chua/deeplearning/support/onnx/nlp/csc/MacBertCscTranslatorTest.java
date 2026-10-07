package com.chua.deeplearning.support.onnx.nlp.csc;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.nlp.TextTranslator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macbert4csc 中文拼写纠错翻译器验收（真实模型，仅中文用例）。
 *
 * <p>覆盖全部公开入口：{@code name} / {@code translate}（含 null、空白、纠错、
 * 保文本、混合文本、超长分段、阈值属性）/ {@code setModelPath} / {@code close}，
 * 以及注册表条目与内嵌资源完整性。</p>
 */
class MacBertCscTranslatorTest {

    /** pycorrector 官方示例：因该 → 应该 */
    private static final String TYPED_SAMPLE = "少先队员因该为老人让坐";

    private static MacBertCscTranslator csc;

    @BeforeAll
    static void setUp() {
        ModelRegistry.discoverAll();
        csc = new MacBertCscTranslator();
    }

    @AfterAll
    static void tearDown() {
        if (csc != null) {
            csc.close();
        }
    }

    @Test
    void registeredInRegistry() {
        ModelRegistry.Entry entry = ModelRegistry.get("macbert4csc");
        assertNotNull(entry, "macbert4csc 应已注册");
        assertEquals("com.chua.deeplearning.support.onnx.nlp.csc.MacBertCscTranslator",
                entry.translatorClassName());
        assertEquals(String.class, entry.inputType());
        assertEquals(String.class, entry.outputType());
        assertEquals(TextTranslator.class, entry.capabilityInterface());
        assertEquals("nlp/csc/macbert4csc/model.onnx", entry.relativePath());
        assertNotNull(entry.downloadUrl());
        assertTrue(entry.downloadUrl().contains("hf-mirror.com/shibing624/macbert4csc-base-chinese"),
                "主源应为 hf-mirror: " + entry.downloadUrl());
        assertNotNull(entry.downloadMirrors());
        assertTrue(entry.downloadMirrors().stream()
                        .anyMatch(u -> u.contains("huggingface.co/shibing624/macbert4csc-base-chinese")),
                "镜像应含 huggingface 官方地址: " + entry.downloadMirrors());
        assertFalse(entry.compress());
    }

    @Test
    void bundledResourcesPresent() throws Exception {
        ClassLoader cl = MacBertCscTranslatorTest.class.getClassLoader();
        assertNotNull(cl.getResource("nlp/csc/macbert4csc/model.onnx"), "内嵌 model.onnx 缺失");
        URL vocab = cl.getResource("nlp/csc/macbert4csc/vocab.txt");
        assertNotNull(vocab, "内嵌 vocab.txt 缺失");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                vocab.openStream(), StandardCharsets.UTF_8))) {
            assertEquals(21128, reader.lines().count(), "vocab 应为 21128 行");
        }
    }

    @Test
    void nameReturnsModelId() {
        assertEquals("macbert4csc", csc.name());
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(csc.translate(null));
        assertEquals("", csc.translate(""));
        assertEquals("   ", csc.translate("   "));
    }

    @Test
    void correctsWrongChinese() {
        String corrected = csc.translate(TYPED_SAMPLE);
        assertNotNull(corrected);
        assertEquals(TYPED_SAMPLE.length(), corrected.length(), "纠错必须逐字等长");
        assertTrue(corrected.contains("应该"), "因该 应被纠正为 应该: " + corrected);
        assertFalse(corrected.contains("因该"), "错别字 因该 未被修复: " + corrected);
    }

    @Test
    void leavesCorrectTextUnchanged() {
        String text = "请在入口处领取中文导览手册";
        assertEquals(text, csc.translate(text), "正确文本不应被误改");
    }

    @Test
    void mixedTextLengthPreserved() {
        String text = "扫描二维码ab123获取帮助";
        String corrected = csc.translate(text);
        assertNotNull(corrected);
        assertFalse(corrected.isBlank());
        assertEquals(text.length(), corrected.length(), "混合文本必须等长（守卫不满足原样返回）");
    }

    @Test
    void longTextChunkedLengthPreserved() {
        String piece = "请在入口处领取中文导览手册。";
        StringBuilder source = new StringBuilder();
        while (source.length() <= 128) {
            source.append(piece);
        }
        String text = source.toString();
        assertTrue(text.length() > 128, "用例需超过 128 字触发分段");
        String corrected = csc.translate(text);
        assertNotNull(corrected);
        assertEquals(text.length(), corrected.length(), "超长分段拼接后必须等长");
    }

    @Test
    void thresholdPropertyControlsCorrection() {
        System.setProperty("deeplearning.csc.threshold", "2");
        try {
            assertEquals(TYPED_SAMPLE, csc.translate(TYPED_SAMPLE), "阈值大于 1 应等价关闭纠错");
        } finally {
            System.clearProperty("deeplearning.csc.threshold");
        }
        assertTrue(csc.translate(TYPED_SAMPLE).contains("应该"), "默认阈值 0.7 应可纠错");
    }

    @Test
    void setModelPathBlankIgnoredAndInjectionWorks() throws Exception {
        Path model = ModelRegistry.resolveModelPath("macbert4csc");
        assertNotNull(model, "注册表应能解析模型路径");
        assertTrue(Files.isRegularFile(model), "模型文件应存在: " + model);
        MacBertCscTranslator injected = new MacBertCscTranslator();
        try {
            injected.setModelPath(null);
            injected.setModelPath("   ");
            injected.setModelPath(model.toString());
            String corrected = injected.translate(TYPED_SAMPLE);
            assertTrue(corrected.contains("应该"), "注入路径后应可正常纠错: " + corrected);
        } finally {
            injected.close();
        }
    }

    @Test
    void closeIdempotentAndReusable() {
        csc.close();
        csc.close();
        String corrected = csc.translate(TYPED_SAMPLE);
        assertNotNull(corrected, "close 后应自动重新加载");
        assertTrue(corrected.contains("应该"), "close 后应仍可纠错: " + corrected);
    }
}
