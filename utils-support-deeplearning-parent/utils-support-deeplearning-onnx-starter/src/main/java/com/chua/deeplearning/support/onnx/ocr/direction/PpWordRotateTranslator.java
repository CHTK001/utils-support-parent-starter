package com.chua.deeplearning.support.onnx.ocr.direction;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PP-OCR 文本方向分类（ORT 原生 + OpenCV）。
 *
 * <p>检测文本方向（0° / 180°），OCR 管线前置。模型
 * {@code ocr/direction/ppocr_cls/model.onnx} 由 jar
 * {@code utils-support-models-onnx-ppocr-cls} 提供。输入 {@code x [1,3,48,192]}
 * （OpenCV resize 48×192、归一化 (v/255-0.5)/0.5），输出 {@code fetch_name_0 [1,2]}
 * softmax（index 0=0°、index 1=180°）。替代 DJL 版（NDImageUtils 不兼容）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PpWordRotateTranslator implements ITranslator<byte[], DirectionInfo> {

    /** 缩放宽度 */
    /** Resize_width */
    private static final int RESIZE_WIDTH = 192;
    /** 缩放高度 */
    /** Resize_height */
    private static final int RESIZE_HEIGHT = 48;
    /** 类别名称列表 */
    /** Classes */
    private static final List<String> CLASSES = List.of("0", "180");

    /** 资源基础路径 */
    /** Resource_base */
    private static final String RESOURCE_BASE = "ocr/direction/ppocr_cls/";
    /** 模型文件路径 */
    /** Model_file */
    private static final String MODEL_FILE = "model.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("ppocr-cls-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("cls");
        Files.createDirectories(modelDir);
        NativeLoader.of("pp-word-rotate")
                .from(PpWordRotateTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("方向分类模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[PP-OCR-cls] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return "pp-word-rotate";
    }

    @Override
    public DirectionInfo translate(byte[] imageData) {
        try {
            prepare();
            return classify(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[pp-word-rotate] 方向检测失败: " + e.getMessage(), e);
        }
    }

    private DirectionInfo classify(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                int srcW = src.cols();
                int srcH = src.rows();
                // resize 到 48 高，宽按比例（最多 192），pad 右侧
                float ratio = (float) srcW / srcH;
                int resizedW = (int) Math.ceil(RESIZE_HEIGHT * ratio);
                resizedW = Math.max(1, Math.min(resizedW, RESIZE_WIDTH));

                Mat resized = ImageUtils.resize(src, resizedW, RESIZE_HEIGHT, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * RESIZE_HEIGHT * RESIZE_WIDTH];
                for (int y = 0; y < RESIZE_HEIGHT; y++) {
                    for (int x = 0; x < resizedW; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * RESIZE_WIDTH + x;
                        // RGB 顺序 + 归一化 (v/255 - 0.5) / 0.5
                        pixels[idx] = (((float) bgr[2] / 255.0f) - 0.5f) / 0.5f;
                        pixels[idx + RESIZE_HEIGHT * RESIZE_WIDTH] = (((float) bgr[1] / 255.0f) - 0.5f) / 0.5f;
                        pixels[idx + 2 * RESIZE_HEIGHT * RESIZE_WIDTH] = (((float) bgr[0] / 255.0f) - 0.5f) / 0.5f;
                    }
                }
                resized.release();

                long[] shape = {1, 3, RESIZE_HEIGHT, RESIZE_WIDTH};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("x", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object out = result.get(0).getValue();
                        float[][] probs;
                        if (out instanceof float[][]) {
                            probs = (float[][]) out;
                        } else if (out instanceof float[][][][]) {
                            float[][][][] arr = (float[][][][]) out;
                            probs = arr[0][0];
                        } else {
                            throw new IllegalArgumentException("方向分类输出格式不识别: " + out.getClass());
                        }
                        float[] p = probs[0];
                        int maxIndex = p.length > 1 && p[1] > p[0] ? 1 : 0;
                        String clsName = maxIndex < CLASSES.size() ? CLASSES.get(maxIndex) : String.valueOf(maxIndex);
                        return new DirectionInfo(clsName, p[maxIndex]);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[pp-word-rotate] 方向检测失败: " + e.getMessage(), e);
        }
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
