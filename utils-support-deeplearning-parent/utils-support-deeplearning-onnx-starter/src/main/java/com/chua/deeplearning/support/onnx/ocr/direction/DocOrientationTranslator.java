package com.chua.deeplearning.support.onnx.ocr.direction;

import com.chua.deeplearning.support.ocr.DirectionInfo;
import com.chua.deeplearning.support.utils.ImageUtils;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * 文档方向分类（PP-lcnet_x1_0_doc_ori，4 类：0°/90°/180°/270°）。
 *
 * <p>PaddleOCR 文档方向分类模型，整图输入 48×192，输出 4 类 softmax。
 * 替代 pp-word-rotate（仅 0/180）的启发式整图矫正逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DocOrientationTranslator implements ITranslator<byte[], DirectionInfo> {

    /**
     * 缩放宽度
    */
    private static final int RESIZE_WIDTH = 224;
    /**
     * 缩放高度
    */
    private static final int RESIZE_HEIGHT = 224;
    /**
     * 类别名称列表
    */
    private static final List<String> CLASSES = List.of("0", "90", "180", "270");

    /**
     * 资源基础路径
    */
    private static final String RESOURCE_BASE = "ocr/direction/doc_ori/";
    /**
     * 模型文件路径
    */
    private static final String MODEL_FILE = "model.onnx";

    /**
     * ONNX 运行时环境
    */
    private OrtEnvironment ortEnv;
    /**
     * 会话
    */
    private OrtSession session;

    /**
     * Prepare
     */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("doc-orientation-");
        Path modelDir = tmpDir.resolve("model");
        Files.createDirectories(modelDir);
        try {
            NativeLoader.of("doc-orientation")
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*.onnx")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        } catch (Throwable ignore) {
            // NativeLoader 在 jar 内资源缺失时抛的是 UnsatisfiedLinkError（属于 Error 而非
            // Exception），必须连 Error 一起吞掉，否则会绕过下方 registry 探测直接失败。
            // ONNX 模型资产已从 maven 依赖移除（见 eduai pom 注释），部署模型随包放在
            // -Ddeeplearning.model.root-dir 指定目录时，走下方 ModelRegistry 回退。
        }
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            // 回退：模型根目录直接按 modelId 命名存放的场景
            modelPath = ModelRegistry.resolveModelPath("doc-orientation");
        }
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("文档方向分类模型缺失，期望 jar 内路径 " + RESOURCE_BASE + MODEL_FILE);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        // 会话建立后模型已读入内存，此时才登记临时目录清理。
        // 原实现只登记了顶层目录，而 deleteOnExit 要求目录为空才能删除，
        // model/ 及其内容（model.onnx 与 .md5_* 标记）从不登记，
        // 于是每次启动都残留一份（实测单份约 6.5MB）。
        registerDeleteOnExit(tmpDir);
        log.info("[doc-orientation] ONNX loaded: {}", modelPath.getFileName());
    }

    /**
     * 递归登记目录树到 {@link File#deleteOnExit()}。
     *
     * <p>{@code deleteOnExit} 按<b>后登记先删除</b>的逆序语义执行，且目录必须为空才能删掉。
     * 因此登记顺序必须让反序删除变成「文件 → 深目录 → 浅目录」，
     * 即<b>先按浅到深登记目录，再登记文件</b>。</p>
     *
     * <p>注意不能用 {@code Comparator.reverseOrder()} 直接对 {@link Path} 排序：
     * 它是<b>字典序</b>而非深度序，得到的删除顺序不保证「文件先于目录」，
     * 实测那样只能把残留从 3 项降到 1 项，顶层目录仍然删不掉。</p>
     *
     * @param root 待清理的根目录
     */
    private static void registerDeleteOnExit(Path root) {
        try (var paths = Files.walk(root)) {
            var all = paths.collect(Collectors.toList());
            all.stream()
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .map(Path::toFile)
                    .forEach(File::deleteOnExit);
            all.stream()
                    .filter(path -> !Files.isDirectory(path))
                    .map(Path::toFile)
                    .forEach(File::deleteOnExit);
        } catch (IOException e) {
            log.debug("[doc-orientation] 登记临时目录清理失败: {}", e.getMessage());
        }
    }
    @Override
    /**
     * 名称
    */
    public String name() {
        return "doc-orientation";
    }

    @Override
    /**
     * Translate
    */
    public DirectionInfo translate(byte[] imageData) {
        try {
            prepare();
            return classify(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[doc-orientation] 方向检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * Classify
     *
     * @param imageData 镜像数据
     * @return classify的结果
     */
    private DirectionInfo classify(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = Imgcodecs.imdecode(
                    new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
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
                        float[][] output = (float[][]) result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
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
