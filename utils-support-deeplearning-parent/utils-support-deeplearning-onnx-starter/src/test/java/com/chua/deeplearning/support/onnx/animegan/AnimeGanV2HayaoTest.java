package com.chua.deeplearning.support.onnx.animegan;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * AnimeGANv2-Hayao 真实 ONNX 推理测试（离线 jar 打包模型）。
 *
 * <p>模型：utils-support-models-onnx-animegan-hayao jar 内
 * {@code vision/style_transfer/animegan2/hayao.onnx}（8.25MB，FP32）。
 * 输入 [1,3,512,512] RGB 归一化 [-1,1]，输出 [1,3,512,512]（0-255 范围）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AnimeGanV2HayaoTest {

    /**
     * 模型 ID（OnnxModelRegistrar 注册名）
     */
    private static final String MODEL_ID = "anime-gan-v2-hayao";

    /**
     * 输入边长（AnimeGANv2 固定 512）
     */
    private static final int INPUT_SIZE = 512;

    public static void main(String[] args) {
        AnimeGanV2HayaoTest runner = new AnimeGanV2HayaoTest();
        System.exit(runner.runTest() ? 0 : 1);
    }

    /**
     * 跑全流程。
     *
     * @return 是否通过
     */
    public boolean runTest() {
        try (OrtEnvironment env = OrtEnvironment.getEnvironment()) {
            // 注册模型（SPI 自动发现模型注册器）
            new OnnxModelRegistrar().register(null);
            ModelRegistry.discoverAll();
            Path modelPath = ModelRegistry.resolveModelPath(MODEL_ID);
            if (modelPath == null || !Files.isRegularFile(modelPath)) {
                log.error("[FAIL] 模型路径无效: modelId={} path={}", MODEL_ID, modelPath);
                return false;
            }
            log.info("[INFO] 模型路径: {} ({}MB)", modelPath, Math.round(modelPath.toFile().length() / 1048576.0));

            long start = System.currentTimeMillis();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            try (OrtSession session = env.createSession(modelPath.toString(), opts)) {
                log.info("[INFO] ONNX loaded ({}ms), 输入节点: {}", System.currentTimeMillis() - start,
                        session.getInputInfo().keySet());

                // 合成 512x512 测试图
                float[] rgb = toRgbFloat(buildSyntheticImage());
                long[] shape = new long[]{1, 3, INPUT_SIZE, INPUT_SIZE};
                FloatBuffer buf = FloatBuffer.wrap(rgb);

                long runStart = System.currentTimeMillis();
                try (OnnxTensor tensor = OnnxTensor.createTensor(env, buf, shape);
                     OrtSession.Result result = session.run(Map.of("input", tensor))) {
                    long elapsed = System.currentTimeMillis() - runStart;
                    Object value = result.get(0).getValue();
                    String type = value.getClass().getSimpleName();

                    // 校验输出
                    int[] dims = outputDims(value);
                    boolean ok = value != null && dims.length == 4
                            && dims[0] == 1 && dims[2] == INPUT_SIZE && dims[3] == INPUT_SIZE;
                    log.info("[PASS={}] 推理 {}ms 输出类型={} 形状=" + java.util.Arrays.toString(dims),
                            ok, elapsed, type);
                    return ok;
                }
            } catch (Exception e) {
                log.error("[FAIL] 推理异常: {}", e.getMessage(), e);
                return false;
            }
        } catch (Exception e) {
            log.error("[FAIL] 加载异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解析输出维度。
     */
    private int[] outputDims(Object value) {
        if (value instanceof float[][][][] f4) {
            return new int[]{f4.length, f4[0].length, f4[0][0].length, f4[0][0][0].length};
        }
        if (value instanceof float[][][] f3) {
            return new int[]{f3.length, f3[0].length, f3[0][0].length};
        }
        return new int[0];
    }

    /**
     * 合成 512x512 测试图：左上蓝、右下红的渐变 + 中心白圆。
     */
    private BufferedImage buildSyntheticImage() {
        BufferedImage img = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    g.setColor(new Color(x * 255 / INPUT_SIZE, y * 255 / INPUT_SIZE, 128));
                    g.fillRect(x, y, 1, 1);
                }
            }
            g.setColor(Color.WHITE);
            int cx = INPUT_SIZE / 2;
            int r = INPUT_SIZE / 4;
            g.fillOval(cx - r, cx - r, r * 2, r * 2);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * BufferedImage → CHW float[]（RGB 归一化 [-1,1]，AnimeGAN 输入范围）。
     */
    private float[] toRgbFloat(BufferedImage img) {
        float[] out = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int idx = y * w + x;
                out[idx] = (((rgb >>> 16) & 0xFF) / 255.0f) * 2 - 1;
                out[idx + w * h] = (((rgb >>> 8) & 0xFF) / 255.0f) * 2 - 1;
                out[idx + w * h * 2] = ((rgb & 0xFF) / 255.0f) * 2 - 1;
            }
        }
        return out;
    }
}