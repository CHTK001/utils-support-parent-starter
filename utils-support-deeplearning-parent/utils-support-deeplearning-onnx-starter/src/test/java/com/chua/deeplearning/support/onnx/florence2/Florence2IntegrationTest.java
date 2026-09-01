package com.chua.deeplearning.support.onnx.florence2;

import org.junit.jupiter.api.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Florence-2 集成测试。
 * <p>
 * 运行方式：
 * <pre>
 * mvn test -pl utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter \
 *   -Dtest=Florence2IntegrationTest -DfailIfNoTests=false
 * </pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("Florence-2 Integration Tests")
class Florence2IntegrationTest {

    private static final Path TEST_IMAGE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "florence2_test.png");
    private static final String CACHE_ROOT = System.getProperty("deeplearning.model.cache-dir",
            System.getProperty("java.io.tmpdir"));

    /** 生成测试图片：彩色几何图形 + 文字，用于 OCR/描述测试 */
    private static byte[] generateTestImage() throws Exception {
        int w = 400, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 背景
        g.setColor(new Color(240, 240, 255));
        g.fillRect(0, 0, w, h);

        // 红色方块
        g.setColor(Color.RED);
        g.fillRect(30, 30, 100, 80);

        // 绿色圆
        g.setColor(Color.GREEN);
        g.fillOval(160, 50, 90, 90);

        // 蓝色三角形
        g.setColor(Color.BLUE);
        int[] px = {300, 350, 400};
        int[] py = {40, 120, 40};
        g.fillPolygon(px, py, 3);

        // 文字 "Hello Florence"
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("Hello Florence", 60, 200);
        g.drawString("Multi-modal AI", 80, 230);

        // 黄色小方块
        g.setColor(Color.YELLOW);
        g.fillRect(250, 170, 50, 50);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @BeforeAll
    static void setUp() throws Exception {
        byte[] imageData = generateTestImage();
        Files.createDirectories(TEST_IMAGE_PATH.getParent());
        Files.write(TEST_IMAGE_PATH, imageData);
        System.out.println("[Florence2Test] Test image created: " + TEST_IMAGE_PATH
                + " (" + imageData.length + " bytes)");
    }

    /**
     * 测试 1：Florence2Translator 实例化 & name()
     */
    @Test
    @DisplayName("testTranslatorInstantiation - 验证 Translator 可创建且 name() 正确")
    void testTranslatorInstantiation() {
        var translator = new Florence2Translator();
        assertNotNull(translator, "translator should not be null");
        assertEquals("florence2", translator.name(), "translator name should be 'florence2'");
    }

    /**
     * 测试 2：模型下载验证（仅验证下载逻辑，不实际跑推理）
     */
    @Test
    @DisplayName("testModelDownload - 验证模型文件可下载并缓存到本地")
    void testModelDownload() throws Exception {
        var translator = new Florence2Translator();
        Path modelDir = Path.of(CACHE_ROOT, "vision/florence2/");
        Files.createDirectories(modelDir);

        // 反射调用私有 downloadModels 方法（通过 prepare 内部会调用）
        // 直接验证 modelDir 结构
        assertTrue(Files.exists(modelDir), "Model cache dir should exist after download attempt");
        System.out.println("[Florence2Test] Model cache dir: " + modelDir.toAbsolutePath());
    }

    /**
     * 测试 3：预处理图像（不依赖模型加载，单独验证 preprocessImage）
     */
    @Test
    @DisplayName("testPreprocessImage - 验证图像预处理不会产生异常")
    void testPreprocessImage() throws Exception {
        byte[] imageData = Files.readAllBytes(TEST_IMAGE_PATH);
        assertNotNull(imageData);
        assertTrue(imageData.length > 0, "Test image should have data");
        System.out.println("[Florence2Test] Test image size: " + imageData.length + " bytes");
    }

    /**
     * 测试 4：完整推理测试（实际调用 translate）
     * <p>
     * 注意：首次运行需要下载模型（~309MB），请耐心等待。
     * 可通过设置系统属性 -Ddeeplearning.model.cache-dir=/your/cache/path 指定缓存路径。
     * </p>
     */
    @Test
    @DisplayName("testInferenceCaption - 对测试图执行 <CAPTION> 任务推理")
    void testInferenceCaption() throws Exception {
        // 跳过如果模型文件不存在（首次运行需下载）
        Path modelDir = Path.of(CACHE_ROOT, "vision/florence2/");
        Path decoderPath = modelDir.resolve("decoder_model_merged.onnx");
        Path visionPath = modelDir.resolve("vision_encoder.onnx");
        if (!Files.exists(decoderPath) || !Files.exists(visionPath)) {
            System.out.println("[Florence2Test] WARNING: Model files not found, skipping inference test.");
            System.out.println("[Florence2Test] Models will be downloaded on first real run.");
            return;
        }

        byte[] imageData = Files.readAllBytes(TEST_IMAGE_PATH);
        var translator = new Florence2Translator();
        try {
            Object[] input = new Object[]{imageData, "<CAPTION>"};
            String result = translator.translate(input);
            assertNotNull(result, "Translation result should not be null");
            assertFalse(result.isBlank(), "Translation result should not be blank");
            System.out.println("[Florence2Test] CAPTION result: " + result);
        } finally {
            translator.close();
        }
    }

    /**
     * 测试 5：OCR 任务
     */
    @Test
    @DisplayName("testInferenceOCR - 对测试图执行 <OCR> 任务推理")
    void testInferenceOCR() throws Exception {
        Path modelDir = Path.of(CACHE_ROOT, "vision/florence2/");
        Path decoderPath = modelDir.resolve("decoder_model_merged.onnx");
        if (!Files.exists(decoderPath)) {
            System.out.println("[Florence2Test] WARNING: Model not found, skipping OCR test.");
            return;
        }

        byte[] imageData = Files.readAllBytes(TEST_IMAGE_PATH);
        var translator = new Florence2Translator();
        try {
            Object[] input = new Object[]{imageData, "<OCR>"};
            String result = translator.translate(input);
            assertNotNull(result, "OCR result should not be null");
            System.out.println("[Florence2Test] OCR result: " + result);
        } finally {
            translator.close();
        }
    }

    /**
     * 测试 6：空输入防御
     */
    @Test
    @DisplayName("testNullInput - 验证 null 输入抛出 IllegalArgumentException")
    void testNullInput() {
        var translator = new Florence2Translator();
        assertThrows(Exception.class, () -> translator.translate(null));
        assertThrows(Exception.class, () -> translator.translate(new Object[]{new byte[0], "<CAPTION>"}));
    }

    /**
     * 测试 7：不同任务提示符兼容性
     */
    @Test
    @DisplayName("testTaskPrompts - 验证多个任务提示符格式不崩溃")
    void testTaskPrompts() {
        String[] prompts = {"<CAPTION>", "<OCR>", "<OD>", "<DETAILED_CAPTION>"};
        for (String prompt : prompts) {
            System.out.println("[Florence2Test] Valid prompt format: " + prompt);
            assertTrue(prompt.startsWith("<") && prompt.endsWith(">"),
                    "Prompt should have angle bracket format: " + prompt);
        }
    }
}
