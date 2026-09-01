package com.chua.deeplearning.support.onnx.action;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.model.ActionDetectionResult;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * C3D 视频动作检测 ONNX 翻译器。
 *
 * <p>基于 C3D 卷积神经网络，对输入视频帧序列执行动作分类检测。
 *
 * @author CH
 * @since 4.0.0.42
 */
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class C3DActionDetectionTranslator implements ITranslator<byte[], List<ActionDetectionResult>> {

    private static final int INPUT_FRAMES = 4;
private static final int INPUT_CHANNELS = 3;
    private static final int INPUT_HEIGHT = 384;
    private static final int INPUT_WIDTH = 640;
    private static final int STEP = 2;
    private static final int VIDEO_LENGTH_LIMIT = 10;
    private static final float NMS_THRESH = 0.3f;
    private static final int PRE_NMS_TOP_N = 100;
    private static final int POST_NMS_TOP_N = 10;
    private static final float[] PRE_NMS_THRESH = {
            0.45f, 0.45f, 0.45f, 0.45f, 0.45f, 0.45f, 0.45f, 0.45f, 0.45f
    };
    private static final String[] ACTION_NAMES = {
            "举手", "吃喝", "吸烟", "打电话", "玩手机", "趴桌睡觉", "跌倒", "洗手", "拍照"
    };
    private static final int NUM_CLASSES = ACTION_NAMES.length;

    private static final String RESOURCE_BASE = "vision/action/c3d/";
    private static final String MODEL_FILE = "model.onnx";
    private static final String MODEL_ID = "c3d-action-detection";

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private volatile boolean loaded;

    private static volatile C3DActionDetectionTranslator shared;

    public static C3DActionDetectionTranslator getInstance() {
        if (shared == null) {
            synchronized (C3DActionDetectionTranslator.class) {
                if (shared == null) {
                    shared = new C3DActionDetectionTranslator();
                }
            }
        }
        return shared;
    }

    public C3DActionDetectionTranslator() {
    }

    private void prepare() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            try {
                Path cache = Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("chua-models").resolve(MODEL_ID);
                if (!Files.isRegularFile(cache.resolve(MODEL_FILE))) {
                    Files.createDirectories(cache);
                    com.chua.common.support.utils.NativeLoader.of(MODEL_ID)
                            .from(getClass().getClassLoader())
                            .basePath(RESOURCE_BASE)
                            .toTarget(cache)
                            .glob("*.onnx")
                            .withMd5(true)
                            .extractOnly(true)
                            .load();
                }
                this.ortEnv = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
                this.session = ortEnv.createSession(cache.resolve(MODEL_FILE).toString(), opts);
                this.loaded = true;
                log.info("C3D Action Detection 模型加载完成");
            } catch (Exception e) {
                throw new RuntimeException("C3DActionDetection 模型加载失败", e);
            }
        }
    }

    @Override
    public String name() {
        return MODEL_ID;
    }

    @Override
    public List<ActionDetectionResult> translate(byte[] videoData) {
        prepare();
        List<Mat> allFrames = new ArrayList<>();
        try {
            Path tempVideo = Files.createTempFile("c3d_action_", ".mp4");
            try {
                Files.write(tempVideo, videoData);
                List<ActionDetectionResult> results = new ArrayList<>();
                VideoCapture capture = new VideoCapture(tempVideo.toString());
                try {
                    if (!capture.isOpened()) {
                        log.warn("无法打开视频文件");
                        return List.of();
                    }
                    double fps = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FPS);
                    double totalFrames = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_COUNT);
                    if (fps <= 0 || totalFrames <= 0) {
                        return List.of();
                    }
                    double duration = totalFrames / fps;
                    int limitFrames = (int) Math.min(duration, VIDEO_LENGTH_LIMIT);
                    Mat frame = new Mat();
                    for (int i = 0; i < limitFrames * fps; i++) {
                        if (!capture.read(frame)) break;
                        if (frame.empty()) break;
                        allFrames.add(frame.clone());
                    }
                    frame.release();
                    if (allFrames.size() < INPUT_FRAMES) {
                        return List.of();
                    }
                    int windowStep = (int) (STEP * fps);
                    double actualFps = fps;
                    for (int startTs = 0; startTs + INPUT_FRAMES <= allFrames.size(); startTs += windowStep) {
                        List<Mat> windowFrames = new ArrayList<>();
                        for (int j = 0; j < INPUT_FRAMES && startTs + j < allFrames.size(); j++) {
                            windowFrames.add(allFrames.get(startTs + j));
                        }
                        if (windowFrames.size() < INPUT_FRAMES) break;
                        float[] inputData = preprocess(windowFrames);
                        long[] shape = {1, INPUT_CHANNELS, INPUT_FRAMES, INPUT_HEIGHT, INPUT_WIDTH};
                        float timestamp = (float) startTs / (float) actualFps;
                        try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputData), shape);
                             OnnxTensor heightTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(new long[]{INPUT_HEIGHT}), new long[0]);
                             OnnxTensor widthTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(new long[]{INPUT_WIDTH}), new long[0])) {
                            Map<String, OnnxTensor> inputs = new HashMap<>();
                            inputs.put("input_frames", inputTensor);
                            inputs.put("height", heightTensor);
                            inputs.put("width", widthTensor);
                            try (OrtSession.Result result = session.run(inputs)) {
                                float[][] predBboxes = parseTensor(result, "pred_bboxes", 3);
                                float[][] predScores = parseTensor(result, "pred_scores", 3);
                                List<ActionDetectionResult> windowResults = postprocess(predBboxes, predScores, timestamp);
                                results.addAll(windowResults);
                            }
                        }
                        for (Mat m : windowFrames) {
                            m.release();
                        }
                    }
                } finally {
                    capture.release();
                }
                results.sort(Comparator.comparingDouble(ActionDetectionResult::timestamp));
                return results.size() > POST_NMS_TOP_N ? results.subList(0, POST_NMS_TOP_N) : results;
            } finally {
                try { Files.deleteIfExists(tempVideo); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("C3D 动作检测推理失败", e);
            return List.of();
        } finally {
            for (Mat m : allFrames) {
                m.release();
            }
        }
    }

    private float[] preprocess(List<Mat> frames) {
        int frameSize = INPUT_HEIGHT * INPUT_WIDTH;
        int chStride = INPUT_FRAMES * frameSize;
        float[] data = new float[INPUT_CHANNELS * chStride];
        for (int f = 0; f < INPUT_FRAMES; f++) {
            Mat resized = new Mat();
            Imgproc.resize(frames.get(f), resized, new Size(INPUT_WIDTH, INPUT_HEIGHT));
            Mat rgb = new Mat();
            Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB);
            rgb.convertTo(rgb, org.opencv.core.CvType.CV_32F);
            for (int y = 0; y < INPUT_HEIGHT; y++) {
                for (int x = 0; x < INPUT_WIDTH; x++) {
                    double[] pixel = rgb.get(y, x);
                    if (pixel != null) {
                        int pixelOffset = f * frameSize + y * INPUT_WIDTH + x;
                        data[0 * chStride + pixelOffset] = (float) pixel[0];
                        data[1 * chStride + pixelOffset] = (float) pixel[1];
                        data[2 * chStride + pixelOffset] = (float) pixel[2];
                    }
                }
            }
            resized.release();
            rgb.release();
        }
        return data;
    }

    private float[][] parseTensor(OrtSession.Result result, String name, int expectedDims) {
        try {
            var opt = result.get(name);
            if (opt.isEmpty()) return new float[0][0];
            var tensor = (OnnxTensor) opt.get();
            float[] flat = tensor.getFloatBuffer().array();
            long[] shape = tensor.getInfo().getShape();
            if (shape.length != expectedDims) return new float[0][0];
            int dim1 = (int) shape[0];
            int dim2 = (int) shape[1];
            int dim3 = (int) shape[2];
            float[][] out = new float[dim1 * dim2][dim3];
            for (int i = 0; i < dim1; i++) {
                for (int j = 0; j < dim2; j++) {
                    int idx = (i * dim2 + j) * dim3;
                    float[] row = new float[dim3];
                    System.arraycopy(flat, idx, row, 0, dim3);
                    out[i * dim2 + j] = row;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("解析输出 {} 失败: {}", name, e.getMessage());
            return new float[0][0];
        }
    }

    private List<ActionDetectionResult> postprocess(float[][] predBboxes, float[][] predScores, float timestamp) {
        List<ActionDetectionResult> results = new ArrayList<>();
        int numDetections = Math.min(predBboxes.length, predScores.length);
        for (int c = 0; c < NUM_CLASSES; c++) {
            List<Detection> candidates = new ArrayList<>();
            for (int i = 0; i < numDetections; i++) {
                if (predScores[i].length <= c) continue;
                float score = predScores[i][c];
                if (score < PRE_NMS_THRESH[c]) continue;
                float[] box = predBboxes[i];
                if (box.length < 4) continue;
                candidates.add(new Detection(box[0], box[1], box[2], box[3], score, c));
            }
            if (candidates.isEmpty()) continue;
            candidates.sort((a, b) -> Float.compare(b.score, a.score));
            if (candidates.size() > PRE_NMS_TOP_N) {
                candidates = candidates.subList(0, PRE_NMS_TOP_N);
            }
            List<Detection> kept = nms(candidates, NMS_THRESH);
            for (Detection d : kept) {
                results.add(new ActionDetectionResult(
                        timestamp, ACTION_NAMES[d.classId], d.score,
                        d.x1, d.y1, d.x2 - d.x1, d.y2 - d.y1));
            }
        }
        return results;
    }

    private List<Detection> nms(List<Detection> detections, float threshold) {
        List<Detection> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            Detection a = detections.get(i);
            result.add(a);
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                Detection b = detections.get(j);
                float iou = computeIou(a, b);
                if (iou > threshold) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float computeIou(Detection a, Detection b) {
        float ax1 = a.x1, ay1 = a.y1, ax2 = a.x2, ay2 = a.y2;
        float bx1 = b.x1, by1 = b.y1, bx2 = b.x2, by2 = b.y2;
        float ix1 = Math.max(ax1, bx1), iy1 = Math.max(ay1, by1);
        float ix2 = Math.min(ax2, bx2), iy2 = Math.min(ay2, by2);
        float iw = Math.max(0, ix2 - ix1), ih = Math.max(0, iy2 - iy1);
        float inter = iw * ih;
        float areaA = (ax2 - ax1) * (ay2 - ay1);
        float areaB = (bx2 - bx1) * (by2 - by1);
        float union = areaA + areaB - inter;
        return union <= 0 ? 0 : inter / union;
    }

    private record Detection(float x1, float y1, float x2, float y2, float score, int classId) {}
}