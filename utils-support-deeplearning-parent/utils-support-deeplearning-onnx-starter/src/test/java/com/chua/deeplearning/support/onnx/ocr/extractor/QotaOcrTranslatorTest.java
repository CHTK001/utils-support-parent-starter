package com.chua.deeplearning.support.onnx.ocr.extractor;

import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * qota-ocr 生产级验收：覆盖 Translator 全部公开入口。
 *
 * <ul>
 *   <li>{@link QotaOcrTranslator#name()} 标识</li>
 *   <li>{@link QotaOcrTranslator#translate(byte[])}：null/空/无法解码/白底黑字/黑底白字中文行</li>
 *   <li>{@link QotaOcrTranslator#setModelPath(String)}：null/空白安全</li>
 *   <li>{@link QotaOcrTranslator#close()}：未加载关闭、加载后关闭、重复关闭</li>
 *   <li>注册表：modelId/input/output/relativePath/downloadUrl</li>
 *   <li>jar 内嵌资源：model.onnx + preprocessor_config.json 全部存在且格式正确</li>
 *   <li>字符表解析：character_list 30246 项、首项 blank、末项空格、含中文</li>
 * </ul>
 */
class QotaOcrTranslatorTest {

    @Test
    void registered() {
        ModelRegistry.discoverAll();
        assertEquals("qota-ocr", new QotaOcrTranslator().name());
        ModelRegistry.Entry e = ModelRegistry.get("qota-ocr");
        assertNotNull(e, "qota-ocr 未注册");
        assertEquals(byte[].class, e.inputType());
        assertEquals(String.class, e.outputType());
        assertEquals("ocr/Qota-OCR/model.onnx", e.relativePath());
        assertNotNull(e.downloadUrl());
        assertTrue(e.downloadUrl().endsWith("model.onnx"), e.downloadUrl());
        assertNotNull(e.downloadMirrors());
        assertFalse(e.downloadMirrors().isEmpty());
    }

    @Test
    void emptyInput() {
        try (QotaOcrTranslator t = new QotaOcrTranslator()) {
            assertThrows(RuntimeException.class, () -> t.translate(null));
            assertThrows(RuntimeException.class, () -> t.translate(new byte[0]));
            assertThrows(RuntimeException.class, () -> t.translate(new byte[]{1, 2, 3}));
        }
    }

    @Test
    void closeWithoutLoad() {
        QotaOcrTranslator t = new QotaOcrTranslator();
        assertDoesNotThrow(t::close);
        assertDoesNotThrow(t::close);
    }

    @Test
    void setModelPathNullSafe() {
        QotaOcrTranslator t = new QotaOcrTranslator();
        assertDoesNotThrow(() -> t.setModelPath(null));
        assertDoesNotThrow(() -> t.setModelPath(""));
        assertDoesNotThrow(() -> t.setModelPath("   "));
        t.close();
    }

    @Test
    void bundledResourcesPresent() {
        ClassLoader loader = QotaOcrTranslator.class.getClassLoader();
        assertResource(loader, "ocr/Qota-OCR/model.onnx", 26_000_000);
        assertResource(loader, "ocr/Qota-OCR/preprocessor_config.json", 300_000);
    }

    @Test
    void characterDictFormat() throws Exception {
        String json = readResource("ocr/Qota-OCR/preprocessor_config.json");
        var table = QotaOcrTranslator.parseCharacterDict(json);
        assertEquals(30246, table.size(), "字符表项数漂移");
        assertEquals("blank", table.get(0));
        assertEquals(" ", table.get(table.size() - 1));
        assertTrue(table.contains("中"), "缺少中文");
        assertTrue(table.contains("文"), "缺少中文");
        assertTrue(table.contains("韩"), "缺少韩文");
    }

    @Test
    void recognizeWhiteBackground() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_white.png");
        try (QotaOcrTranslator t = new QotaOcrTranslator()) {
            String text = t.translate(img);
            assertNotNull(text);
            assertFalse(text.isBlank(), "白底黑字中文行识别为空");
            assertTrue(text.contains("中文"), "识别结果缺关键字: " + text);
            Files.write(Paths.get(System.getProperty("java.io.tmpdir"), "qota-zh-white.txt"),
                    text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void recognizeBlackBackground() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_black.png");
        try (QotaOcrTranslator t = new QotaOcrTranslator()) {
            String text = t.translate(img);
            assertNotNull(text);
            assertFalse(text.isBlank(), "黑底白字中文行识别为空");
            assertTrue(text.contains("中文"), "识别结果缺关键字: " + text);
            Files.write(Paths.get(System.getProperty("java.io.tmpdir"), "qota-zh-black.txt"),
                    text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void closeAfterLoad() throws Exception {
        byte[] img = resourceBytes("ocr/qota/zh_white.png");
        QotaOcrTranslator t = new QotaOcrTranslator();
        assertFalse(t.translate(img).isBlank());
        assertDoesNotThrow(t::close);
        assertDoesNotThrow(t::close);
    }

    private static void assertResource(ClassLoader loader, String name, long minSize) {
        try (InputStream in = loader.getResourceAsStream(name)) {
            assertNotNull(in, "资源缺失: " + name);
            long size = 0;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                size += n;
            }
            assertTrue(size >= minSize, name + " 过小: " + size);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String readResource(String name) throws Exception {
        try (InputStream in = QotaOcrTranslator.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "资源缺失: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream in = QotaOcrTranslator.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "测试图片缺失: " + name);
            return in.readAllBytes();
        }
    }
}
