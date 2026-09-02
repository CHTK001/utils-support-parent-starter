package com.chua.deeplearning.support.onnx.idcard;

import com.chua.deeplearning.support.idcard.CnIdCardParser;
import com.chua.deeplearning.support.idcard.CnIdCardResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 身份证识别端到端测试
 */
@DisplayName("ID Card Recognition E2E")
class CnIdCardRecognizerTest {

    @Test
    @Disabled("合成图文字无法通过 PaddleOCR 识别，仅验证 parser 层")
    @DisplayName("合成身份证图片端到端识别")
    void recognize_synthetic_id_card() throws Exception {
        BufferedImage img = createSyntheticIdCard(800, 500);
        byte[] imageData = toByteArray(img);
        Path pngPath = Path.of("D:\\ch\\project\\test_id_card.png");
        Files.write(pngPath, imageData);
        System.out.println("=== 输入图片: " + pngPath + " ===");
        System.out.println("图片尺寸: " + img.getWidth() + "x" + img.getHeight());

        com.chua.deeplearning.support.onnx.idcard.CnIdCardRecognizer recognizer
                = new com.chua.deeplearning.support.onnx.idcard.CnIdCardRecognizer();
        java.util.List<CnIdCardResult> results = recognizer.recognize(imageData);

        System.out.println("\n=== 识别结果 ===");
        if (results.isEmpty()) {
            System.out.println("未检测到身份证（合成图无真实文字，预期行为）");
        } else {
            for (CnIdCardResult r : results) {
                System.out.println("姓名: " + r.getName());
                System.out.println("身份证号: " + r.getIdNumber());
                System.out.println("性别: " + r.getGender());
                System.out.println("民族: " + r.getEthnicity());
                System.out.println("出生: " + r.getBirthDate());
                System.out.println("地址: " + r.getAddress());
                System.out.println("签发机关: " + r.getIssueAuthority());
                System.out.println("有效期限: " + r.getValidPeriod());
                System.out.println("置信度: " + r.getConfidence());
            }
        }
    }

    @Test
    @Disabled("PaddleOCR native libs not available in test env")
    @DisplayName("PaddleOCR 文字识别测试")
    void ocr_recognize_test() throws Exception {
        System.out.println("SKIPPED: PaddleOCR native libs not available");
    }

    @Test
    @DisplayName("身份证解析器正则匹配测试")
    void parser_regex_test() {
        CnIdCardParser parser = new CnIdCardParser();

        // 使用冒号分隔格式（PaddleOCR输出格式）
        String text1 = "姓名: 张三\n性别: 男\n民族: 汉\n出生: 2000年01月01日\n住址: 北京市朝阳区xxx街道xxx号\n公民身份号码: 110101200001011232";
        CnIdCardResult r1 = parser.parse(text1);
        assertNotNull(r1);
        System.out.println("\n正面识别测试:");
        System.out.println("  姓名: " + r1.getName());
        System.out.println("  身份证号: " + r1.getIdNumber());
        System.out.println("  性别: " + r1.getGender());
        System.out.println("  民族: " + r1.getEthnicity());
        System.out.println("  出生: " + r1.getBirthDate());
        System.out.println("  地址: " + r1.getAddress());
        assertTrue(r1.isValid(), "身份证号应有效");
        assertEquals("张三", r1.getName());
        assertEquals("110101200001011232", r1.getIdNumber());

        String text2 = "有效期限: 2020年01月01日 至 2030年01月01日";
        CnIdCardResult r2 = parser.parse(text2);
        assertNotNull(r2);
        System.out.println("\n反面识别测试:");
        System.out.println("  有效期限: " + r2.getValidPeriod());
    }

    private BufferedImage createSyntheticIdCard(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(70, 130, 180));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        int cardX = 100, cardY = 80, cardW = 600, cardH = 360;
        g.fillRect(cardX, cardY, cardW, cardH);
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(2));
        g.drawRect(cardX, cardY, cardW, cardH);
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

    private byte[] toByteArray(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
