package com.chua.deeplearning.support.onnx.vision.clip;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ViT-H-14 ONNX Translator Tests。
 *
 * <p>测试覆盖：余弦相似度计算、模型元信息、集成测试（需模型文件）。</p>
 */
@DisplayName("ViT-H-14 ONNX Translator Tests")
class VitH14OnnxTranslatorTest {
 * <p>测试覆盖：</p>
 * <ul>
 *     <li>余弦相似度计算（正交向量、相同向量、相反向量）</li>
 *     <li>模型加载与特征提取（需要模型文件，可通过系统属性启用）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class VitH14OnnxTranslatorTest {

    private VitH14OnnxTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new VitH14OnnxTranslator();
    }

    @AfterEach
    void tearDown() {
        if (translator != null) {
            translator.close();
        }
    }

    // ==================== 余弦相似度测试 ====================

    @Test
    @DisplayName("余弦相似度 - 相同向量应返回 1.0")
    void cosineSimilarity_identicalVectors_returnsOne() {
        float[] vec = {1.0f, 2.0f, 3.0f, 4.0f};
        float similarity = VitH14OnnxTranslator.cosineSimilarity(vec, vec);
        assertEquals(1.0f, similarity, 1e-6f, "相同向量的余弦相似度应为 1.0");
    }

    @Test
    @DisplayName("余弦相似度 - 相反向量应返回 -1.0")
    void cosineSimilarity_oppositeVectors_returnsNegativeOne() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {-1.0f, -2.0f, -3.0f};
        float similarity = VitH14OnnxTranslator.cosineSimilarity(a, b);
        assertEquals(-1.0f, similarity, 1e-6f, "相反向量的余弦相似度应为 -1.0");
    }

    @Test
    @DisplayName("余弦相似度 - 正交向量应返回 0.0")
    void cosineSimilarity_orthogonalVectors_returnsZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        float similarity = VitH14OnnxTranslator.cosineSimilarity(a, b);
        assertEquals(0.0f, similarity, 1e-6f, "正交向量的余弦相似度应为 0.0");
    }

    @Test
    @DisplayName("余弦相似度 - 维度不匹配应抛出异常")
    void cosineSimilarity_differentDimensions_throwsException() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {1.0f, 2.0f};
        assertThrows(IllegalArgumentException.class,
                () -> VitH14OnnxTranslator.cosineSimilarity(a, b),
                "维度不匹配时应抛出 IllegalArgumentException");
    }

    @Test
    @DisplayName("余弦相似度 - 部分相似向量")
    void cosineSimilarity_partialSimilar_returnsExpectedRange() {
        float[] a = {1.0f, 0.5f, 0.3f, 0.8f};
        float[] b = {0.9f, 0.6f, 0.2f, 0.7f};
        float similarity = VitH14OnnxTranslator.cosineSimilarity(a, b);
        assertTrue(similarity > 0.9f && similarity <= 1.0f,
                "部分相似向量的余弦相似度应在 (0.9, 1.0] 范围内");
    }

    // ==================== 名称和维度测试 ====================

    @Test
    @DisplayName("模型名称应为 vit-h-14")
    void name_returnsExpectedValue() {
        assertEquals("vit-h-14", translator.name(), "模型名称应为 vit-h-14");
    }

    @Test
    @DisplayName("特征维度应为 1024")
    void featureDimension_returnsExpectedValue() {
        assertEquals(1024, translator.featureDimension(), "特征维度应为 1024");
    }

    // ==================== 零向量测试 ====================

    @Test
    @DisplayName("余弦相似度 - 零向量应返回 NaN")
    void cosineSimilarity_zeroVector_handlesGracefully() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {0.0f, 0.0f, 0.0f};
        float similarity = VitH14OnnxTranslator.cosineSimilarity(a, b);
        assertTrue(Float.isNaN(similarity), "零向量的余弦相似度应为 NaN");
    }

    // ==================== 集成测试（需要模型文件） ====================

    @Test
    @DisplayName("模型元信息 - 验证名称和维度")
    void modelMeta_returnsExpectedValues() {
        VitH14OnnxTranslator t = new VitH14OnnxTranslator();
        assertEquals("vit-h-14", t.name());
        assertEquals(1024, t.featureDimension());
    }

    /**
     * 集成测试：需要模型文件才能运行。
     * <p>通过系统属性 {@code -Dvit.h14.model.path=/path/to/vit-h-14.onnx} 启用。</p>
     */
    @Nested
    @DisplayName("集成测试（需要模型文件）")
    @EnabledIfSystemProperty(named = "vit.h14.model.path", matches = ".+")
    class IntegrationTests {

        @Test
        @DisplayName("模型加载并提取特征")
        void extractFeature_withRealModel_returnsValidFeatures() {
            String modelPath = System.getProperty("vit.h14.model.path");
            VitH14OnnxTranslator realTranslator = new VitH14OnnxTranslator(
                    java.nio.file.Path.of(modelPath));
            try {
                assertNotNull(realTranslator, "Translator 应成功创建");
                assertEquals(1024, realTranslator.featureDimension(), "特征维度应为 1024");
            } finally {
                realTranslator.close();
            }
        }
    }
}
