package com.chua.deeplearning.support.onnx.depth;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.deeplearning.support.image.DepthResult;
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
   * YOLO26-深度 单目深度估计翻译器（纯 ONNX Runtime 实现）。
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

    private OrtEnvironment ortEnv; // ortenv
    private OrtSession session; // 会话
    private String modelPath; // 模型路径
    private volatile boolean prepared = false; // prepared

    /**
      * 设置模型文件路径（仅供 模型registry 在 SPI 实例化后注入使用）。
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
        return estimateDepth(imageBytes).depthImage();
    }

    /**
     * 估计深度并返回完整结果（深度图 + 距离矩阵 + 统计）。
     *
     * @param imageBytes 输入图像字节数组
     * @return 深度估计结果（距离单位：米）
     */
    public DepthResult estimateDepth(byte[] imageBytes) {
        try {
            ensurePrepared();
            return depthResult(imageBytes);
        } catch (Exception e) {
            throw new RuntimeException("[yolo26-depth] 推理失败: " + e.getMessage(), e);
        }
    }

    private synchronized void ensurePrepared() throws Exception {
        if (prepared) {
            return;
        }
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

    private DepthResult depthResult(byte[] imageBytes) throws Exception {
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
                float[][][][] depthMap = (float[][][][]) result.get(0).getValue();
                int h = depthMap[0][0].length;
                int w = depthMap[0][0][0].length;
                float[][] depth = depthMap[0][0];

                // 裁剪 letterbox padding 得到原图比例的距离矩阵，并缩放到原图尺寸（单位：米，越远越大）
                int cropTop = Math.round(top * h / (float) MODEL_SIZE);
                int cropLeft = Math.round(left * w / (float) MODEL_SIZE);
                int cropW = Math.round(resizedW * w / (float) MODEL_SIZE);
                int cropH = Math.round(resizedH * h / (float) MODEL_SIZE);
                float[][] meters = new float[origH][origW];
                float sum = 0f;
                int cnt = 0;
                float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
                float center = 0f;
                for (int y = 0; y < origH; y++) {
                    // 映射回裁剪区域坐标（线性插值采样）
                    float srcY = cropTop + (y + 0.5f) * cropH / origH;
                    for (int x = 0; x < origW; x++) {
                        float srcX = cropLeft + (x + 0.5f) * cropW / origW;
                        float v = sampleDepth(depth, h, w, srcY, srcX);
                        meters[y][x] = v;
                        if (v < min) {
                            min = v;
                        }
                        if (v > max) {
                            max = v;
                        }
                        sum += v;
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    float centerV = meters[origH / 2][origW / 2];
                    center = centerV;
                } else {
                    center = 0f;
                }
                float mean = cnt > 0 ? sum / cnt : 0f;
                if (min == Float.MAX_VALUE) {
                    min = 0f;
                }
                if (max == Float.MIN_VALUE) {
                    max = 0f;
                }

                // 由距离矩阵生成深度图：转为 disparity（值越大=越近），线性归一化灰度（近处亮、远处暗）
                float[] disparity = new float[origH * origW];
                float dMin = Float.MAX_VALUE, dMax = Float.MIN_VALUE;
                for (int y = 0; y < origH; y++) {
                    for (int x = 0; x < origW; x++) {
                        float v = meters[y][x];
                        float d = v > 0.01f ? 1f / v : 0f;
                        disparity[y * origW + x] = d;
                        if (d < dMin) {
                            dMin = d;
                        }
                        if (d > dMax) {
                            dMax = d;
                        }
                    }
                }
                float dRange = dMax - dMin;
                if (dRange <= 0) {
                    dRange = 1f;
                }

                BufferedImage depthImg = new BufferedImage(origW, origH, BufferedImage.TYPE_3BYTE_BGR);
                for (int y = 0; y < origH; y++) {
                    for (int x = 0; x < origW; x++) {
                        float norm = (disparity[y * origW + x] - dMin) / dRange;
                        int gray = (int) (norm * 255f);
                        gray = Math.max(0, Math.min(255, gray));
                        int rgb = (gray << 16) | (gray << 8) | gray;
                        depthImg.setRGB(x, y, rgb);
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(depthImg, "PNG", baos);
                return new DepthResult(baos.toByteArray(), meters, min, max, center, mean);
            }
        }
    }

    /**
     * 双线性采样距离矩阵中的像素值。
     * @param depth 深度
     * @param h h
     * @param w w
     * @param y y
     * @param x x
     * @return 样本深度的结果
     */
    private static float sampleDepth(float[][] depth, int h, int w, float y, float x) {
        int y0 = Math.min(h - 1, Math.max(0, (int) Math.floor(y)));
        int x0 = Math.min(w - 1, Math.max(0, (int) Math.floor(x)));
        int y1 = Math.min(h - 1, y0 + 1);
        int x1 = Math.min(w - 1, x0 + 1);
        float fy = y - y0;
        float fx = x - x0;
        float v00 = depth[y0][x0], v01 = depth[y0][x1];
        float v10 = depth[y1][x0], v11 = depth[y1][x1];
        return (v00 * (1 - fx) + v01 * fx) * (1 - fy)
                + (v10 * (1 - fx) + v11 * fx) * fy;
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
