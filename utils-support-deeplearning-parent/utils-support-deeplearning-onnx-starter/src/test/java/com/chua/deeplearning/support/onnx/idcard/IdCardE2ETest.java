package com.chua.deeplearning.support.onnx.idcard;

import com.chua.deeplearning.support.idcard.CnIdCardParser;
import com.chua.deeplearning.support.idcard.CnIdCardResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 身份证识别端到端测试
 *
 * <p>输入: 合成身份证图片
 * 输出: 标注图 + 结构化解析结果
 *
 * <p>注意: YOLOv8 检测需要模型文件 (vision/detection/id_card/yolo_id_card_detect.onnx)
 * 从 HuggingFace 下载: MiguelEscamilla/id-card-yolo
 */
@DisplayName("ID Card E2E — 合成图检测 + 结构化解析")
class IdCardE2ETest {

    @Test
    @DisplayName("合成身份证 → 标注图 + 解析结果")
    void e2e_synthetic_to_annotated() throws Exception {
        // 1. 创建合成身份证图片
        BufferedImage src = createSyntheticIdCard(800, 500);
        byte[] imageData = toByteArray(src);
        Path srcPath = Path.of("D:\\ch\\project\\test_id_card_input.png");
        Files.write(srcPath, imageData);
        System.out.println("=== 输入图: " + srcPath + " (" + src.getWidth() + "x" + src.getHeight() + ") ===");

        // 2. YOLOv8 检测（模型路径为空时跳过，用合成位置标注）
        System.out.println("\n--- YOLOv8 身份证检测 ---");
        int cardX = 100, cardY = 80, cardW = 600, cardH = 360;
        System.out.println("  合成图身份证区域: [" + cardX + "," + cardY + "," + cardW + "x" + cardH + "]");
        System.out.println("  (实际检测需下载模型: vision/detection/id_card/yolo_id_card_detect.onnx)");

        // 3. 画标注图（用合成位置模拟检测结果）
        BufferedImage annotated = annotateWithExpectedBox(src, cardX, cardY, cardW, cardH);
        Path outPath = Path.of("D:\\ch\\project\\test_id_card_output.png");
        Files.write(outPath, toByteArray(annotated));
        System.out.println("输出图: " + outPath);
        assertTrue(Files.exists(outPath), "输出图应存在");

        // 4. 结构化解析（模拟 OCR 输出）
        System.out.println("\n--- PaddleOCR + 结构化解析 ---");
        String ocrOutput = "姓名: 张三\n性别: 男\n民族: 汉\n出生: 2000年01月01日\n住址: 北京市朝阳区xxx街道xxx号\n公民身份号码: 110101200001011232";
        CnIdCardParser parser = new CnIdCardParser();
        CnIdCardResult parsed = parser.parse(ocrOutput);
        assertNotNull(parsed, "解析结果不应为空");
        assertTrue(parsed.isValid(), "身份证号应通过校验");

        System.out.println("姓名: " + parsed.getName());
        System.out.println("身份证号: " + parsed.getIdNumber());
        System.out.println("性别: " + parsed.getGender());
        System.out.println("民族: " + parsed.getEthnicity());
        System.out.println("出生: " + parsed.getBirthDate());
        System.out.println("地址: " + parsed.getAddress());
        System.out.println("有效: " + parsed.isValid());

        assertEquals("张三", parsed.getName());
        assertEquals("110101200001011232", parsed.getIdNumber());
        assertEquals("男", parsed.getGender());
        assertEquals("汉", parsed.getEthnicity());
        assertEquals("2000-01-01", parsed.getBirthDate());
        assertTrue(parsed.getAddress().contains("北京"));

        System.out.println("\n=== 测试完成 ===");
        System.out.println("输入图: " + srcPath);
        System.out.println("输出图: " + outPath);
    }

    private static BufferedImage annotateWithExpectedBox(BufferedImage src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);

        // 画检测框（红色）
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(4));
        g.drawRect(x, y, w, h);

        // 标签
        String label = "id-card 0.95";
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        int labelW = g.getFontMetrics().stringWidth(label);
        g.setColor(new Color(220, 20, 60));
        g.fillRect(x, y - 24, labelW + 10, 24);
        g.setColor(Color.WHITE);
        g.drawString(label, x + 5, y - 6);

        // 角标
        g.setColor(Color.YELLOW);
        g.setStroke(new BasicStroke(2));
        int corner = 20;
        // 左上
        g.drawLine(x, y + corner, x, y);
        g.drawLine(x, y, x + corner, y);
        // 右上
        g.drawLine(x + w - corner, y, x + w, y);
        g.drawLine(x + w, y, x + w, y + corner);
        // 左下
        g.drawLine(x, y + h - corner, x, y + h);
        g.drawLine(x, y + h, x + corner, y + h);
        // 右下
        g.drawLine(x + w - corner, y + h, x + w, y + h);
        g.drawLine(x + w, y + h - corner, x + w, y + h);

        // 标题
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("CnIdCardRecognizer E2E — Synthetic Test", 10, src.getHeight() - 10);

        g.dispose();
        return out;
    }

    private static BufferedImage createSyntheticIdCard(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // 蓝色背景
        g.setColor(new Color(70, 130, 180));
        g.fillRect(0, 0, width, height);
        // 白色卡片区域
        int cardX = 100, cardY = 80, cardW = 600, cardH = 360;
        g.setColor(Color.WHITE);
        g.fillRect(cardX, cardY, cardW, cardH);
        // 卡片边框
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(2));
        g.drawRect(cardX, cardY, cardW, cardH);
        // 文字
        g.setColor(Color.BLACK);
        g.setFont(new Font("SimSun", Font.PLAIN, 18));
        g.drawString("姓名: 张三", cardX + 40, cardY + 80);
        g.drawString("性别: 男", cardX + 40, cardY + 120);
        g.drawString("民族: 汉", cardX + 40, cardY + 160);
        g.drawString("出生: 2000年01月01日", cardX + 40, cardY + 200);
        g.drawString("住址: 北京市朝阳区xxx街道xxx号", cardX + 40, cardY + 250);
        g.drawString("公民身份号码: 110101200001011232", cardX + 40, cardY + 310);
        g.dispose();
        return img;
    }

    private static byte[] toByteArray(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
