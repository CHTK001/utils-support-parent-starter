package com.chua.deeplearning.support.onnx.seg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * EdgeSAM SegmentTranslator 端到端真实推理测试（SAM 边缘剪枝版）。
 *
 * <p>验证：
 * <ol>
 *   <li>从 jar 加载 edge_sam_encoder.onnx (21MB) + edge_sam_decoder.onnx (15MB)</li>
 *   <li>合成 1024x1024 测试图（中心放白方块，提示框包住中心）</li>
 *   <li>Encoder → image_embeddings [1,256,64,64]</li>
 *   <li>Decoder + bbox 提示 → scores [1,4] + masks [1,4,256,256]</li>
 *   <li>选最高分掩码 → 二值灰度图 [src_w, src_h]</li>
 *   <li>前景像素占比 > 5%（确保真的"切到东西"，而不是全空）</li>
 * </ol>
 *
 * <p>用法：
 * <pre>{@code
 *   java -cp <classpath> com.chua.deeplearning.support.onnx.seg.EdgeSamSegmentTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EdgeSamSegmentTest {

    /**
     * 合成测试图像尺寸
     */
    private static final int SRC_SIZE = 640;

    /**
     * 合成图像中目标物体尺寸（中心白方块）
     */
    private static final int OBJECT_SIZE = 240;

    /**
     * 前景像素占比下限（小于该值视为"没切到东西"）
     */
    private static final double MIN_FOREGROUND_RATIO = 0.05;

    public static void main(String[] args) {
        EdgeSamSegmentTest runner = new EdgeSamSegmentTest();
        System.exit(runner.runTest() ? 0 : 1);
    }

    /**
     * 跑全流程。
     *
     * @return 是否通过
     */
    public boolean runTest() {
        try {
            // 1. 合成测试图：黑色背景 + 中心白方块
            long start = System.currentTimeMillis();
            BufferedImage src = buildSyntheticImage(SRC_SIZE, OBJECT_SIZE);
            Path tmp = Files.createTempFile("edgesam-input-", ".png");
            ImageIO.write(src, "png", tmp.toFile());
            Image input = ImageFactory.getInstance().fromFile(tmp);
            log.info("[INFO] 合成测试图: {}x{} 文件={}", SRC_SIZE, SRC_SIZE, tmp);

            // 2. 提示框：包住中心物体 [x1, y1, x2, y2]（像素坐标）
            int cx = SRC_SIZE / 2;
            int cy = SRC_SIZE / 2;
            int half = OBJECT_SIZE / 2;
            float[] box = new float[]{
                    cx - half - 10,
                    cy - half - 10,
                    cx + half + 10,
                    cy + half + 10
            };

            // 3. 推理
            EdgeSamSegmentTranslator translator = new EdgeSamSegmentTranslator();
            Image maskImage = translator.segment(input, box);
            long elapsed = System.currentTimeMillis() - start;

            // 4. 验证：掩码尺寸 + 前景像素占比
            int mw = maskImage.getWidth();
            int mh = maskImage.getHeight();
            if (mw != SRC_SIZE || mh != SRC_SIZE) {
                log.error("[FAIL] 掩码尺寸不符: {}x{}", mw, mh);
                return false;
            }

            BufferedImage maskBuf = (BufferedImage) maskImage.getWrappedImage();
            int white = 0;
            int total = mw * mh;
            for (int y = 0; y < mh; y++) {
                for (int x = 0; x < mw; x++) {
                    int rgb = maskBuf.getRGB(x, y) & 0xFF;
                    if (rgb > 128) {
                        white++;
                    }
                }
            }
            double ratio = (double) white / total;

            // 5. 保存掩码到临时文件以便肉眼检查
            Path outPath = tmp.getParent().resolve("edgesam-mask.png");
            ImageIO.write(maskBuf, "png", outPath.toFile());

            boolean ok = ratio >= MIN_FOREGROUND_RATIO;
            log.info("[PASS={}] EdgeSAM 推理 {}ms: 掩码 {}x{} 前景像素 {}/{} ratio={:.4f} 输出={}",
                    ok, elapsed, mw, mh, white, total, ratio, outPath);
            return ok;
        } catch (Exception e) {
            log.error("[FAIL] EdgeSAM 推理异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 合成测试图：黑色背景 + 中心白方块。
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