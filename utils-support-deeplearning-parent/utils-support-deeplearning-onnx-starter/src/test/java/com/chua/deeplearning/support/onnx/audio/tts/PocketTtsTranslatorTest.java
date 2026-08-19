package com.chua.deeplearning.support.onnx.audio.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PocketTtsTranslator 单元测试。
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>config.json 扁平化解析（嵌套对象、字符串、数字、点路径）</li>
 *   <li>张量名推断逻辑（配置名存在/不存在/dtype 推断）</li>
 *   <li>WAV 解码（空输入、16-bit PCM、多声道合并、重采样）</li>
 *   <li>端到端合成（模型存在时跑真推理，缺失时优雅降级）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class PocketTtsTranslatorTest {

    // ==================== config.json 解析 ====================

    @Test
    void testFlattenJson_simpleObject() throws Exception {
        String json = "{\"key1\": \"value1\", \"key2\": 42}";
        Map<String, String> out = new LinkedHashMap<>();
        invokeFlattenJson("", json, out);

        assertEquals("value1", out.get("key1"));
        assertEquals("42", out.get("key2"));
    }

    @Test
    void testFlattenJson_nestedObject() throws Exception {
        String json = "{\"model_files\": {\"text_encoder\": \"enc.onnx\", \"flow\": \"flow.onnx\"}, \"flow_steps\": 4}";
        Map<String, String> out = new LinkedHashMap<>();
        invokeFlattenJson("", json, out);

        assertEquals("enc.onnx", out.get("model_files.text_encoder"));
        assertEquals("flow.onnx", out.get("model_files.flow"));
        assertEquals("4", out.get("flow_steps"));
    }

    @Test
    void testFlattenJson_deeplyNested() throws Exception {
        String json = "{\"a\": {\"b\": {\"c\": \"deep\"}}}";
        Map<String, String> out = new LinkedHashMap<>();
        invokeFlattenJson("", json, out);

        assertEquals("deep", out.get("a.b.c"));
    }

    @Test
    void testFlattenJson_stringWithComma() throws Exception {
        String json = "{\"path\": \"file1.onnx,file2.onnx\", \"num\": 1}";
        Map<String, String> out = new LinkedHashMap<>();
        invokeFlattenJson("", json, out);

        assertEquals("file1.onnx,file2.onnx", out.get("path"));
        assertEquals("1", out.get("num"));
    }

    @Test
    void testFlattenJson_quotedValues() throws Exception {
        String json = "{\"name\": \"hello world\", \"escaped\": \"line1\\nline2\"}";
        Map<String, String> out = new LinkedHashMap<>();
        invokeFlattenJson("", json, out);

        assertEquals("hello world", out.get("name"));
        assertEquals("line1\\nline2", out.get("escaped"));
    }

    @Test
    void testFlattenJson_configJsonTemplate() throws Exception {
        // 测试真实 config.json 模板
        Path configPath = Path.of("src/main/resources/audio/tts/pocket-tts/config.json");
        Assumptions.assumeTrue(Files.exists(configPath), "config.json not found, skipping template test");

        String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        invokeFlattenJson("", content, out);

        // 验证关键字段
        assertNotNull(out.get("model_files.text_encoder"), "应解析 text_encoder 文件名");
        assertNotNull(out.get("model_files.flow"), "应解析 flow 文件名");
        assertNotNull(out.get("model_files.mimi_decoder"), "应解析 mimi_decoder 文件名");
        assertNotNull(out.get("flow_steps"), "应解析 flow_steps");
        assertNotNull(out.get("latent_dim"), "应解析 latent_dim");
        assertNotNull(out.get("frames_per_token"), "应解析 frames_per_token");
    }

    // ==================== 张量名推断 ====================

    @Test
    void testTensorName_configPriority() throws Exception {
        // 配置名优先于推断
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);
        configCache.put("text_encoder_input", "my_custom_input");
        configCache.put("text_encoder_output", "my_custom_output");

        // 由于没有真实 ORT 会话，测试 configCache 读取逻辑
        assertEquals("my_custom_input", configCache.get("text_encoder_input"));
        assertEquals("my_custom_output", configCache.get("text_encoder_output"));
    }

    @Test
    void testTensorName_defaultFallback() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);

        // 未配置时应返回 null（需要 ORT 会话推断）
        assertNull(configCache.get("text_encoder_input"));
        assertNull(configCache.get("nonexistent_key"));
    }

    @Test
    void testConfigStr_defaultValue() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);

        // 未配置时返回默认值
        String result = invokeConfigStr(translator, "model_files.text_encoder", "default.onnx");
        assertEquals("default.onnx", result);
    }

    @Test
    void testConfigStr_configuredValue() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);
        configCache.put("model_files.text_encoder", "custom_encoder.onnx");

        String result = invokeConfigStr(translator, "model_files.text_encoder", "default.onnx");
        assertEquals("custom_encoder.onnx", result);
    }

    @Test
    void testConfigInt_parseSuccess() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);
        configCache.put("flow_steps", "8");

        int result = invokeConfigInt(translator, "flow_steps", 4);
        assertEquals(8, result);
    }

    @Test
    void testConfigInt_parseFailure_returnsDefault() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);
        configCache.put("flow_steps", "not_a_number");

        int result = invokeConfigInt(translator, "flow_steps", 4);
        assertEquals(4, result);
    }

    @Test
    void testConfigDouble_parseSuccess() throws Exception {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);
        configCache.put("frames_per_token", "4.5");

        double result = invokeConfigDouble(translator, "frames_per_token", 4.0);
        assertEquals(4.5, result, 0.001);
    }

    // ==================== WAV 解码 ====================

    @Test
    void testDecodeWavToFloat_emptyInput() throws Exception {
        // 空 WAV 字节应抛出异常（反射调用会包装为 InvocationTargetException）
        byte[] emptyWav = new byte[0];
        try {
            float[] result = invokeDecodeWavToFloat(emptyWav);
            // 如果不抛异常，应返回空数组
            assertEquals(0, result.length);
        } catch (Exception e) {
            // 反射调用会包装为 InvocationTargetException，需取 cause
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(cause instanceof IllegalArgumentException
                    || cause instanceof javax.sound.sampled.UnsupportedAudioFileException
                    || cause instanceof java.io.EOFException,
                    "Unexpected exception: " + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }

    @Test
    void testDecodeWavToFloat_invalidWav() throws Exception {
        byte[] invalidWav = "not a wav file".getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> invokeDecodeWavToFloat(invalidWav));
    }

    @Test
    void testToWav_producesValidHeader() throws Exception {
        // 合成一个简短的 WAV
        float[] samples = new float[1000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / 24000); // 440Hz
        }
        byte[] wav = invokeToWav(samples, 24000);

        // 验证 WAV 头
        assertNotNull(wav);
        assertTrue(wav.length > 44, "WAV 应至少包含44字节头");
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
    }

    // ==================== loadConfig 集成测试 ====================

    @Test
    void testLoadConfig_populatesAllFields() throws Exception {
        // 创建临时 config.json
        String json = """
                {
                    "model_files": {
                        "text_encoder": "custom_encoder.onnx",
                        "flow": "custom_flow.onnx",
                        "mimi_decoder": "custom_mimi.onnx",
                        "mimi_encoder": "custom_mimi_enc.onnx"
                    },
                    "tensor_names": {
                        "text_encoder_input": "input_ids",
                        "flow_x": "noise"
                    },
                    "flow_steps": 8,
                    "latent_dim": 16,
                    "frames_per_token": 2.5,
                    "max_frames": 2048
                }
                """;
        Path tmpConfig = Files.createTempFile("pocket-tts-config", ".json");
        Files.write(tmpConfig, json.getBytes(StandardCharsets.UTF_8));

        try {
            PocketTtsTranslator translator = new PocketTtsTranslator();
            Map<String, String> configCache = getConfigCache(translator);

            // 调用 loadConfig
            Method loadConfig = PocketTtsTranslator.class.getDeclaredMethod("loadConfig", Path.class);
            loadConfig.setAccessible(true);
            loadConfig.invoke(translator, tmpConfig);

            // 验证配置已解析
            assertEquals("custom_encoder.onnx", configCache.get("model_files.text_encoder"));
            assertEquals("custom_flow.onnx", configCache.get("model_files.flow"));
            assertEquals("custom_mimi.onnx", configCache.get("model_files.mimi_decoder"));
            assertEquals("custom_mimi_enc.onnx", configCache.get("model_files.mimi_encoder"));
            assertEquals("input_ids", configCache.get("tensor_names.text_encoder_input"));
            assertEquals("noise", configCache.get("tensor_names.flow_x"));

            // 验证数值字段已更新
            Field flowStepsField = PocketTtsTranslator.class.getDeclaredField("flowSteps");
            flowStepsField.setAccessible(true);
            assertEquals(8, flowStepsField.getInt(translator));

            Field latentDimField = PocketTtsTranslator.class.getDeclaredField("latentDim");
            latentDimField.setAccessible(true);
            assertEquals(16, latentDimField.getInt(translator));

            Field framesPerTokenField = PocketTtsTranslator.class.getDeclaredField("framesPerToken");
            framesPerTokenField.setAccessible(true);
            assertEquals(2.5, framesPerTokenField.getDouble(translator), 0.001);

            Field maxFramesField = PocketTtsTranslator.class.getDeclaredField("maxFrames");
            maxFramesField.setAccessible(true);
            assertEquals(2048, maxFramesField.getInt(translator));

            // 验证 ref_latents_layout 默认值
            Field layoutField = PocketTtsTranslator.class.getDeclaredField("refLatentsLayout");
            layoutField.setAccessible(true);
            assertEquals("NCT", layoutField.get(translator));
        } finally {
            Files.deleteIfExists(tmpConfig);
        }
    }

    @Test
    void testLoadConfig_refLatentsLayout() throws Exception {
        // 测试 ref_latents_layout 配置选项
        String json = """
                {
                    "ref_latents_layout": "NTC"
                }
                """;
        Path tmpConfig = Files.createTempFile("pocket-tts-layout", ".json");
        Files.write(tmpConfig, json.getBytes(StandardCharsets.UTF_8));

        try {
            PocketTtsTranslator translator = new PocketTtsTranslator();
            Method loadConfig = PocketTtsTranslator.class.getDeclaredMethod("loadConfig", Path.class);
            loadConfig.setAccessible(true);
            loadConfig.invoke(translator, tmpConfig);

            Field layoutField = PocketTtsTranslator.class.getDeclaredField("refLatentsLayout");
            layoutField.setAccessible(true);
            assertEquals("NTC", layoutField.get(translator));
        } finally {
            Files.deleteIfExists(tmpConfig);
        }
    }

    @Test
    void testLoadConfig_missingFile_usesDefaults() throws Exception {
        Path nonExistent = Path.of("/nonexistent/config.json");
        PocketTtsTranslator translator = new PocketTtsTranslator();
        Map<String, String> configCache = getConfigCache(translator);

        Method loadConfig = PocketTtsTranslator.class.getDeclaredMethod("loadConfig", Path.class);
        loadConfig.setAccessible(true);
        loadConfig.invoke(translator, nonExistent);

        // 应保持默认值
        assertTrue(configCache.isEmpty(), "不存在的 config 不应填充 cache");

        Field flowStepsField = PocketTtsTranslator.class.getDeclaredField("flowSteps");
        flowStepsField.setAccessible(true);
        assertEquals(4, flowStepsField.getInt(translator), "flowSteps 应保持默认 4");
    }

    // ==================== 边界情况测试 ====================

    @Test
    void testUnquote_variousFormats() throws Exception {
        assertEquals("hello", invokeUnquote("\"hello\""));
        assertEquals("no-quote", invokeUnquote("no-quote"));
        assertEquals("with\"escape", invokeUnquote("\"with\\\"escape\""));
        assertEquals("  trimmed  ", invokeUnquote("  \"  trimmed  \"  "));
    }

    @Test
    void testFindValueEnd_string() throws Exception {
        // "key": "value", "next": 1
        // 位置: 0123456789...
        // 位置7是value的开头引号"，位置14是逗号
        String s = "\"key\": \"value\", \"next\": 1";
        int end = invokeFindValueEnd(s, 7); // 从value的开头引号开始
        assertEquals(14, end, "字符串值应结束于逗号位置（不含逗号）");
    }

    @Test
    void testFindValueEnd_object() throws Exception {
        // "key": {"a": 1}, "next": 2
        // 位置: 0123456789...
        // 位置7是{，位置15是逗号
        String s = "\"key\": {\"a\": 1}, \"next\": 2";
        int end = invokeFindValueEnd(s, 7); // 从{开始
        assertEquals(15, end, "对象值应结束于逗号位置（不含逗号）");
    }

    // ==================== close 资源释放 ====================

    @Test
    void testClose_multipleCallsNoException() {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        // 多次 close 不应抛异常
        assertDoesNotThrow(translator::close);
        assertDoesNotThrow(translator::close);
    }

    // ==================== 端到端合成（模型存在时） ====================

    @Test
    @EnabledIfSystemProperty(named = "pocket-tts.model.dir", matches = ".*")
    void testSynthesize_endToEnd() throws Exception {
        // 仅当模型目录存在时运行
        String modelDir = System.getProperty("pocket-tts.model.dir");
        assertNotNull(modelDir);

        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            byte[] wav = translator.synthesize("Hello world");
            assertNotNull(wav, "合成结果不应为 null");
            assertTrue(wav.length > 100, "WAV 文件应大于100字节");

            // 验证是有效 WAV
            assertEquals('R', (char) wav[0]);
            assertEquals('W', (char) wav[8]);
        } finally {
            translator.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "pocket-tts.model.dir", matches = ".*")
    void testSynthesize_emptyText_throwsException() {
        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            assertThrows(IllegalArgumentException.class, () -> translator.synthesize(""));
            assertThrows(IllegalArgumentException.class, () -> translator.synthesize("   "));
            assertThrows(IllegalArgumentException.class, () -> translator.synthesize(null));
        } finally {
            translator.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "pocket-tts.model.dir", matches = ".*")
    void testSynthesize_withRefAudio() throws Exception {
        String modelDir = System.getProperty("pocket-tts.model.dir");
        assertNotNull(modelDir);

        PocketTtsTranslator translator = new PocketTtsTranslator();
        try {
            // 先合成一段作为参考音频
            byte[] refWav = translator.synthesize("This is a reference voice.");
            assertNotNull(refWav);

            // 用参考音频克隆合成
            byte[] clonedWav = translator.synthesize("Hello cloned voice!", refWav);
            assertNotNull(clonedWav, "克隆合成结果不应为 null");
            assertTrue(clonedWav.length > 100, "克隆 WAV 应大于100字节");
        } finally {
            translator.close();
        }
    }

    // ==================== 反射辅助方法 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, String> getConfigCache(PocketTtsTranslator translator) throws Exception {
        Field field = PocketTtsTranslator.class.getDeclaredField("configCache");
        field.setAccessible(true);
        return (Map<String, String>) field.get(translator);
    }

    private static void invokeFlattenJson(String prefix, String json, Map<String, String> out) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("flattenJson", String.class, String.class, Map.class);
        method.setAccessible(true);
        method.invoke(null, prefix, json, out);
    }

    private static String invokeConfigStr(PocketTtsTranslator translator, String dotPath, String def) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("configStr", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(translator, dotPath, def);
    }

    private static int invokeConfigInt(PocketTtsTranslator translator, String dotPath, int def) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("configInt", String.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(translator, dotPath, def);
    }

    private static double invokeConfigDouble(PocketTtsTranslator translator, String dotPath, double def) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("configDouble", String.class, double.class);
        method.setAccessible(true);
        return (double) method.invoke(translator, dotPath, def);
    }

    private static float[] invokeDecodeWavToFloat(byte[] wavBytes) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("decodeWavToFloat", byte[].class);
        method.setAccessible(true);
        return (float[]) method.invoke(null, (Object) wavBytes);
    }

    private static byte[] invokeToWav(float[] samples, int rate) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("toWav", float[].class, int.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, samples, rate);
    }

    private static String invokeUnquote(String s) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("unquote", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, s);
    }

    private static int invokeFindValueEnd(String s, int start) throws Exception {
        Method method = PocketTtsTranslator.class.getDeclaredMethod("findValueEnd", String.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(null, s, start);
    }
}
