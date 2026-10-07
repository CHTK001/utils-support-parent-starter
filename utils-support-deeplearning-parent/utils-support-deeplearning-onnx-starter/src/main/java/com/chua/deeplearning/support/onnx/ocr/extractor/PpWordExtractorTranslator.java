package com.chua.deeplearning.support.onnx.ocr.extractor;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
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
import com.chua.deeplearning.support.onnx.GpuHelper;
import com.chua.deeplearning.support.onnx.ModelResourceResolver;

/**
 * PP-ocrv6 文字识别（ORT 原生 + 打开cv）。
 *
 * <p>替代 DJL 版（djl-onnx 不支持 NDArray 张量运算）。模型 + dict 由 jar
 * {@code utils-support-models-onnx-paddleocrv6-tiny} 提供，NativeLoader 解压。
 * 字符表从 {@code inference.yml} 的 {@code character_dict} 提取（与模型 6906 类对齐，
 * 而非精简的 dict.txt 6623 行）。输入 {@code x [1,3,48,W]}（打开cv resize 高 48、
 * 按宽高比缩放、归一化；宽度动态，上限 1920），输出 {@code fetch_name_0 [1,seq,classes]}，
 * CTC 解码 → 识别文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PpWordExtractorTranslator implements ITranslator<byte[], String> {

    /**
     * 图像高度
    */
    private static final int IMG_H = 48;
    /**
     * 图像最大宽度
    */
    private static final int IMG_W_MAX = 1920;
    /**
     * 均值数组
    */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    /**
     * 标准差数组
    */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /**
     * 模型文件路径
    */
    private static final String MODEL_FILE = "inference.onnx";
    /**
     * 配置文件路径
    */
    private static final String CONFIG_FILE = "inference.yml";

    /**
     * 模型资源目录（tiny / medium 通用）。
     */
    private final String resourceBase;

    /**
     * 模型名称（用于 NAT加载 缓存隔离）。
     */
    private final String modelName;

    /**
     * ONNX 运行时环境
    */
    private OrtEnvironment ortEnv;
    /**
     * 会话
    */
    private OrtSession session;
    /**
     * 词典列表
    */
    private List<String> dict;

    /**
     * 默认使用 PP-ocrv6 tiny 资源。
     */
    public PpWordExtractorTranslator() {
        this("ocr/PP-OCRv6/tiny/rec_infer/", "paddleocrv6-rec");
    }

    /**
     * 指定资源目录构造。
     *
     * @param resourceBase 模型资源目录（jar 内路径）
     * @param modelName    模型名称
     */
    public PpWordExtractorTranslator(String resourceBase, String modelName) {
        this.resourceBase = resourceBase;
        this.modelName = modelName;
    }

    /**
     * Prepare
    */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path modelPath = resolveModelFile();
        Path configPath = modelPath.getParent().resolve(CONFIG_FILE);
        if (Files.isRegularFile(configPath)) {
            dict = loadCharacterDict(configPath);
        } else {
            // 模型来自 classpath（模型资产 jar）时会被物化到缓存目录，
            // 但物化只带出主模型文件，同目录的 inference.yml 不会一起落盘，
            // 于是「onnx 旁边找字典」必然落空、字典退化为空表、识别结果全为空串
            // （且不抛错，极难排查）。这里回落到 classpath 按资源目录读取，
            // 与兄弟实现 PpOcrOpencvTranslator 的做法保持一致。
            log.info("[PaddleOCRv6-rec] 模型同目录无 {}，回落 classpath 读取: {}", CONFIG_FILE, configPath);
            dict = loadCharacterDictFromClasspath();
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        // 按该模型的 device 参数尝试启用 CUDA；缺 CUDA 原生库或初始化失败时静默保持 CPU
        GpuHelper.apply(opts, modelName);
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[PaddleOCRv6-rec] ONNX loaded: {} dict_size={}", modelPath, dict.size());
    }

    /**
     * 接收框架注入的模型路径 / 外部目录。
     *
     * @param path 模型绝对路径或外部目录路径
     */
    public void setModelPath(String path) {
        if (path != null && !path.isBlank()) {
            this.injectedPath = path.trim();
        }
    }

    /**
     * 框架注入的模型路径 / 外部目录。
     */
    private volatile String injectedPath;

    /**
     * 三级回退解析识别模型文件。
     *
     * @return 模型文件绝对路径
     * @throws Exception 三级均未找到时抛出
     */
    private Path resolveModelFile() throws Exception {
        Path external = ModelResourceResolver
                .resolveOnnx(injectedPath, modelName, resourceBase, MODEL_FILE);
        if (external != null) {
            return external;
        }
        // classpath 兜底
        Path tmpDir = Files.createTempDirectory("paddleocrv6-rec-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("rec");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName)
                .from(PpWordExtractorTranslator.class.getClassLoader())
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

    /**
     * 从 推理.yml 的 character_dict 提取完整字符表（含 blank 前缀，与模型类数对齐）。
     *
     * @param ymlPath 推理.yml 路径
     * @return 字符表（index 0 为 blank，其余为字符）
     */
    private static List<String> loadCharacterDict(Path ymlPath) throws Exception {
        return parseCharacterDict(Files.readAllLines(ymlPath));
    }

    /**
     * 从 classpath 读取字符字典（模型资产 jar 内的 {@code inference.yml}）。
     *
     * <p>模型来自 jar 时，物化到磁盘的只有主模型文件，附属的 {@code inference.yml}
     * 仍在 classpath 上，故按 {@link #resourceBase} 拼接资源路径直接读取。</p>
     *
     * @return 字符表（index 0 为 blank）；取不到时返回仅含 blank 的表
     */
    private List<String> loadCharacterDictFromClasspath() {
        String resource = resourceBase.endsWith("/") ? resourceBase + CONFIG_FILE : resourceBase + "/" + CONFIG_FILE;
        try (InputStream is = PpWordExtractorTranslator.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                log.warn("[PaddleOCRv6-rec] classpath 也找不到字符字典 {}，识别结果将为空", resource);
                return Wraps.list();
            }
            var table = parseCharacterDict(List.of(new String(is.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
            log.info("[PaddleOCRv6-rec] 字符字典取自 classpath: {} dict_size={}", resource, table.size());
            return table;
        } catch (Exception e) {
            log.warn("[PaddleOCRv6-rec] classpath 字典读取失败: {}", e.getMessage());
            return Wraps.list();
        }
    }

    /**
     * 解析 character_dict 段为字符表（文件与 classpath 两条来源共用）。
     *
     * @param lines 推理配置的文本行
     * @return 字符表（index 0 为 blank，其余为字符）
     */
    private static List<String> parseCharacterDict(List<String> lines) {
        List<String> chars = Wraps.list();
        boolean inDict = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("character_dict:")) {
                inDict = true;
                continue;
            }
            if (inDict) {
                if (trimmed.startsWith("- ")) {
                    String raw = trimmed.substring(2).trim();
                    String ch = raw;
                    if (ch.length() >= 2 && (ch.startsWith("'") || ch.startsWith("\""))) {
                        ch = ch.substring(1, ch.length() - 1);
                    }
                    if (ch.equals("\\")) {
                        ch = "\\";
                    }
                    chars.add(ch);
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("-")) {
                    break;
                }
            }
        }
        List<String> table = Wraps.list(chars.size() + 1);
        table.add("blank");
        table.addAll(chars);
        return table;
    }

    @Override
    /**
     * 名称
    */
    public String name() {
        return modelName;
    }

    @Override
    /**
     * Translate
     *
     * <p><b>本方法必须串行执行，不可去掉 synchronized。</b> 本实例只有一个
     * {@link OrtSession} 字段，由 {@link #prepare()} 创建、由 {@link #close()} 释放，
     * 而 ONNX Runtime Java 绑定的 {@code OrtSession.run()} <b>不是线程安全的</b>：
     * 同一 session 上并发 run 会在原生层破坏内存并直接 SIGSEGV 掉整个 JVM
     * （不是抛 Java 异常，Java 侧 catch/finally 完全拦不住）。
     *
     * <p>加锁范围覆盖 prepare + run 整体，且与已 synchronized 的 {@link #prepare()}、
     * {@link #close()} 共用同一监视器，因此既杜绝了并发 run，
     * 也杜绝了「一边 run 一边 close 释放原生 session」的 use-after-free。
     * 参见 {@code hs_err_pid78102.log}：崩溃线程为
     * {@code ForkJoinPool-1-worker-7} → 本类 {@code recognize} →
     * {@code OrtSession.run} → {@code libonnxruntime.so+0xb66403}。</p>
     *
     * <p>不用 {@code ThreadLocal<OrtSession>}：推理跑在虚拟线程上，线程数无界，
     * 会为每个虚拟线程各建一个原生 session 而耗尽内存。</p>
     */
    public synchronized String translate(byte[] imageData) {
        try {
            prepare();
            return recognize(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-rec] 文字识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * Recognize
     *
     * @param imageData 镜像数据
     * @return recognize的结果
     */
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
                float ratio = (float) srcH / IMG_H;
                int resizeW = Math.max(1, (int) Math.ceil(srcW / ratio));
                resizeW = Math.max(resizeW, 16);
                // 宽度动态（模型 shape=[-1,3,48,-1]），保留原始宽高比，仅限制上限防止超长行 OOM
                resizeW = Math.min(resizeW, IMG_W_MAX);

                Mat resized = ImageUtils.resize(src, resizeW, IMG_H, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * IMG_H * resizeW];
                for (int y = 0; y < IMG_H; y++) {
                    for (int x = 0; x < resizeW; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * resizeW + x;
                        // PP-OCR 输入 BGR 顺序（img_mode=BGR），归一化 (v/255 - 0.5) / 0.5
                        pixels[idx] = (((float) bgr[0] / 255.0f) - MEAN[0]) / STD[0];
                        pixels[idx + IMG_H * resizeW] = (((float) bgr[1] / 255.0f) - MEAN[1]) / STD[1];
                        pixels[idx + 2 * IMG_H * resizeW] = (((float) bgr[2] / 255.0f) - MEAN[2]) / STD[2];
                    }
                }
                resized.release();

                long[] shape = {1, 3, IMG_H, resizeW};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = Wraps.map();
                    inputs.put("x", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object out = result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
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
            throw new RuntimeException("[paddleocrv6-rec] 文字识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * CTC 解码：每步取 argmax，去掉连续重复和 blank（索引 0）。
     * @param seqProbs seqprobs
     * @return decode的结果
     */
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

    /**
     * 参数最大值
     *
     * @param arr arr
     * @return 参数最大的结果
     */
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
        dict = null;
    }
}
