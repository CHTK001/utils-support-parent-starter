package com.chua.deeplearning.support.onnx.seg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * EfficientSAM-Ti SegmentTranslator 端到端真实推理测试。
 *
 * <p>验证：
 * <ol>
 *   <li>从 jar 加载 efficientsam_ti_encoder.onnx (24MB) + efficientsam_ti_decoder.onnx (16MB)</li>
 *   <li>合成 640x640 测试图（中心白方块，提示框包住中心）</li>
 *   <li>Encoder → image_embeddings [1,256,64,64]</li>
 *   <li>Decoder + bbox 提示 → output_masks [1,1,3,H,W] + iou_predictions [1,1,3]</li>
 *   <li>取 IoU 最高掩码 → 二值掩码 → 前景像素占比 > 5%</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EfficientSamSegmentTest {

    /**
     * 合成测试图像尺寸
     */
    private static final int SRC_SIZE = 640;

    /**
     * 合成图像中目标物体尺寸（中心白方块）
     */
    private static final int OBJECT_SIZE = 240;

    /**
     * 前景像素占比下限
     */
    private static final double MIN_FOREGROUND_RATIO = 0.05;

    public static void main(String[] args) {
        EfficientSamSegmentTest runner = new EfficientSamSegmentTest();
        System.exit(runner.runTest() ? 0 : 1);
    }

    /**
     * 跑全流程。
     *
     * @return 是否通过
     */
    public boolean runTest() {
        try {
            long start = System.currentTimeMillis();
            BufferedImage src = buildSyntheticImage(SRC_SIZE, OBJECT_SIZE);
            Path tmp = Files.createTempFile("efficientsam-input-", ".png");
            ImageIO.write(src, "png", tmp.toFile());
            Image input = ImageFactory.getInstance().fromFile(tmp);
            log.info("[INFO] 合成测试图: {}x{} 文件={}", SRC_SIZE, SRC_SIZE, tmp);

            int cx = SRC_SIZE / 2;
            int cy = SRC_SIZE / 2;
            int half = OBJECT_SIZE / 2;
            float[] box = new float[]{
                    cx - half - 10,
                    cy - half - 10,
                    cx + half + 10,
                    cy + half + 10
            };

            EfficientSamSegmentTranslator translator = new EfficientSamSegmentTranslator();
            Image result = translator.segment(input, box);
            long elapsed = System.currentTimeMillis() - start;

            Object wrapped = result.getWrappedImage();
            if (!(wrapped instanceof BufferedImage)) {
                log.error("[FAIL] 输出不是 BufferedImage: {}", wrapped == null ? "null" : wrapped.getClass());
                return false;
            }
            BufferedImage maskBuf = (BufferedImage) wrapped;
            int mw = maskBuf.getWidth();
            int mh = maskBuf.getHeight();

            int white = 0;
            int total = mw * mh;
            for (int y = 0; y < mh && y < SRC_SIZE; y++) {
                for (int x = 0; x < mw && x < SRC_SIZE; x++) {
                    int rgb = maskBuf.getRGB(x, y) & 0xFF;
                    if (rgb > 128) {
                        white++;
                    }
                }
            }
            double ratio = (double) white / Math.max(1, total);

            Path outPath = tmp.getParent().resolve("efficientsam-mask.png");
            ImageIO.write(maskBuf, "png", outPath.toFile());

            boolean ok = ratio >= MIN_FOREGROUND_RATIO;
            log.info("[PASS={}] EfficientSAM 推理 {}ms: 掩码 {}x{} 前景 {}/{} ratio={:.4f} 输出={}",
                    ok, elapsed, mw, mh, white, total, ratio, outPath);
            return ok;
        } catch (Exception e) {
            log.error("[FAIL] EfficientSAM 推理异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 合成测试图：黑底 + 中心白方块。
     */
    private BufferedImage buildSyntheticImage(int size, int obj) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, size, size);
            g.setColor(Color.WHITE);
            int x = (size - obj) / 2;
            int y = (size - obj) / 2;
            g.fillRect(x, y, obj, obj);
        } finally {
            g.dispose();
        }
        return img;
    }
}