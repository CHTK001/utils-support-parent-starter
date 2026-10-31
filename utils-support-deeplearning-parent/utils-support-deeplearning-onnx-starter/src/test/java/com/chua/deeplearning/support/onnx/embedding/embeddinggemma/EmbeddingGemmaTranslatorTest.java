package com.chua.deeplearning.support.onnx.embedding.embeddinggemma;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;
import com.chua.deeplearning.support.capability.ModelCapabilities;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.model.HardwareConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * embeddinggemma-2 文本只（text-only）文本嵌入 Translator（String → float[768]）验收。
 *
 * <p>覆盖：注册表条目与能力标签 / {@code name()} / {@code translate()}（768 维、L2 归一化、
 * null/空白、重复一致）/ {@code setModelPath}（文件路径与目录路径）/ {@code close()}，
 * 以及 onnx 引擎下 {@code models(String, String.class, float[].class)} 的可见性。</p>
 *
 * <p>推理测试依赖项目内的离线模型副本（safetensors-starter 的 {@code dlmodel} 目录，
 * 含 {@code model_q4.onnx} + {@code model_q4.onnx_data} + {@code tokenizer.json}）；
 * 未找到时推理测试自动跳过，注册表断言不依赖模型文件</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class EmbeddingGemmaTranslatorTest {

    /** 模型标识 */
    private static final String MODEL_ID = "embeddinggemma-2-embedding";

    /** 模型输出维度 */
    private static final int DIM = 768;

    private static EmbeddingGemmaTranslator translator;

    /** 离线模型目录（项目内 dlmodel），null 表示未找到 */
    private static Path localModelDir;

    @BeforeAll
    static void setUp() {
        localModelDir = resolveLocalModelDir();
        // 只实例化翻译器，不在未就位时让空路径穿透
        translator = new EmbeddingGemmaTranslator();
        if (localModelDir != null) {
            translator.setModelPath(localModelDir.toString());
            ModelRegistry.discoverAll();
        }
    }

    @AfterAll
    static void tearDown() {
        if (translator != null) {
            translator.close();
        }
    }

    /**
     * 解析项目内已下载的离线模型目录，可能性自上而下：
     * <ol>
     *   <li>{@code ../utils-support-deeplearning-safetensors-starter/dlmodel}（模块内）</li>
     *   <li>{@code ../../utils-support-deeplearning-safetensors-starter/dlmodel}（parent 内）</li>
     *   <li>{{@code /D/ch/project/.../safetensors-starter/dlmodel}（落盘绝对路径）</li>
     * </ol>
     */
    private static Path resolveLocalModelDir() {
        String[] candidates = {
                // onnx-starter 模块下 ../safetensors-starter/dlmodel
                "../utils-support-deeplearning-safetensors-starter/dlmodel",
                // 从 parent 目录运行时
                "../../utils-support-deeplearning-safetensors-starter/dlmodel",
                "D:/ch/project/utils-support-parent-starter/"
                        + "utils-support-deeplearning-parent/utils-support-deeplearning-safetensors-starter/dlmodel"
        };
        for (String candidate : candidates) {
            Path p = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("model_q4.onnx"))
                    && Files.isRegularFile(p.resolve("model_q4.onnx_data"))
                    && Files.isRegularFile(p.resolve("tokenizer.json"))) {
                return p;
            }
        }
        return null;
    }

    /** 是否能在本地找到模型（未找到时推理测试跳过） */
    private static void requireOfflineModel() {
        assumeTrue(localModelDir != null,
                "离线模型目录(dlmodel, 含 model_q4.onnx + tokenizer.json) 未找到，跳过推理测试");
    }

    @Test
    void registeredInRegistry() {
        ModelRegistry.Entry entry = ModelRegistry.get(MODEL_ID);
        assertNotNull(entry, MODEL_ID + " 应已注册");
        assertEquals("com.chua.deeplearning.support.onnx.embedding.embeddinggemma.EmbeddingGemmaTranslator",
                entry.translatorClassName());
        assertEquals(String.class, entry.inputType());
        assertEquals(float[].class, entry.outputType());
        assertEquals(FeatureExtractor.class, entry.capabilityInterface());
        assertEquals("nlp/embedding/embeddinggemma-2/model_q4.onnx", entry.relativePath());
        assertNotNull(entry.downloadUrl(), "downloadUrl 不应为空");
        assertTrue(entry.downloadUrl().contains("hf-mirror.com/onnx-community/embeddinggemma-2-ONNX"),
                "主源应为 hf-mirror: " + entry.downloadUrl());
        assertNotNull(entry.downloadMirrors());
        assertTrue(entry.downloadMirrors().stream().anyMatch(u -> 
u.contains("huggingface.co/onnx-community/embeddinggemma-2-ONNX")));
        assertFalse(entry.compress());
        assertEquals(List.of("model_q4.onnx_data", "tokenizer.json"), entry.downloadFiles(),
                "downloadFiles 应补齐 onnx_data 与 tokenizer.json: " + entry.downloadFiles());
        HardwareConfig hw = entry.hardwareConfig();
        assertNotNull(hw, "应标注硬件配置");
        assertEquals("cpu", hw.device());
        assertTrue(hw.recommended(), "应标记为推荐模型");
        assertFalse(hw.isGpu(), "text-only 模型应在 CPU 运行");
    }

    @Test
    void capabilityLabelIsFeature() {
        assertEquals(ModelCapabilities.FEATURE, ModelCapabilities.labelOf(FeatureExtractor.class),
                "FeatureExtractor.class 应映射（feature）能力标签");
    }

    @Test
    void nameReturnsModelId() {
        assertEquals(MODEL_ID, translator.name());
    }

    @Test
    void listedInOnnxEngineModels() {
        List<ModelDefinition> models = DeeplearningModels.models("onnx", String.class, float[].class);
        assertNotNull(models);
        assertTrue(models.stream().anyMatch(d -> MODEL_ID.equals(d.getId())),
                "onnx 引擎下应出现 embeddinggemma-2-embedding（String → float[]）: "
                        + models.size() + " 个模型");
        ModelDefinition found = models.stream().filter(d -> MODEL_ID.equals(d.getId())).findFirst().orElse(null);
        assertNotNull(found);
        assertEquals(List.of("feature"), found.getCapabilities(), "能力标签应为 feature");
        assertEquals("onnx", found.getProvider());
        assertEquals(MODEL_ID, found.getName());
        assertNotNull(found.getDownloadUrl());
    }

    @Test
    void translateReturns768DVector() throws Exception {
        requireOfflineModel();
        float[] vec = translator.translate("Hello world");
        assertNotNull(vec);
        assertEquals(DIM, vec.length, "输出应为 768 维句向量");
        assertTrue(vec[0] != 0.0f, "向量不应全零");
        assertL2Norm(vec);
    }

    @Test
    void translateChinese() throws Exception {
        requireOfflineModel();
        float[] vec = translator.translate("你好世界");
        assertNotNull(vec);
        assertEquals(DIM, vec.length);
        assertL2Norm(vec);
    }

    @Test
    void consistentEmbeddingsSameText() throws Exception {
        requireOfflineModel();
        float[] a = translator.translate("Hello world, this is a test.");
        float[] b = translator.translate("Hello world, this is a test.");
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "第 " + i + " 维不一致");
        }
    }

    @Test
    void translateNullThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> translator.translate(null));
    }

    @Test
    void translateBlankTextReturnsFiniteVector() throws Exception {
        requireOfflineModel();
        float[] vec = translator.translate("");
        assertNotNull(vec);
        assertEquals(DIM, vec.length);
        for (float v : vec) {
            assertTrue(Float.isFinite(v), "输出不能含 NaN/Infinity");
        }
        assertL2Norm(vec);
    }

    @Test
    void setModelPathByFileAndDirWorks() throws Exception {
        requireOfflineModel();
        Path onnxFile = localModelDir.resolve("model_q4.onnx");

        EmbeddingGemmaTranslator byFile = new EmbeddingGemmaTranslator();
        try {
            byFile.setModelPath(onnxFile.toString());
            assertL2Dim(byFile.translate("Hello world"));
        } finally {
            byFile.close();
        }

        EmbeddingGemmaTranslator byDir = new EmbeddingGemmaTranslator();
        try {
            byDir.setModelPath(localModelDir.toString());
            assertL2Dim(byDir.translate("Hello world"));
        } finally {
            byDir.close();
        }
    }

    @Test
    void closeIdempotentAndReusable() throws Exception {
        requireOfflineModel();
        translator.close();
        translator.close();
        float[] vec = translator.translate("Hello world");
        assertNotNull(vec);
        assertL2Dim(vec);
    }

    // ----------------------------------------------------------------- private methods

    private static void assertL2Dim(float[] vec) {
        assertEquals(DIM, vec.length);
        assertL2Norm(vec);
    }

    private static void assertL2Norm(float[] vec) {
        double sum = 0.0;
        for (float v : vec) {
            sum += (double) v * v;
        }
        assertTrue(Math.abs(Math.sqrt(sum) - 1.0) < 1e-4, "L2 norm is 1, actual: " + Math.sqrt(sum));
    }
}
