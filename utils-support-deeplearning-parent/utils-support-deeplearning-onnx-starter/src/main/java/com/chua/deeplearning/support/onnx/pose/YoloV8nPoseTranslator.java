package com.chua.deeplearning.support.onnx.pose;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.pose.PoseKeypoint;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * YOLOv8n-pose 姿态估计 — 检测人体 17 个关键点（骨骼点）。
 *
 * <p>输入 {@code [1,3,640,640]}，输出 {@code [1,56,8400]}。
 * 56 = 4(bbox) + 1(cls) + 51(17关键点×3)，8400 个预测。
 * 模型来源：modelscope {@code Xenova/yolov8n-pose} 的
 * {@code model_fp16.onnx}（约 6.5MB，fp16）。OpenCV 预处理，ORT 原生推理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class YoloV8nPoseTranslator implements ITranslator<byte[], List<PoseKeypoint>> {

    private static final int INPUT_SIZE = 640;
    private static final int NUM_PREDS = 8400;
    private static final int NUM_KEYPOINTS = 17;
    private static final float CONF_THRESHOLD = 0.3f;
    private static final float NMS_THRESHOLD = 0.45f;

    private static final String RESOURCE_BASE = "vision/pose/yolov8n/onnx/";
    private static final String MODEL_FILE = "model_quantized.onnx";

    private static final String[] KEYPOINT_NAMES = {
            "nose", "left_eye", "right_eye", "left_ear", "right_ear",
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_hip", "right_hip",
            "left_knee", "right_knee", "left_ankle", "right_ankle"
    };

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private int srcWidth;
    private int srcHeight;

    public static class PoseResult {
        public float[] bbox;
        public float score;
        public float[][] keypoints; // [17][2] (x, y)
        public float[] keypointScores; // [17]
    }

    private synchronized void prepare() throws Exception {
        if (session != null) return;
        Path tmpDir = Files.createTempDirectory("yolov8n-pose-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("yolov8n-pose");
        Files.createDirectories(modelDir);
        NativeLoader.of("yolov8n-pose")
                .from(YoloV8nPoseTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true).load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) throw new Exception("模型缺失: " + modelPath);
        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath.toString(), opts);
            log.info("[YOLOv8n-pose] ONNX loaded: {}", modelPath.getFileName());
        } catch (Exception e) {
            throw new Exception("Failed to create ORT session: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "yolov8n-pose";
    }

    @Override
    public List<PoseKeypoint> translate(byte[] imageData) {
        List<PoseResult> results = detectBytes(imageData);
        if (results.isEmpty()) {
            return List.of();
        }
        PoseResult best = results.get(0);
        List<PoseKeypoint> keypoints = new ArrayList<>();
        for (int i = 0; i < NUM_KEYPOINTS; i++) {
            String name = i < KEYPOINT_NAMES.length ? KEYPOINT_NAMES[i] : "kp_" + i;
            keypoints.add(new PoseKeypoint(name,
                    best.keypoints[i][0], best.keypoints[i][1], best.keypointScores[i]));
        }
        return keypoints;
    }

    /**
     * 检测图像中的姿态关键点（byte[] 输入，OpenCV 预处理）。
     *
     * @param imageData 图像字节
     * @return 姿态结果列表
     */
    public List<PoseResult> detectBytes(byte[] imageData) {
        try {
            prepare();
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                srcWidth = src.cols();
                srcHeight = src.rows();
                Mat resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, Imgproc.INTER_LINEAR);

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
                    inputs.put("images", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][][] output = (float[][][]) result.get(0).getValue();
                        return decode(output[0]);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[yolov8n-pose] 姿态估计失败: " + e.getMessage(), e);
        }
    }

    private List<PoseResult> decode(float[][] data) {
        float scaleX = (float) srcWidth / INPUT_SIZE;
        float scaleY = (float) srcHeight / INPUT_SIZE;
        List<PoseResult> candidates = new ArrayList<>();

        for (int i = 0; i < NUM_PREDS; i++) {
            float cx = data[0][i];
            float cy = data[1][i];
            float w = data[2][i];
            float h = data[3][i];
            float cls = data[4][i];
            if (cls < CONF_THRESHOLD) continue;

            float x1 = (cx - w / 2) * scaleX;
            float y1 = (cy - h / 2) * scaleY;
            float x2 = (cx + w / 2) * scaleX;
            float y2 = (cy + h / 2) * scaleY;

            float[][] kps = new float[NUM_KEYPOINTS][2];
            float[] kpScores = new float[NUM_KEYPOINTS];
            for (int k = 0; k < NUM_KEYPOINTS; k++) {
                kps[k][0] = data[5 + k * 3][i] * scaleX;
                kps[k][1] = data[5 + k * 3 + 1][i] * scaleY;
                kpScores[k] = data[5 + k * 3 + 2][i];
            }

            PoseResult pr = new PoseResult();
            pr.bbox = new float[]{x1, y1, x2, y2};
            pr.score = cls;
            pr.keypoints = kps;
            pr.keypointScores = kpScores;
            candidates.add(pr);
        }

        candidates.sort((a, b) -> Float.compare(b.score, a.score));
        boolean[] suppressed = new boolean[candidates.size()];
        List<PoseResult> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (suppressed[i]) continue;
            result.add(candidates.get(i));
            for (int j = i + 1; j < candidates.size(); j++) {
                if (suppressed[j]) continue;
                if (iou(candidates.get(i).bbox, candidates.get(j).bbox) > NMS_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float iou(float[] a, float[] b) {
        float x1 = Math.max(a[0], b[0]);
        float y1 = Math.max(a[1], b[1]);
        float x2 = Math.min(a[2], b[2]);
        float y2 = Math.min(a[3], b[3]);
        float inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float areaA = (a[2] - a[0]) * (a[3] - a[1]);
        float areaB = (b[2] - b[0]) * (b[3] - b[1]);
        return inter / (areaA + areaB - inter + 1e-9f);
    }

    public synchronized void close() {
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        session = null; ortEnv = null;
    }
}