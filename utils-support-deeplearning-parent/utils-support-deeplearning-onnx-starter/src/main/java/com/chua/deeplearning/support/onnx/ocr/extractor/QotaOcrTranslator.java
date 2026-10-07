package com.chua.deeplearning.support.onnx.ocr.extractor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.onnx.GpuHelper;
import com.chua.deeplearning.support.onnx.ModelResourceResolver;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Qota-OCR 文字识别（ORT 原生 + 打开cv）。
 *
 * <p>{@code atonlee/Qota-OCR}，PP-OCRv6_small_rec 架构加训韩文后的多语
 * （韩/中/日/英）行识别器，SynthDoG 四语字符 F1 均为 0.96~0.99。
 * 模型 + 字符表由 jar {@code utils-support-models-onnx-qota-ocr} 提供，NativeLoader 解压。
 * 字符表取自 {@code preprocessor_config.json} 的 {@code character_list}
 * （index 0 为 blank，共 30246 项，与输出类数对齐）。
 * 输入 {@code x [1,3,48,W]}（打开cv resize 高 48、按宽高比缩放、归一化；
 * 宽度动态，下限 320 上限 3200，不足 pad 0），输出每位置字符概率，CTC 解码 → 识别文本。</p>
 *
 * <p><b>与 Paddle 版的差异</b>：HF transformers 预处理链为
 * {@code resize → rescale → BGR转RGB → normalize → pad}，其中
 * {@code do_convert_rgb=true}——BGR 行图必须先转 RGB 再归一化；
 * Paddle 版（img_mode=BGR）则直接按 BGR 送入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class QotaOcrTranslator implements ITranslator<byte[], String>, AutoCloseable {

    private static final int IMG_H = 48;
    private static final int IMG_W_MIN = 320;
    private static final int IMG_W_MAX = 3200;
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    private static final String MODEL_FILE = "model.onnx";
    private static final String CONFIG_FILE = "preprocessor_config.json";

    private final String resourceBase;
    private final String modelName;

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private List<String> dict;

    public QotaOcrTranslator() {
        this("ocr/Qota-OCR/", "qota-ocr");
    }

    public QotaOcrTranslator(String resourceBase, String modelName) {
        this.resourceBase = resourceBase;
        this.modelName = modelName;
    }

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path modelPath = resolveModelFile();
        Path configPath = modelPath.getParent().resolve(CONFIG_FILE);
        if (Files.isRegularFile(configPath)) {
            dict = loadCharacterDict(configPath);
        } else {
            log.info("[Qota-OCR] 模型同目录无 {}，回落 classpath 读取: {}", CONFIG_FILE, configPath);
            dict = loadCharacterDictFromClasspath();
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        GpuHelper.apply(opts, modelName);
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[Qota-OCR] ONNX loaded: {} dict_size={}", modelPath, dict.size());
    }

    public void setModelPath(String path) {
        if (path != null && !path.isBlank()) {
            this.injectedPath = path.trim();
        }
    }

    private volatile String injectedPath;

    private Path resolveModelFile() throws Exception {
        Path external = ModelResourceResolver
                .resolveOnnx(injectedPath, modelName, resourceBase, MODEL_FILE);
        if (external != null) {
            return external;
        }
        Path tmpDir = Files.createTempDirectory("qota-ocr-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("rec");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName)
                .from(QotaOcrTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("OCR 资源缺失（外部目录 / 注册表 / classpath 均未找到）: " + modelName);
        }
        return modelPath;
    }

    private static List<String> loadCharacterDict(Path jsonPath) throws Exception {
        return parseCharacterDict(new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8));
    }

    private List<String> loadCharacterDictFromClasspath() {
        String resource = resourceBase.endsWith("/") ? resourceBase + CONFIG_FILE : resourceBase + "/" + CONFIG_FILE;
        try (InputStream is = QotaOcrTranslator.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                log.warn("[Qota-OCR] classpath 也找不到字符字典 {}，识别结果将为空", resource);
                return Wraps.list();
            }
            var table = parseCharacterDict(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            log.info("[Qota-OCR] 字符字典取自 classpath: {} dict_size={}", resource, table.size());
            return table;
        } catch (Exception e) {
            log.warn("[Qota-OCR] classpath 字典读取失败: {}", e.getMessage());
            return Wraps.list();
        }
    }

    static List<String> parseCharacterDict(String json) {
        List<String> table = Wraps.list();
        int key = json.indexOf("\"character_list\"");
        if (key < 0) {
            return table;
        }
        int open = json.indexOf('[', key);
        int close = json.lastIndexOf(']');
        if (open < 0 || close < 0 || close <= open) {
            return table;
        }
        String body = json.substring(open + 1, close);
        StringBuilder cur = null;
        boolean escape = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (cur == null) {
                if (c == '"') {
                    cur = new StringBuilder();
                    escape = false;
                }
                continue;
            }
            if (escape) {
                cur.append(c == 'n' ? '\n' : c == 't' ? '\t' : c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                table.add(cur.toString());
                cur = null;
                continue;
            }
            cur.append(c);
        }
        return table;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public synchronized String translate(byte[] imageData) {
        try {
            prepare();
            return recognize(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[qota-ocr] 文字识别失败: " + e.getMessage(), e);
        }
    }

    private String recognize(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                int srcW = src.cols();
                int srcH = src.rows();
                int resizeW = Math.max(1, (int) Math.ceil(srcW * (IMG_H / (double) srcH)));
                int modelW = Math.min(IMG_W_MAX, Math.max(IMG_W_MIN, resizeW));

                Mat resized = ImageUtils.resize(src, resizeW, IMG_H, Imgproc.INTER_LINEAR);
                Mat rgb = new Mat();
                Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB);
                resized.release();

                float[] pixels = new float[3 * IMG_H * modelW];
                for (int y = 0; y < IMG_H; y++) {
                    for (int x = 0; x < modelW; x++) {
                        double[] v = x < rgb.cols() ? rgb.get(y, x) : new double[]{0, 0, 0};
                        int idx = y * modelW + x;
                        pixels[idx] = (((float) v[0] / 255.0f) - MEAN[0]) / STD[0];
                        pixels[idx + IMG_H * modelW] = (((float) v[1] / 255.0f) - MEAN[1]) / STD[1];
                        pixels[idx + 2 * IMG_H * modelW] = (((float) v[2] / 255.0f) - MEAN[2]) / STD[2];
                    }
                }
                rgb.release();

                long[] shape = {1, 3, IMG_H, modelW};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = Wraps.map();
                    inputs.put("x", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object out = result.get(0).getValue();
                        float[][][] probs;
                        if (out instanceof float[][][]) {
                            probs = (float[][][]) out;
                        } else if (out instanceof float[][][][]) {
                            probs = ((float[][][][]) out)[0];
                        } else {
                            throw new IllegalArgumentException("OCR 输出格式不识别: " + out.getClass());
                        }
                        return decode(probs[0]);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[qota-ocr] 文字识别失败: " + e.getMessage(), e);
        }
    }

    private String decode(float[][] seqProbs) {
        StringBuilder sb = new StringBuilder();
        int prevIdx = -1;
        for (float[] step : seqProbs) {
            int maxIdx = argMax(step);
            if (maxIdx != prevIdx && maxIdx != 0 && maxIdx < dict.size()) {
                String ch = dict.get(maxIdx);
                if (ch != null && !ch.isBlank()) {
                    sb.append(ch);
                }
            }
            prevIdx = maxIdx;
        }
        return sb.toString();
    }

    private int argMax(float[] arr) {
        int idx = 0;
        float best = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > best) {
                best = arr[i];
                idx = i;
            }
        }
        return idx;
    }

    @Override
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        session = null;
        ortEnv = null;
        dict = null;
    }
}
