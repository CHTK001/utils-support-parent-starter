package com.chua.deeplearning.support.onnx.depth;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Depth-Anything V2 ONNX（ORT 直连，规避 DJL onnxruntime 对 Resize op 的兼容问题）。
 *
 * <p>输入 byte[] → 518x518 ImageNet normalize → ORT 推理 → 深度图归一化 → 缩放到原图尺寸 → byte[]</p>
 * @author CH
 */
@Slf4j
public class DepthAnythingOrtTranslator implements ITranslator<byte[], byte[]>, AutoCloseable {

    private static final int MODEL_SIZE = 518;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final String modelId;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private volatile boolean initialized;

    public DepthAnythingOrtTranslator() {
        this("depth-anything");
    }

    public DepthAnythingOrtTranslator(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public String name() {
        return modelId;
    }

    @Override
    public byte[] translate(byte[] input) {
        try {
            return depth(input);
        } catch (Exception e) {
            throw new RuntimeException("[" + modelId + "] 推理失败: " + e.getMessage(), e);
        }
    }

    private synchronized void prepare() throws Exception {
        if (initialized) return;
        Path modelPath = ModelRegistry.resolveModelPath(modelId);
        if (modelPath == null || !Files.exists(modelPath)) {
            throw new IllegalStateException("模型文件不存在: " + modelPath);
        }
        ortEnv = OrtEnvironment.getEnvironment();
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        opts.setOptimizationLevel(SessionOptions.OptLevel.NO_OPT);
        session = ortEnv.createSession(modelPath.toString(), opts);
        initialized = true;
        log.info("[DepthAnything] ORT ready, model={}", modelPath);
    }

    public byte[] depth(byte[] imageBytes) throws Exception {
        prepare();

        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        int origW = src.getWidth();
        int origH = src.getHeight();

        // 预处理：resize + normalize
        BufferedImage resized = new BufferedImage(MODEL_SIZE, MODEL_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g = resized.createGraphics();
        g.drawImage(src, 0, 0, MODEL_SIZE, MODEL_SIZE, null);
        g.dispose();

        float[] chw = new float[3 * MODEL_SIZE * MODEL_SIZE];
        for (int y = 0; y < MODEL_SIZE; y++) {
            for (int x = 0; x < MODEL_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float gv = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                int idx = y * MODEL_SIZE + x;
                chw[idx] = (r - MEAN[0]) / STD[0];
                chw[MODEL_SIZE * MODEL_SIZE + idx] = (gv - MEAN[1]) / STD[1];
                chw[2 * MODEL_SIZE * MODEL_SIZE + idx] = (b - MEAN[2]) / STD[2];
            }
        }

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(chw),
                new long[]{1, 3, MODEL_SIZE, MODEL_SIZE})) {
            var inputs = java.util.Map.of("pixel_values", inputTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] depth = (float[][][]) result.get(0).getValue();
                int h = depth[0].length;
                int w = depth[0][0].length;

                // 归一化
                float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (depth[0][y][x] < min) min = depth[0][y][x];
                        if (depth[0][y][x] > max) max = depth[0][y][x];
                    }
                }
                float range = max - min;
                if (range <= 0) range = 1f;

                BufferedImage depthImg = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int gray = (int) ((depth[0][y][x] - min) / range * 255f);
                        gray = Math.max(0, Math.min(255, gray));
                        int rgb = (gray << 16) | (gray << 8) | gray;
                        depthImg.setRGB(x, y, rgb);
                    }
                }

                // 缩放到原图尺寸
                BufferedImage scaled = new BufferedImage(origW, origH, BufferedImage.TYPE_3BYTE_BGR);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.drawImage(depthImg, 0, 0, origW, origH, null);
                g2.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(scaled, "PNG", baos);
                return baos.toByteArray();
            }
        }
    }

    @Override
    public void close() {
        if (session != null) { try { session.close(); } catch (Exception ignore) {} }
        ortEnv = null;
        initialized = false;
    }
}