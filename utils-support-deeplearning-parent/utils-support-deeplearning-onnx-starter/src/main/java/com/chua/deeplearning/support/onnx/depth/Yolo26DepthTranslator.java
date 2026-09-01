package com.chua.deeplearning.support.onnx.depth;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * YOLO26-Depth 单目深度估计翻译器（纯 ONNX Runtime 实现）。
 *
 * <h2>模型说明</h2>
 * <p>基于 Ultralytics YOLO26 的 monocular depth estimation 模型：
 * <ul>
 *   <li><b>输入</b>：letterbox 768x768 RGB float32（除以 255 归一化到 [0,1]），NCHW [1,3,768,768]。</li>
 *   <li><b>输出</b>：深度图 [1,1,768,768]，原始值为 metric depth（值越大越远），展示时转为 disparity（近处亮）。</li>
 *   <li><b>尺寸</b>：n/s/m/l/x 五档，同一 translator 通过 {@code setModelPath} 注入对应模型文件。</li>
 *   <li><b>用途</b>：单目深度估计、背景虚化、3D 场景理解。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Slf4j
public class Yolo26DepthTranslator implements ITranslator<byte[], byte[]>, AutoCloseable {

    /** 模型固定输入尺寸 */
    private static final int MODEL_SIZE = 768;
    /** letterbox 填充灰度值（Ultralytics 约定） */
    private static final int PAD_VALUE = 114;

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private String modelPath;
    private volatile boolean prepared = false;

    /**
     * 设置模型文件路径（仅供 ModelRegistry 在 SPI 实例化后注入使用）。
     *
     * @param modelPath 模型文件绝对路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public String name() {
        return "yolo26-depth";
    }

    @Override
    public byte[] translate(byte[] imageBytes) {
        try {
            ensurePrepared();
            return depth(imageBytes);
        } catch (Exception e) {
            throw new RuntimeException("[yolo26-depth] 推理失败: " + e.getMessage(), e);
        }
    }

    private synchronized void ensurePrepared() throws Exception {
        if (prepared) return;
        if (modelPath == null || !Files.exists(Path.of(modelPath))) {
            throw new IllegalStateException("模型文件不存在: " + modelPath);
        }
        ortEnv = OrtEnvironment.getEnvironment();
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        opts.setOptimizationLevel(SessionOptions.OptLevel.NO_OPT);
        session = ortEnv.createSession(modelPath, opts);
        prepared = true;
        log.info("[Yolo26Depth] ORT ready, model={}", modelPath);
    }

    private byte[] depth(byte[] imageBytes) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
        int origW = src.getWidth();
        int origH = src.getHeight();

        // letterbox 缩放到 768x768
        float gain = Math.min((float) MODEL_SIZE / origW, (float) MODEL_SIZE / origH);
        int resizedW = Math.round(origW * gain);
        int resizedH = Math.round(origH * gain);
        int padW = MODEL_SIZE - resizedW;
        int padH = MODEL_SIZE - resizedH;
        int left = padW / 2;
        int top = padH / 2;

        BufferedImage letterbox = new BufferedImage(MODEL_SIZE, MODEL_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = letterbox.createGraphics();
        g.setColor(new java.awt.Color(PAD_VALUE, PAD_VALUE, PAD_VALUE));
        g.fillRect(0, 0, MODEL_SIZE, MODEL_SIZE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, left, top, left + resizedW, top + resizedH, 0, 0, origW, origH, null);
        g.dispose();

        // 归一化到 [0,1] RGB NCHW
        float[] chw = new float[3 * MODEL_SIZE * MODEL_SIZE];
        for (int y = 0; y < MODEL_SIZE; y++) {
            for (int x = 0; x < MODEL_SIZE; x++) {
                int rgb = letterbox.getRGB(x, y);
                int idx = y * MODEL_SIZE + x;
                chw[idx] = ((rgb >> 16) & 0xFF) / 255f;
                chw[MODEL_SIZE * MODEL_SIZE + idx] = ((rgb >> 8) & 0xFF) / 255f;
                chw[2 * MODEL_SIZE * MODEL_SIZE + idx] = (rgb & 0xFF) / 255f;
            }
        }

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(chw),
                new long[]{1, 3, MODEL_SIZE, MODEL_SIZE})) {
            var inputs = java.util.Map.of("images", inputTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] depthMap = (float[][][]) result.get(0).getValue();
                int h = depthMap[0].length;
                int w = depthMap[0][0].length;

                // YOLO26-Depth 输出为 metric depth（值越大=越远），先转为 disparity（值越大=越近），
                // 再线性归一化到灰度：近处亮、远处暗，与 depth-anything 滤镜展示方向一致
                float[] disparity = new float[h * w];
                float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        float v = depthMap[0][y][x];
                        float d = v > 0.01f ? 1f / v : 0f;
                        disparity[y * w + x] = d;
                        if (d < min) min = d;
                        if (d > max) max = d;
                    }
                }
                float range = max - min;
                if (range <= 0) range = 1f;

                BufferedImage depthImg = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        float norm = (disparity[y * w + x] - min) / range;
                        int gray = (int) (norm * 255f);
                        gray = Math.max(0, Math.min(255, gray));
                        int rgb = (gray << 16) | (gray << 8) | gray;
                        depthImg.setRGB(x, y, rgb);
                    }
                }

                // 裁剪 letterbox padding 并缩放到原图尺寸
                int cropTop = Math.round(top * w / (float) MODEL_SIZE);
                int cropLeft = Math.round(left * h / (float) MODEL_SIZE);
                int cropW = Math.round(resizedW * w / (float) MODEL_SIZE);
                int cropH = Math.round(resizedH * h / (float) MODEL_SIZE);
                BufferedImage crop = depthImg.getSubimage(cropLeft, cropTop, Math.max(1, cropW), Math.max(1, cropH));

                BufferedImage scaled = new BufferedImage(origW, origH, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(crop, 0, 0, origW, origH, null);
                g2.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(scaled, "PNG", baos);
                return baos.toByteArray();
            }
        }
    }

    @Override
    public void close() {
        if (session != null) {
            try { session.close(); } catch (Exception ignore) { }
        }
        ortEnv = null;
        prepared = false;
    }
}
