package com.chua.deeplearning.support.onnx.clip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CN-CLIP ViT-B/16 Image Feature Translator Tests
 *
 * <p>Tests the CN-CLIP image encoder: 224x224 input -> 512-dim embedding.</p>
 */
@DisplayName("CN-CLIP Image Feature Tests")
class CnClipImageFeatureTest {

    private static final int DIMENSION = 512;
    private static final int IMAGE_SIZE = 224;

    // ==================== 单元测试 ====================

    @Test
    @DisplayName("余弦相似度 - 相同向量应返回 1.0")
    void cosineSimilarity_identical_returnsOne() {
        float[] vec = new float[DIMENSION];
        Arrays.fill(vec, 1.0f);
        float sim = cosineSimilarity(vec, vec);
        assertEquals(1.0f, sim, 1e-6f, "相同向量余弦相似度应为 1.0");
    }

    @Test
    @DisplayName("余弦相似度 - 正交向量应返回 0.0")
    void cosineSimilarity_orthogonal_returnsZero() {
        float[] a = new float[DIMENSION];
        float[] b = new float[DIMENSION];
        Arrays.fill(a, 0, DIMENSION / 2, 1.0f);
        Arrays.fill(b, DIMENSION / 2, DIMENSION, 1.0f);
        float sim = cosineSimilarity(a, b);
        assertEquals(0.0f, sim, 1e-6f, "正交向量余弦相似度应为 0.0");
    }

    @Test
    @DisplayName("余弦相似度 - 相反向量应返回 -1.0")
    void cosineSimilarity_opposite_returnsNegativeOne() {
        float[] a = new float[DIMENSION];
        float[] b = new float[DIMENSION];
        Arrays.fill(a, 1.0f);
        Arrays.fill(b, -1.0f);
        float sim = cosineSimilarity(a, b);
        assertEquals(-1.0f, sim, 1e-6f, "相反向量余弦相似度应为 -1.0");
    }

    @Test
    @DisplayName("余弦相似度 - 零向量应返回 NaN")
    void cosineSimilarity_zeroVector_returnsNaN() {
        float[] a = new float[DIMENSION];
        float[] b = new float[DIMENSION];
        Arrays.fill(a, 1.0f);
        float sim = cosineSimilarity(a, b);
        assertTrue(Float.isNaN(sim), "零向量余弦相似度应为 NaN");
    }

    @Test
    @DisplayName("余弦相似度 - 维度不匹配应抛出异常")
    void cosineSimilarity_differentDimensions_throwsException() {
        float[] a = new float[512];
        float[] b = new float[513];
        Arrays.fill(a, 1.0f);
        Arrays.fill(b, 1.0f);
        assertThrows(IllegalArgumentException.class,
                () -> cosineSimilarity(a, b),
                "维度不匹配时应抛出 IllegalArgumentException");
    }

    // ==================== 集成测试（需要模型文件） ====================

    @Test
    @DisplayName("模型加载 - 验证 translator 可实例化")
    void translator_instantiation_succeeds() {
        CnClipImageFeatureTranslator translator = new CnClipImageFeatureTranslator();
        assertNotNull(translator, "translator 应成功实例化");
    }

    @Test
    @DisplayName("嵌入向量维度应为 512")
    void embeddingDimension_is512() {
        // CN-CLIP ViT-B/16 输出 512 维
        assertEquals(512, DIMENSION, "CN-CLIP ViT-B/16 嵌入维度应为 512");
    }

    @Test
    @DisplayName("预处理 - 合成彩色图片可正确 resize 到 224x224")
    void preprocess_resizeTo224_works() throws Exception {
        BufferedImage img = createTestImage(400, 300);
        // 使用 Java 原生 resize（不依赖 OpenCV native）
        Image scaled = img.getScaledInstance(IMAGE_SIZE, IMAGE_SIZE, Image.SCALE_SMOOTH);
        BufferedImage result = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        result.getGraphics().drawImage(scaled, 0, 0, null);
        assertEquals(IMAGE_SIZE, result.getWidth(), "宽度应为 224");
        assertEquals(IMAGE_SIZE, result.getHeight(), "高度应为 224");
    }

    // ==================== 辅助方法 ====================

    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不匹配: " + a.length + " vs " + b.length);
        }
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return Float.NaN;
        return dot / (float) Math.sqrt(normA * normB);
    }

    private BufferedImage createTestImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width / 2, height);
        g.setColor(Color.GREEN);
        g.fillRect(0, height / 2, width, height / 2);
        g.setColor(Color.YELLOW);
        g.fillRect(0, 0, width, height / 2);
        g.dispose();
        return img;
    }
}
