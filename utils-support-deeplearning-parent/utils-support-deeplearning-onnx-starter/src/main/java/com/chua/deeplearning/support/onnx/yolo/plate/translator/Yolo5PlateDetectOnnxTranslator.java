package com.chua.deeplearning.support.onnx.yolo.plate.translator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YOLOv5 车牌检测（CVHub520/yolov5_car_plate，ORT 原生 + OpenCV）。
 *
 * <p>模型 {@code vision/detection/yolov5_plate/yolov5_plate_detect.onnx} 由 jar
 * {@code utils-support-models-onnx-yolov5-plate} 提供。输入 {@code [1,3,640,640]}，
 * 输出 {@code [1,25200,15]}：每行 = x_center, y_center, w, h, obj_conf,
 * 4 个角点关键点 (8)，class0_conf(single), class1_conf(double)。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Yolo5PlateDetectOnnxTranslator implements ITranslator<byte[], List<PredictRectangle>> {

    /**
     * 输入边长。
     */
    private static final int INPUT_SIZE = 640;

    /**
     * 置信度阈值。
     */
    private static final float CONF_THRESHOLD = 0.3f;

    /**
     * IOU 阈值。
     */
    private static final float IOU_THRESHOLD = 0.5f;

    /**
     * 锚点数量（25200 = 3 scale × 3 anchor × 各格点数）。
     */
    private static final int NUM_PREDICTIONS = 25200;

    /**
     * jar 内模型资源目录。
     */
    private static final String RESOURCE_BASE = "vision/detection/yolov5_plate/";

    /**
     * 模型文件名。
     */
    private static final String MODEL_FILE = "yolov5_plate_detect.onnx";

    /**
     * ONNX 运行时环境。
     */
    private OrtEnvironment ortEnv;

    /**
     * 检测会话。
     */
    private OrtSession session;

    /**
     * 源图像宽度。
     */
    private int srcWidth;

    /**
     * 源图像高度。
     */
    private int srcHeight;

    /**
     * 是否已加载。
     */
    private volatile boolean loaded;

    /**
     * Prepare。
     */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path cache = Path.of(System.getProperty("java.io.tmpdir")).resolve("chua-models").resolve("yolov5-plate-det");
        if (!Files.isRegularFile(cache.resolve(MODEL_FILE))) {
            Files.createDirectories(cache);
            NativeLoader.of("yolov5-plate-detect-onnx")
                    .from(Yolo5PlateDetectOnnxTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(cache)
                    .glob("*.onnx")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        Path modelPath = cache.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("车牌检测模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        this.loaded = true;
        System.out.println("[Yolo5PlateDet] ONNX loaded: " + modelPath.getFileName());
    }

    @Override
    public String name() {
        return "yolov5-plate-detect";
    }

    @Override
    public synchronized List<PredictRectangle> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[yolov5-plate-detect] 车牌检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 车牌检测：letterbox → 推理 → 解码 + NMS。
     *
     * @param imageData 原图
     * @return 检测结果（含角点 keypoints）
     */
    private List<PredictRectangle> detect(byte[] imageData) throws Exception {
        ImageUtils.load();
        Mat src = ImageUtils.decode(imageData);
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("无法解码图像");
        }
        try {
            srcWidth = src.cols();
            srcHeight = src.rows();
            // letterbox 缩放到 640×640（保持纵横比，灰色填充）
            float scale = Math.min(INPUT_SIZE / (float) srcWidth, INPUT_SIZE / (float) srcHeight);
            int newW = Math.round(srcWidth * scale);
            int newH = Math.round(srcHeight * scale);
            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(newW, newH), 0, 0, Imgproc.INTER_LINEAR);
            Mat padded = new Mat(new Size(INPUT_SIZE, INPUT_SIZE), src.type(), new org.opencv.core.Scalar(114, 114, 114));
            int padX = (INPUT_SIZE - newW) / 2;
            int padY = (INPUT_SIZE - newH) / 2;
            resized.copyTo(padded.submat(padY, padY + newH, padX, padX + newW));
            resized.release();

            float[] pixels = new float[3 * INPUT_SIZE * INPUT_SIZE];
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    double[] bgr = padded.get(y, x);
                    int idx = y * INPUT_SIZE + x;
                    // RGB 顺序 /255，CHW
                    pixels[idx] = (float) bgr[2] / 255.0f;
                    pixels[idx + INPUT_SIZE * INPUT_SIZE] = (float) bgr[1] / 255.0f;
                    pixels[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (float) bgr[0] / 255.0f;
                }
            }
            padded.release();

            long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
            try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(session.getInputInfo().keySet().iterator().next(), tensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    Object out = result.get(0).getValue();
                    float[][] dets;
                    if (out instanceof float[][][][] arr4) {
                        dets = arr4[0][0];
                    } else if (out instanceof float[][][] arr3) {
                        dets = arr3[0];
                    } else {
                        throw new IllegalArgumentException("输出格式不识别: " + out.getClass());
                    }
                    return decode(dets, scale, padX, padY);
                }
            }
        } finally {
            src.release();
        }
    }

    /**
     * 解码 + NMS：过滤置信度 → 类分融合 → NMS → 还原原图坐标 → 输出角点。
     *
     * @param dets  原始检测 [25200][15]
     * @param scale letterbox 缩放比例
     * @param padX  letterbox 水平填充
     * @param padY  letterbox 垂直填充
     * @return 检测结果
     */
    private List<PredictRectangle> decode(float[][] dets, float scale, int padX, int padY) {
        // 过滤 obj_conf > 阈值
        List<float[]> candidates = new ArrayList<>();
        for (float[] d : dets) {
            if (d[4] < CONF_THRESHOLD) {
                continue;
            }
            float cls0 = d[13] * d[4];
            float cls1 = d[14] * d[4];
            float cls = Math.max(cls0, cls1);
            if (cls < CONF_THRESHOLD) {
                continue;
            }
            candidates.add(d);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 按置信度降序
        candidates.sort((a, b) -> Float.compare(Math.max(b[13], b[14]) * b[4], Math.max(a[13], a[14]) * a[4]));
        // 转原图坐标 + NMS
        List<float[]> boxes = new ArrayList<>();
        List<float[]> kept = new ArrayList<>();
        List<Integer> classes = new ArrayList<>();
        for (float[] d : candidates) {
            float cx = (d[0] - padX) / scale;
            float cy = (d[1] - padY) / scale;
            float w = d[2] / scale;
            float h = d[3] / scale;
            float x1 = Math.max(0, cx - w / 2);
            float y1 = Math.max(0, cy - h / 2);
            float x2 = Math.min(srcWidth, cx + w / 2);
            float y2 = Math.min(srcHeight, cy + h / 2);
            float[] box = {x1, y1, x2, y2};
            if (boxes.isEmpty() || nmsOk(box, boxes, IOU_THRESHOLD)) {
                boxes.add(box);
                kept.add(d);
                classes.add(d[13] >= d[14] ? 0 : 1);
            }
        }
        List<PredictRectangle> result = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            float[] d = kept.get(i);
            float[] box = boxes.get(i);
            float clsConf = Math.max(d[13], d[14]) * d[4];
            String clsName = classes.get(i) == 0 ? "single" : "double";
            // 4 角点（相对检测框中心的偏移，还原原图坐标）
            List<float[]> keypoints = new ArrayList<>(4);
            for (int k = 0; k < 4; k++) {
                float kx = (d[0] + d[5 + k * 2] - padX) / scale;
                float ky = (d[1] + d[6 + k * 2] - padY) / scale;
                keypoints.add(new float[]{kx, ky});
            }
            float w = box[2] - box[0];
            float h = box[3] - box[1];
            result.add(new PredictRectangle(box[0], box[1], w, h, clsConf, classes.get(i), clsName, keypoints));
        }
        return result;
    }

    /**
     * NMS 判断新框是否与已选框 IoU 低于阈值。
     */
    private boolean nmsOk(float[] box, List<float[]> boxes, float iouThresh) {
        for (float[] b : boxes) {
            float x1 = Math.max(box[0], b[0]);
            float y1 = Math.max(box[1], b[1]);
            float x2 = Math.min(box[2], b[2]);
            float y2 = Math.min(box[3], b[3]);
            float inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
            float area1 = (box[2] - box[0]) * (box[3] - box[1]);
            float area2 = (b[2] - b[0]) * (b[3] - b[1]);
            float iou = inter / Math.max(1e-6f, area1 + area2 - inter);
            if (iou > iouThresh) {
                return false;
            }
        }
        return true;
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
        loaded = false;
    }
}
