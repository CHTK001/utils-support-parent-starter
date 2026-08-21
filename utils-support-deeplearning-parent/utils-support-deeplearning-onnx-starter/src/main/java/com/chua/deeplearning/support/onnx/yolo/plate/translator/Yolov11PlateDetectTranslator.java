package com.chua.deeplearning.support.onnx.yolo.plate.translator;

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

public class Yolov11PlateDetectTranslator implements ITranslator<byte[], List<PredictRectangle>> {

    private static final int INPUT_SIZE = 640;
    private static final float CONF_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final String RESOURCE_BASE = "vision/detection/yolov5_plate/";
    private static final String MODEL_FILE = "yolov5_plate_v1x.onnx";
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private int srcWidth;
    private int srcHeight;
    private volatile boolean loaded;

    private synchronized void prepare() throws Exception {
        if (loaded) return;
        Path direct = Path.of("G:\\work\\utils-support-models-parent\\utils-support-models-onnx-yolov5-plate\\src\\main\\resources\\vision\\detection\\yolov5_plate\\yolov5_plate_v1x.onnx");
        if (Files.isRegularFile(direct)) {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(direct.toString(), opts);
            this.loaded = true;
            return;
        }
        Path cache = Path.of(System.getProperty("java.io.tmpdir")).resolve("chua-models").resolve("yolov11-plate-v1x");
        if (!Files.isRegularFile(cache.resolve(MODEL_FILE))) {
            Files.createDirectories(cache);
            NativeLoader.of("yolov11-plate-v1x")
                    .from(Yolov11PlateDetectTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(cache)
                    .glob("*.onnx")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        Path modelPath = cache.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath))
            throw new IllegalArgumentException("车牌检测模型缺失: " + modelPath);
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        this.loaded = true;
    }

    @Override
    public String name() {
        return "yolov11-plate-detect-v1x";
    }

    @Override
    public synchronized List<PredictRectangle> translate(byte[] imageData) {
        try {
            prepare();
            return detect(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[yolov11-plate-v1x] " + e.getMessage(), e);
        }
    }

    private List<PredictRectangle> detect(byte[] imageData) throws Exception {
        ImageUtils.load();
        Mat src = ImageUtils.decode(imageData);
        if (src == null || src.empty()) throw new IllegalArgumentException("无法解码");
        try {
            srcWidth = src.cols();
            srcHeight = src.rows();
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
                    float[][][] arr;
                    if (out instanceof float[][][][] a4) arr = new float[][][]{a4[0][0]};
                    else if (out instanceof float[][][] a3) arr = a3;
                    else throw new IllegalStateException(out.getClass().toString());
                    float[][] dets;
                    if (arr.length == 1 && arr[0].length == 5) dets = transpose(arr[0]);
                    else dets = arr[0];
                    return decode(dets, scale, padX, padY);
                }
            }
        } finally {
            src.release();
        }
    }

    private float[][] transpose(float[][] m) {
        int r = m.length, c = m[0].length;
        float[][] t = new float[c][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) t[j][i] = m[i][j];
        return t;
    }

    private List<PredictRectangle> decode(float[][] dets, float scale, int padX, int padY) {
        List<float[]> boxes = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (float[] d : dets) {
            float conf = d.length > 4 ? d[4] : 0;
            if (conf < CONF_THRESHOLD) continue;
            float cx = d[0], cy = d[1], w = d[2], h = d[3];
            float x1 = Math.max(0, (cx - w / 2 - padX) / scale);
            float y1 = Math.max(0, (cy - h / 2 - padY) / scale);
            float x2 = Math.min(srcWidth, (cx + w / 2 - padX) / scale);
            float y2 = Math.min(srcHeight, (cy + h / 2 - padY) / scale);
            boxes.add(new float[]{x1, y1, x2, y2});
            scores.add(conf);
        }
        List<Integer> keep = nms(boxes, scores);
        List<PredictRectangle> res = new ArrayList<>();
        for (int i : keep) {
            float[] b = boxes.get(i);
            float w = b[2] - b[0], h = b[3] - b[1];
            res.add(new PredictRectangle(b[0], b[1], w, h, scores.get(i), 0, "plate"));
        }
        return res;
    }

    private List<Integer> nms(List<float[]> boxes, List<Float> scores) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) idx.add(i);
        idx.sort((a, b) -> Float.compare(scores.get(b), scores.get(a)));
        List<Integer> keep = new ArrayList<>();
        boolean[] suppressed = new boolean[boxes.size()];
        for (int i = 0; i < idx.size(); i++) {
            int cur = idx.get(i);
            if (suppressed[cur]) continue;
            keep.add(cur);
            for (int j = i + 1; j < idx.size(); j++) {
                int nxt = idx.get(j);
                if (suppressed[nxt]) continue;
                if (iou(boxes.get(cur), boxes.get(nxt)) > IOU_THRESHOLD) suppressed[nxt] = true;
            }
        }
        return keep;
    }

    private float iou(float[] a, float[] b) {
        float x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
        float x2 = Math.min(a[2], b[2]), y2 = Math.min(a[3], b[3]);
        float inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float aa = (a[2] - a[0]) * (a[3] - a[1]);
        float ab = (b[2] - b[0]) * (b[3] - b[1]);
        return inter / Math.max(1e-6f, aa + ab - inter);
    }
}
