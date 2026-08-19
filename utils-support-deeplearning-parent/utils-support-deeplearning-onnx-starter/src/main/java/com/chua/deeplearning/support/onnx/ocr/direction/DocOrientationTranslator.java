package com.chua.deeplearning.support.onnx.ocr.direction;

import com.chua.deeplearning.support.utils.ImageUtils;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文档方向分类（PP-LCNet_x1_0_doc_ori，4 类：0°/90°/180°/270°）。
 *
 * <p>PaddleOCR 文档方向分类模型，整图输入 48×192，输出 4 类 softmax。
 * 替代 pp-word-rotate（仅 0/180）的启发式整图矫正逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DocOrientationTranslator implements ITranslator<byte[], DirectionInfo> {

    /** 缩放宽度 */
    /** Resize_width */
    private static final int RESIZE_WIDTH = 224;
    /** 缩放高度 */
    /** Resize_height */
    private static final int RESIZE_HEIGHT = 224;
    /** 类别名称列表 */
    /** Classes */
    private static final List<String> CLASSES = List.of("0", "90", "180", "270");

    /** 资源基础路径 */
    /** Resource_base */
    private static final String RESOURCE_BASE = "ocr/direction/doc_ori/";
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
        Path modelPath = ModelRegistry.resolveModelPath("doc-orientation");
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("文档方向分类模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[doc-orientation] ONNX loaded: {}", modelPath.getFileName());
    }
    @Override
    public String name() {
        return "doc-orientation";
    }

    @Override
    public DirectionInfo translate(byte[] imageData) {
        try {
            prepare();
            return classify(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[doc-orientation] 方向检测失败: " + e.getMessage(), e);
        }
    }

    private DirectionInfo classify(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = org.opencv.imgcodecs.Imgcodecs.imdecode(
                    new org.opencv.core.MatOfByte(imageData), org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                Mat resized = new Mat();
                Imgproc.resize(src, resized, new Size(RESIZE_WIDTH, RESIZE_HEIGHT), 0, 0, Imgproc.INTER_CUBIC);
                float[] pixels = new float[3 * RESIZE_WIDTH * RESIZE_HEIGHT];
                for (int y = 0; y < RESIZE_HEIGHT; y++) {
                    for (int x = 0; x < RESIZE_WIDTH; x++) {
                        double[] bgr = resized.get(y, x);
                        pixels[y * RESIZE_WIDTH + x] = ((float) bgr[2] / 255.0f - 0.5f) / 0.5f;
                        pixels[RESIZE_WIDTH * RESIZE_HEIGHT + y * RESIZE_WIDTH + x] = ((float) bgr[1] / 255.0f - 0.5f) / 0.5f;
                        pixels[2 * RESIZE_WIDTH * RESIZE_HEIGHT + y * RESIZE_WIDTH + x] = ((float) bgr[0] / 255.0f - 0.5f) / 0.5f;
                    }
                }
                resized.release();
                long[] shape = {1, 3, RESIZE_HEIGHT, RESIZE_WIDTH};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = Map.of("x", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][] output = (float[][]) result.get(0).getValue();
                        float[] scores = output[0];
                        int maxIdx = 0;
                        float maxVal = scores[0];
                        for (int i = 1; i < scores.length; i++) {
                            if (scores[i] > maxVal) {
                                maxVal = scores[i];
                                maxIdx = i;
                            }
                        }
                        String name = CLASSES.get(maxIdx);
                        return new DirectionInfo(name, maxVal);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[doc-orientation] 方向检测失败: " + e.getMessage(), e);
        }
    }
}