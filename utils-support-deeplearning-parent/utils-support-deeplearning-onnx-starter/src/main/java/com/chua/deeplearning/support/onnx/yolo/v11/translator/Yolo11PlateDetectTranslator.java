package com.chua.deeplearning.support.onnx.yolo.v11.translator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;
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
 * YOLOv11 车牌检测（morsetechlab/yolov11-license-plate-detection，ORT 原生 + OpenCV）。
 *
 * <p>模型 {@code vision/detection/yolo11_plate/yolo11_plate_detect.onnx} 内嵌于
 * 本模块 resources。输入 {@code [1,3,640,640]}，输出 {@code [1,5,8400]}：
 * 每列 = cx, cy, w, h, conf（单类 license-plate）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Yolo11PlateDetectTranslator implements ITranslator<byte[], List<PredictRectangle>> {

    /**
     * 输入边长。
     */
    private static final int INPUT_SIZE = 640;

    /**
     * 置信度阈值。
     */
    private static final float CONF_THRESHOLD = 0.25f;

    /**
     * IOU 阈值。
     */
    private static final float IOU_THRESHOLD = 0.45f;

    /**
     * jar 内模型资源目录。
     */
    private static final String RESOURCE_BASE = "vision/detection/yolo11_plate/";

    /**
     * 模型文件名。
     */
    private static final String MODEL_FILE = "yolo11_plate_detect.onnx";

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
     * 加载内嵌模型并创建会话。
     */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path cache = Path.of(System.getProperty("java.io.tmpdir")).resolve("chua-models").resolve("yolo11-plate-det");
        if (!Files.isRegularFile(cache.resolve(MODEL_FILE))) {
            Files.createDirectories(cache);
            NativeLoader.of("yolo11-plate-detect-onnx")
                    .from(Yolo11PlateDetectTranslator.class.getClassLoader())
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
        System.out.println("[Yolo11Plate] ONNX loaded: " + modelPath.getFileName());
    }

    @Override
    public String name() {
        return "yolo11-plate-detect";
    }

    @Override
    public synchronized List<PredictRectangle> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[yolo11-plate-detect] 车牌检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 车牌检测：letterbox → 推理 → 解码 + NMS。
     *
     * @param imageData 原图
     * @return 检测结果
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
                    return decode(out, scale, padX, padY);
                }
            }
        } finally {
            src.release();
        }
    }

    /**
     * 解码：输出 {@code [5,8400]}（cx,cy,w,h,conf），还原原图坐标 + NMS。
     */
    private List<PredictRectangle> decode(Object rawOutput, float scale, int padX, int padY) {
        float[][] raw;
        if (rawOutput instanceof float[][][][] arr4) {
            raw = arr4[0][0];
        } else if (rawOutput instanceof float[][][] arr3) {
            raw = arr3[0];
        } else {
            throw new IllegalArgumentException("输出格式不识别: " + rawOutput.getClass());
        }
        int rows = raw.length;
        int cols = raw[0].length;
        boolean transposed = rows < cols;
        int numBoxes = transposed ? cols : rows;

        List<float[]> boxes = new ArrayList<>();
        List<float[]> kept = new ArrayList<>();
        for (int i = 0; i < numBoxes; i++) {
            float cx = transposed ? raw[0][i] : raw[i][0];
            float cy = transposed ? raw[1][i] : raw[i][1];
            float w = transposed ? raw[2][i] : raw[i][2];
            float h = transposed ? raw[3][i] : raw[i][3];
            float conf = transposed ? raw[4][i] : raw[i][4];
            if (conf < CONF_THRESHOLD) {
                continue;
            }
            cx = (cx - padX) / scale;
            cy = (cy - padY) / scale;
            w = w / scale;
            h = h / scale;
            float x1 = Math.max(0, cx - w / 2);
            float y1 = Math.max(0, cy - h / 2);
            float x2 = Math.min(srcWidth, cx + w / 2);
            float y2 = Math.min(srcHeight, cy + h / 2);
            float[] box = {x1, y1, x2, y2};
            if (boxes.isEmpty() || nmsOk(box, boxes, IOU_THRESHOLD)) {
                boxes.add(box);
                kept.add(new float[]{x1, y1, x2 - x1, y2 - y1, conf});
            }
        }
        List<PredictRectangle> result = new ArrayList<>(kept.size());
        for (float[] b : kept) {
            result.add(new PredictRectangle(b[0], b[1], b[2], b[3], b[4], 0, "license-plate", List.of()));
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
