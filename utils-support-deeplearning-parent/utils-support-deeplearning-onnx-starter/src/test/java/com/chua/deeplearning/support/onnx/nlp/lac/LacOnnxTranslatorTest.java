package com.chua.deeplearning.support.onnx.nlp.lac;

import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * lac-onnx 生产级验收：覆盖 Translator 全部公开入口。
 *
 * <ul>
 *   <li>{@link LacOnnxTranslator#name()} 标识</li>
 *   <li>{@link LacOnnxTranslator#translate(String)}：null/空/单字/长文本截断/真实分词/资源缺失</li>
 *   <li>{@link LacOnnxTranslator#close()}：未加载关闭、加载后关闭、重复关闭</li>
 *   <li>注册表：modelId/input/output/capability/relativePath/downloadUrl</li>
 *   <li>jar 内嵌资源：word/tag/q2b.dic + npy + encoder 全部存在且格式正确</li>
 *   <li>内部解码：BIO 合并/Viterbi/npy 解析（反射覆盖私有分支）</li>
 * </ul>
 */
class LacOnnxTranslatorTest {

    @Test
    void registered() {
        ModelRegistry.discoverAll();
        assertEquals("lac-onnx", new LacOnnxTranslator().name());
        ModelRegistry.Entry e = ModelRegistry.get("lac-onnx");
        assertNotNull(e);
        assertEquals(String.class, e.inputType());
        assertEquals(String[][].class, e.outputType());
        assertNotNull(e.capabilityInterface());
        assertEquals("nlp/lac/lac_encoder.onnx", e.relativePath());
        assertNotNull(e.downloadUrl());
        assertTrue(e.downloadUrl().endsWith("lac_encoder.onnx"), e.downloadUrl());
        assertNotNull(e.downloadMirrors());
        assertFalse(e.downloadMirrors().isEmpty());
    }

    @Test
    void emptyInput() {
        try (LacOnnxTranslator t = new LacOnnxTranslator()) {
            assertEquals(0, t.translate("").length);
            assertEquals(0, t.translate(null).length);
        }
    }

    @Test
    void closeWithoutLoad() {
        LacOnnxTranslator t = new LacOnnxTranslator();
        assertDoesNotThrow(t::close);
        assertDoesNotThrow(t::close);
    }

    @Test
    void bundledResourcesPresent() {
        ClassLoader loader = LacOnnxTranslator.class.getClassLoader();
        assertResource(loader, "nlp/lac/word.dic", 700_000);
        assertResource(loader, "nlp/lac/tag.dic", 400);
        assertResource(loader, "nlp/lac/q2b.dic", 40_000);
        assertResource(loader, "nlp/lac/lac_crf_transitions.npy", 14_000);
        assertResource(loader, "nlp/lac/lac_encoder.onnx", 30_000_000);
    }

    @Test
    void tagDicFormat() throws Exception {
        int count = 0;
        int maxId = -1;
        try (InputStream in = resource("nlp/lac/tag.dic");
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String[] kv = line.split("\t");
                assertEquals(2, kv.length, line);
                int id = Integer.parseInt(kv[0].trim());
                maxId = Math.max(maxId, id);
                assertFalse(kv[1].trim().isEmpty());
                count++;
            }
        }
        assertEquals(57, count);
        assertEquals(48, maxId);
    }

    @Test
    void wordDicFormat() throws Exception {
        long maxId = -1;
        int count = 0;
        boolean hasOov = false;
        try (InputStream in = resource("nlp/lac/word.dic");
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                assertTrue(tab > 0, "无tab: " + line);
                long id = Long.parseLong(line.substring(0, tab).trim());
                maxId = Math.max(maxId, id);
                count++;
                if ("OOV".equals(line.substring(tab + 1))) {
                    hasOov = true;
                }
            }
        }
        assertEquals(58224, count);
        assertEquals(58223, maxId);
        assertTrue(hasOov);
    }

    @Test
    void crfNpyHeader() throws Exception {
        try (InputStream in = resource("nlp/lac/lac_crf_transitions.npy")) {
            byte[] head = in.readNBytes(128);
            assertEquals((byte) 0x93, head[0]);
            assertEquals('N', head[1]);
            int hlen = (head[8] & 0xFF) | ((head[9] & 0xFF) << 8);
            String header = new String(head, 10, hlen, StandardCharsets.US_ASCII);
            assertTrue(header.contains("59, 59"), header);
            assertTrue(header.contains("<f4"), header);
        }
    }

    @Test
    void decodeBioBranches() throws Exception {
        try (LacOnnxTranslator t = new LacOnnxTranslator()) {
            Method loadTag = LacOnnxTranslator.class.getDeclaredMethod("loadTagDic", java.nio.file.Path.class);
            Method decode = LacOnnxTranslator.class.getDeclaredMethod("decodeBio", String.class, int[].class);
            loadTag.setAccessible(true);
            decode.setAccessible(true);
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("tag", ".dic");
            java.nio.file.Files.writeString(tmp, "0\tn-B\n1\tn-I\n2\tPER-B\n3\tPER-I\n4\tO\n", StandardCharsets.UTF_8);
            loadTag.invoke(t, tmp);
            // B- 开新词 + I- 续接；"工作"两字 n-B/n-I 各自成词（上游语义：仅 -I 续接）
            String[][] r = (String[][]) decode.invoke(t, "北京工作", new int[]{2, 3, 0, 0});
            assertEquals(3, r.length);
            assertEquals("北京", r[0][0]);
            assertEquals("PER", r[0][1]);
            assertEquals("工", r[1][0]);
            assertEquals("n", r[1][1]);
            // 孤立 I- 续接上一词（上游 _decode_bio 语义）
            r = (String[][]) decode.invoke(t, "北京", new int[]{2, 1});
            assertEquals(1, r.length);
            assertEquals("北京", r[0][0]);
            // 缺省标签 -> n-B
            r = (String[][]) decode.invoke(t, "好", new int[]{99});
            assertEquals(1, r.length);
            assertEquals("好", r[0][0]);
            assertEquals("n", r[0][1]);
            // O 单字成词
            r = (String[][]) decode.invoke(t, "，。", new int[]{4, 4});
            assertEquals(2, r.length);
            assertEquals("O", r[0][1]);
        }
    }

    @Test
    void viterbiPath() throws Exception {
        try (LacOnnxTranslator t = new LacOnnxTranslator()) {
            java.lang.reflect.Field f = LacOnnxTranslator.class.getDeclaredField("transitions");
            f.setAccessible(true);
            f.set(t, new float[][]{{0f, -10f}, {-10f, 0f}});
            java.lang.reflect.Field n = LacOnnxTranslator.class.getDeclaredField("numTags");
            n.setAccessible(true);
            n.set(t, 2);
            Method v = LacOnnxTranslator.class.getDeclaredMethod("viterbi", float[][].class, int.class);
            v.setAccessible(true);
            int[] path = (int[]) v.invoke(t, new float[][]{{5f, 0f}, {5f, 0f}, {0f, 5f}}, 3);
            // 转移矩阵禁止跨类（-10），末帧 emission 5 不足以跳变，最优仍全 0
            assertArrayEquals(new int[]{0, 0, 0}, path);
        }
    }

    @Test
    void segment() {
        try (LacOnnxTranslator t = new LacOnnxTranslator()) {
            String[][] r = t.translate("张三在北京市工作");
            assertTrue(r.length > 0);
            for (String[] row : r) {
                assertEquals(2, row.length);
            }
            StringBuilder sb = new StringBuilder();
            for (String[] row : r) {
                sb.append(row[0]);
            }
            assertEquals("张三在北京市工作", sb.toString());
            // NER 标签应含 PER/LOC（与上游示例一致）
            boolean hasPer = false;
            boolean hasLoc = false;
            for (String[] row : r) {
                if ("PER".equals(row[1])) {
                    hasPer = true;
                }
                if ("LOC".equals(row[1])) {
                    hasLoc = true;
                }
            }
            assertTrue(hasPer, java.util.Arrays.deepToString(r));
            assertTrue(hasLoc, java.util.Arrays.deepToString(r));
        }
    }

    @Test
    void singleCharAndLongInput() {
        try (LacOnnxTranslator t = new LacOnnxTranslator()) {
            String[][] r = t.translate("好");
            assertEquals(1, r.length);
            assertEquals("好", r[0][0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                sb.append('的');
            }
            // 超长截断不断言抛错，且输出可拼接
            String[][] longR = t.translate(sb.toString());
            assertTrue(longR.length > 0);
            StringBuilder back = new StringBuilder();
            for (String[] row : longR) {
                back.append(row[0]);
            }
            assertEquals(512, back.length());
        }
    }

    @Test
    void closeAfterLoad() {
        LacOnnxTranslator t = new LacOnnxTranslator();
        t.translate("测试关闭");
        assertDoesNotThrow(t::close);
        assertDoesNotThrow(t::close);
        // 关闭后可重新加载
        assertTrue(t.translate("重载").length > 0);
        t.close();
    }

    private static InputStream resource(String name) {
        InputStream in = LacOnnxTranslator.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(in, name);
        return in;
    }

    private static void assertResource(ClassLoader loader, String name, long minSize) {
        try (InputStream in = loader.getResourceAsStream(name)) {
            assertNotNull(in, name);
            long total = 0;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
            }
            assertTrue(total >= minSize, name + " size=" + total);
        } catch (Exception e) {
            fail(name + ": " + e.getMessage());
        }
    }
}
