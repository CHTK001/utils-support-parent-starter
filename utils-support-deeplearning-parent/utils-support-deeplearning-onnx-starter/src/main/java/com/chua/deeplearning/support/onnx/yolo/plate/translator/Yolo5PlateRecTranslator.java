package com.chua.deeplearning.support.onnx.yolo.plate.translator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.plate.PlateResult;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
* yolov5 车牌识别（cvhub520/yolov5_car_铭牌，ORT 原生 + 打开cv）。
*
* <p>模型 {@code vision/detection/yolov5_plate/yolov5_plate_rec_color.onnx} 由 jar
* {@code utils-support-models-onnx-yolov5-plate} 提供。输入 {@code [1,3,48,168]}
* （打开cv resize 到 48×168，按 mean=0.588/std=0.193 归一化），输出
* {@code output_1 [1,21,78]}（CTC 字符 logits）+ {@code output_2 [1,5]}（颜色 logits）。
* 字符集来自 yolov5_car_铭牌.yaml 的 名称。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class Yolo5PlateRecTranslator implements ITranslator<byte[], PlateResult> {

    /**
    * 输入宽度。
    */
    private static final int INPUT_W = 168;

    /**
    * 输入高度。
    */
    private static final int INPUT_H = 48;

    /**
    * 归一化均值（yaml mean: 0.588）。
    */
    private static final float MEAN = 0.588f;

    /**
    * 归一化标准差（yaml std: 0.193）。
    */
    private static final float STD = 0.193f;

    /**
    * 字符集（yaml 名称，索引 0 为 blank）。
    */
    private static final String CHARS = "#京沪津渝冀晋蒙辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新学警港澳挂使领民航危0123456789ABCDEFGHJKLMNPQRSTUVWXYZ险品";

    /**
    * 车牌颜色类别。
    */
    private static final String[] COLORS = {"black", "blue", "green", "white", "yellow"};

    /**
    * jar 内模型资源目录。
    */
    private static final String RESOURCE_BASE = "vision/detection/yolov5_plate/";

    /**
    * 模型文件名。
    */
    private static final String MODEL_FILE = "yolov5_plate_rec_color.onnx";

    /**
    * ONNX 运行时环境。
    */
    private OrtEnvironment ortEnv;

    /**
    * 识别会话。
    */
    private OrtSession session;

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
        Path cache = Path.of(System.getProperty("java.io.tmpdir")).resolve("chua-models").resolve("yolov5-plate-rec");
        if (!Files.isRegularFile(cache.resolve(MODEL_FILE))) {
            Files.createDirectories(cache);
            NativeLoader.of("yolov5-plate-rec-color")
                    .from(Yolo5PlateRecTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(cache)
                    .glob("*.onnx")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        Path modelPath = cache.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("车牌识别模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        this.loaded = true;
        log.info("[Yolo5PlateRec] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return "yolov5-plate-recognize";
    }

    @Override
    public synchronized PlateResult translate(byte[] imageData) {
        try {
            prepare();
            return recognize(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[yolov5-plate-recognize] 车牌识别失败: " + e.getMessage(), e);
        }
    }

    /**
    * 识别车牌：预处理 → 推理 → CTC 解码车牌号 + 颜色 softmax。
    *
    * @param imageData 原图（应为检测到的车牌裁剪块）
    * @return 车牌识别结果
    */
    private PlateResult recognize(byte[] imageData) throws Exception {
        ImageUtils.load();
        Mat src = ImageUtils.decode(imageData);
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("无法解码图像");
        }
        try {
            Mat resized = new Mat();
            Imgproc.resize(src, resized, new org.opencv.core.Size(INPUT_W, INPUT_H), 0, 0, Imgproc.INTER_LINEAR);

            float[] pixels = new float[3 * INPUT_H * INPUT_W];
            for (int y = 0; y < INPUT_H; y++) {
                for (int x = 0; x < INPUT_W; x++) {
                    double[] bgr = resized.get(y, x);
                    int idx = y * INPUT_W + x;
                    // (v/255 - mean) / std，NCHW RGB 顺序
                    pixels[idx] = ((float) bgr[2] / 255.0f - MEAN) / STD;
                    pixels[idx + INPUT_H * INPUT_W] = ((float) bgr[1] / 255.0f - MEAN) / STD;
                    pixels[idx + 2 * INPUT_H * INPUT_W] = ((float) bgr[0] / 255.0f - MEAN) / STD;
                }
            }
            resized.release();

            long[] shape = {1, 3, INPUT_H, INPUT_W};
            try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(session.getInputInfo().keySet().iterator().next(), tensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    float[][] ctcLogits = toMat2D(result.get(0).getValue()); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
                    float[] colorLogits = toVector(result.get(1).getValue());
                    return new PlateResult(decodeCtc(ctcLogits), decodeColor(colorLogits));
                }
            }
        } finally {
            src.release();
        }
    }

    /**
    * CTC 解码：逐时间步 argmax → 相邻去重 → 跳过 blank(0)。
    *
    * @param logits 概率 [21][78]
    * @return 车牌号
    */
    private String decodeCtc(float[][] logits) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (float[] step : logits) {
            int best = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < step.length; i++) {
                if (step[i] > bestScore) {
                    bestScore = step[i];
                    best = i;
                }
            }
            if (best != last && best != 0 && best < CHARS.length()) {
                sb.append(CHARS.charAt(best));
            }
            last = best;
        }
        return sb.toString();
    }

    /**
    * 颜色分类：softmax 取最大类别。
    *
    * @param logits 颜色 logits [5]
    * @return 颜色名
    */
    private String decodeColor(float[] logits) {
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] > bestScore) {
                bestScore = logits[i];
                best = i;
            }
        }
        return best >= 0 && best < COLORS.length ? COLORS[best] : "unknown";
    }

    /**
    * 转二维数组 [steps][classes]。
    * @param value 值
    * @return 转为mat2d的结果
    */
    private float[][] toMat2D(Object value) {
        if (value instanceof float[][][][] arr4) {
            return (float[][]) arr4[0][0];
        } else if (value instanceof float[][][] arr3) {
            return (float[][]) arr3[0];
        } else if (value instanceof float[][] arr2) {
            return arr2;
        }
        throw new IllegalArgumentException("输出格式不识别: " + value.getClass());
    }

    /**
    * 转一维向量 [classes]。
    * @param value 值
    * @return 转为向量的结果
    */
    private float[] toVector(Object value) {
        if (value instanceof float[][][][] arr4) {
            return arr4[0][0][0];
        } else if (value instanceof float[][][] arr3) {
            return arr3[0][0];
        } else if (value instanceof float[][] arr2) {
            return arr2[0];
        } else if (value instanceof float[] arr1) {
            return arr1;
        }
        throw new IllegalArgumentException("输出格式不识别: " + value.getClass());
    }

    /**
    * 关闭底层 ONNX 会话。
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
