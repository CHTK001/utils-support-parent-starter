package com.chua.deeplearning.support.onnx.classification;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片矫正检测（CenterNet，ORT 原生 + OpenCV）。
 *
 * <p>检测卡片四角点（如身份证、银行卡），用于图像矫正。
 * 模型 {@code cv/card_correction/card_detection.onnx} 由 jar
 * {@code utils-support-models-onnx-card-correction} 提供。输入 {@code [1,3,768,768]}，
 * 输出：{@code hm[1,1,192,192]} 热图、{@code wh[1,8,192,192]} 角点宽高、
 * {@code reg[1,2,192,192]} 中心偏移、{@code cls[1,4,192,192]} 角点类别。
 * stride=4，输出 4 个角点坐标。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CardCorrectionTranslator implements ITranslator<byte[], List<DetectionInfo>> {

    private static final int INPUT_SIZE = 768;
    private static final int HEAT_SIZE = 192;
    private static final int NUM_CORNERS = 4;
    private static final int STRIDE = 4;
    private static final float CONF_THRESHOLD = 0.3f;

    private static final String RESOURCE_BASE = "cv/card_correction/";
    private static final String MODEL_FILE = "card_detection.onnx";

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private int srcWidth;
    private int srcHeight;

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("card-correction-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("card-correction");
        Files.createDirectories(modelDir);
        NativeLoader.of("card-correction-detector")
                .from(CardCorrectionTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("卡片矫正模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[CardCorrection] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return "card-correction-detector";
    }

    @Override
    public List<DetectionInfo> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[card-correction-detector] 卡片矫正检测失败: " + e.getMessage(), e);
        }
    }

    private List<DetectionInfo> detect(byte[] imageData) {
        try {
            nu.pattern.OpenCV.loadLocally();
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                srcWidth = src.cols();
                srcHeight = src.rows();
                Mat resized = new Mat();
                Imgproc.resize(src, resized, new Size(INPUT_SIZE, INPUT_SIZE), 0, 0, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * INPUT_SIZE * INPUT_SIZE];
                for (int y = 0; y < INPUT_SIZE; y++) {
                    for (int x = 0; x < INPUT_SIZE; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * INPUT_SIZE + x;
                        pixels[idx] = (float) bgr[2] / 255.0f;
                        pixels[idx + INPUT_SIZE * INPUT_SIZE] = (float) bgr[1] / 255.0f;
                        pixels[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (float) bgr[0] / 255.0f;
                    }
                }
                resized.release();

                long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("image", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][] hm = toMat2D(result.get("hm").get().getValue());
                        float[][][] wh = toMat3D(result.get("wh").get().getValue());
                        float[][][] reg = toMat3D(result.get("reg").get().getValue());
                        return decode(hm, wh, reg);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[card-correction-detector] 卡片矫正检测失败: " + e.getMessage(), e);
        }
    }

    private List<DetectionInfo> decode(float[][] hm, float[][][] wh, float[][][] reg) {
        float scaleX = (float) srcWidth / HEAT_SIZE;
        float scaleY = (float) srcHeight / HEAT_SIZE;
        List<DetectionInfo> result = new ArrayList<>();

        // hm[192][192]，找 NMS 后的 top-4 峰值
        for (int corner = 0; corner < NUM_CORNERS; corner++) {
            float best = 0;
            int bestY = -1;
            int bestX = -1;
            for (int y = 0; y < HEAT_SIZE; y++) {
                for (int x = 0; x < HEAT_SIZE; x++) {
                    float v = hm[y][x];
                    if (v > best && v >= CONF_THRESHOLD && isLocalMax(hm, x, y)) {
                        best = v;
                        bestY = y;
                        bestX = x;
                    }
                }
            }
            if (bestY < 0) {
                continue;
            }
            float offsetX = reg[0][bestY][bestX];
            float offsetY = reg[1][bestY][bestX];
            float centerX = (bestX + offsetX) * STRIDE;
            float centerY = (bestY + offsetY) * STRIDE;

            float w = wh[corner * 2][bestY][bestX];
            float h = wh[corner * 2 + 1][bestY][bestX];
            float x1 = (centerX - w / 2) * scaleX / STRIDE;
            float y1 = (centerY - h / 2) * scaleY / STRIDE;
            float x2 = (centerX + w / 2) * scaleX / STRIDE;
            float y2 = (centerY + h / 2) * scaleY / STRIDE;

            result.add(new DetectionInfo("corner_" + corner, best,
                    Math.max(0, x1), Math.max(0, y1), Math.max(0, x2 - x1), Math.max(0, y2 - y1)));
        }
        return result;
    }

    private boolean isLocalMax(float[][] hm, int x, int y) {
        float v = hm[y][x];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx >= 0 && nx < HEAT_SIZE && ny >= 0 && ny < HEAT_SIZE) {
                    if (hm[ny][nx] > v) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private float[][] toMat2D(Object value) {
        // 输入 [1, C, H, W]，单通道 C=1 → 返回 [H][W]
        float[][][][] arr4 = (float[][][][]) value;
        float[][][] arr = arr4[0];
        int h = arr[0].length;
        int w = arr[0][0].length;
        float[][] out = new float[h][w];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                out[i][j] = arr[0][i][j];
            }
        }
        return out;
    }

    private float[][][] toMat3D(Object value) {
        float[][][][] arr4 = (float[][][][]) value;
        return arr4[0];
    }

    /**
     * 关闭底层 ONNX Session。
     */
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        session = null;
        ortEnv = null;
    }
}
