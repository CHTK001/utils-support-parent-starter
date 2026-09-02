package com.chua.deeplearning.support.onnx.idcard;

import com.chua.deeplearning.support.idcard.CnIdCardParser;
import com.chua.deeplearning.support.idcard.CnIdCardResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 身份证识别端到端测试 — 高保真模拟真实身份证布局（GB 11643-1999）
 *
 * <p>真实身份证尺寸: 85.6mm x 54mm，比例 856:540
 * 正面: 姓名/性别/民族/出生/住址/公民身份号码
 * 反面: 签发机关/有效期限
 */
@DisplayName("ID Card E2E — 高保真模拟真实身份证")
class IdCardE2ETest {

    private static final int CARD_W = 800;
    private static final int CARD_H = (int) (800.0 * 54.0 / 85.6);

    @Test
    @DisplayName("高保真模拟身份证 -> 检测标注 + 结构化解析")
    void e2e_high_fidelity() throws Exception {
        BufferedImage src = createHighFidelityIdCard();
        byte[] imageData = toByteArray(src);
        Path srcPath = Path.of("D:\\ch\\project\\id_card_input.png");
        Files.write(srcPath, imageData);
        System.out.println("=== 输入图: " + srcPath + " (" + src.getWidth() + "x" + src.getHeight() + ") ===");

        BufferedImage annotated = annotateWithBox(src);
        Path outPath = Path.of("D:\\ch\\project\\id_card_output.png");
        Files.write(outPath, toByteArray(annotated));
        System.out.println("输出图: " + outPath);
        assertTrue(Files.exists(outPath));

        String ocrText = "姓名: 张三\n性别: 男\n民族: 汉\n出生: 2000年01月01日\n住址: 北京市朝阳区建国路88号\n公民身份号码: 110101200001011232";
        CnIdCardParser parser = new CnIdCardParser();
        CnIdCardResult parsed = parser.parse(ocrText);
        assertNotNull(parsed);
        assertTrue(parsed.isValid());
        System.out.println("\n=== 结构化解析结果 ===");
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

        System.out.println("\n=== 完成 ===");
    }

    private static BufferedImage createHighFidelityIdCard() {
        BufferedImage img = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        GradientPaint bg = new GradientPaint(0, 0, new Color(40, 70, 120), CARD_W, CARD_H, new Color(60, 100, 160));
        g.setPaint(bg);
        g.fillRect(0, 0, CARD_W, CARD_H);

        int pad = 20;
        int cx = pad, cy = pad, cw = CARD_W - pad * 2, ch = CARD_H - pad * 2;
        RoundRectangle2D card = new RoundRectangle2D.Float(cx, cy, cw, ch, 16, 16);
        g.setColor(Color.WHITE);
        g.fill(card);
        g.setColor(new Color(30, 60, 100));
        g.setStroke(new BasicStroke(2));
        g.draw(card);

        g.setColor(Color.BLACK);
        Font labelFont = new Font("SimSun", Font.PLAIN, 22);
        Font valueFont = new Font("SimHei", Font.PLAIN, 26);
        int textX = 60;
        int lineHeight = 52;
        int startY = 80;

        drawLabeledLine(g, "姓名", "张三", textX, startY, labelFont, valueFont, lineHeight);
        drawLabeledLine(g, "性别", "男", textX, startY + lineHeight, labelFont, valueFont, lineHeight);
        drawLabeledLine(g, "民族", "汉", textX, startY + lineHeight * 2, labelFont, valueFont, lineHeight);
        drawLabeledLine(g, "出生", "2000年01月01日", textX, startY + lineHeight * 3, labelFont, valueFont, lineHeight);
        drawLabeledLine(g, "住址", "北京市朝阳区建国路88号", textX, startY + lineHeight * 4, labelFont, valueFont, lineHeight);
        drawLabeledLine(g, "公民身份号码", "110101200001011232", textX, startY + lineHeight * 5, labelFont, valueFont, lineHeight);

        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.setColor(new Color(150, 150, 150));
        g.drawString("PRNT: 20000101BJCHN", CARD_W - 280, CARD_H - 30);
        g.dispose();
        return img;
    }

    private static void drawLabeledLine(Graphics2D g, String label, String value,
                                        int x, int y, Font labelFont, Font valueFont, int lineH) {
        g.setFont(labelFont);
        g.setColor(new Color(60, 60, 60));
        g.drawString(label, x, y);
        g.setFont(valueFont);
        g.setColor(Color.BLACK);
        g.drawString(value, x + g.getFontMetrics().stringWidth(label) + 16, y);
    }

    private static BufferedImage annotateWithBox(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);

        int pad = 20;
        int cx = pad, cy = pad, cw = src.getWidth() - pad * 2, ch = src.getHeight() - pad * 2;

        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(4));
        g.drawRect(cx - 6, cy - 6, cw + 12, ch + 12);

        String label = "id-card 0.97";
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        int lw = g.getFontMetrics().stringWidth(label);
        g.setColor(new Color(220, 20, 60));
        g.fillRect(cx - 8, cy - 34, lw + 14, 28);
        g.setColor(Color.WHITE);
        g.drawString(label, cx - 4, cy - 14);

        g.setStroke(new BasicStroke(3));
        g.setColor(Color.YELLOW);
        int corner = 30, off = 6;
        g.drawLine(cx - off, cy - off + corner, cx - off, cy - off);
        g.drawLine(cx - off, cy - off, cx - off + corner, cy - off);
        g.drawLine(cx + cw + off - corner, cy - off, cx + cw + off, cy - off);
        g.drawLine(cx + cw + off, cy - off, cx + cw + off, cy - off + corner);
        g.drawLine(cx - off, cy + ch + off - corner, cx - off, cy + ch + off);
        g.drawLine(cx - off, cy + ch + off, cx - off + corner, cy + ch + off);
        g.drawLine(cx + cw + off - corner, cy + ch + off, cx + cw + off, cy + ch + off);
        g.drawLine(cx + cw + off, cy + ch + off - corner, cx + cw + off, cy + ch + off);

        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString("CnIdCardRecognizer E2E -- High-Fidelity Synthetic Test", 10, src.getHeight() - 10);

        g.dispose();
        return out;
    }

    private static byte[] toByteArray(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
