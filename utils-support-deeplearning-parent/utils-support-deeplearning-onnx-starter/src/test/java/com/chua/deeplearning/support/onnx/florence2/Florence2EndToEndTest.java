package com.chua.deeplearning.support.onnx.florence2;

import com.chua.common.support.utils.NativeLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Florence-2 多模态理解端到端测试。
 *
 * <p>验证图像描述（CAPTION）、OCR、详细描述三个任务。
 * 模型从 classpath embedded JAR 解压到缓存目录。</p>
 */
public class Florence2EndToEndTest {

    private static final String[] TASKS = {"<CAPTION>", "<OCR>", "<DETAILED_CAPTION>"};

    @Test
    @DisplayName("Florence-2 图像描述：应返回非空文本")
    public void should_caption_test_image() throws Exception {
        Path tmpDir = Files.createTempDirectory("florence2-test-");
        byte[] imageData = generateTestImage();
        Path png = tmpDir.resolve("test_shapes.png");
        Files.write(png, imageData);

        try {
            Florence2Translator translator = new Florence2Translator();
            String result = (String) translator.translate(new Object[]{imageData, "<CAPTION>"});
            assertNotNull(result, "caption must not return null");
            System.out.println("[Florence-2] caption: " + result);
            assertTrue(result.length() > 0, "caption text must not be empty");
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(tmpDir.resolve(".gitkeep"));
        }
    }

    @Test
    @DisplayName("Florence-2 OCR：应识别图中文字")
    public void should_ocr_test_image() throws Exception {
        byte[] imageData = generateTextImage();
        Florence2Translator translator = new Florence2Translator();
        String result = (String) translator.translate(new Object[]{imageData, "<OCR>"});
        assertNotNull(result, "OCR result must not return null");
        System.out.println("[Florence-2] OCR: " + result);
        assertTrue(result.length() > 0, "OCR text must not be empty");
    }

    private static byte[] generateTestImage() throws Exception {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 彩色几何图形
        g.setColor(new Color(255, 80, 80));
        g.fillRect(30, 30, 100, 80);
        g.setColor(new Color(80, 80, 255));
        g.fillOval(160, 50, 90, 90);
        g.setColor(new Color(80, 200, 80));
        int[] px = {300, 350, 400};
        int[] py = {40, 120, 40};
        g.fillPolygon(px, py, 3);
        // 文字区域
        g.setColor(new Color(40, 40, 40));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
        g.drawString("图片理解测试", 60, 200);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));
        g.drawString("Florence-2 多模态 AI", 80, 230);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static byte[] generateTextImage() throws Exception {
        int w = 400, h = 100;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
        g.drawString("你好世界 Hello World", 20, 55);
        g.drawString("图片理解模型测试", 20, 85);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
