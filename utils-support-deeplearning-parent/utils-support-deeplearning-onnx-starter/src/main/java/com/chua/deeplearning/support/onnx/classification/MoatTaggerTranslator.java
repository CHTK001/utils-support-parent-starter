package com.chua.deeplearning.support.onnx.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * WD-v1-4 MoAT Tagger V2 自动标签 Translator（448×448，NHWC BGR→RGB，9083 标签）。
 *
 * <p>输入 448×448 NHWC，ImageNet 均值方差，输出 9083 维 sigmoid 标签分数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MoatTaggerTranslator implements Translator<Image, Classifications> {

    private static final int INPUT_SIZE = 448;
    private static final int TOP_K = 20;

    private final int topk;
    private List<String> classes;
    private final Path csvPath;

    public MoatTaggerTranslator() {
        this(TOP_K, null);
    }

    public MoatTaggerTranslator(int topk, Path csvPath) {
        this.topk = topk;
        this.csvPath = csvPath;
    }

    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path csv = csvPath != null ? csvPath : modelRoot.resolve("wd-v1-4-moat-tagger-v2.csv");
        if (!Files.exists(csv)) {
            // 尝试 jar 内资源
            try {
                java.net.URL url = MoatTaggerTranslator.class.getClassLoader().getResource("vision/tagging/wd-v1-4-moat/wd-v1-4-moat-tagger-v2.csv");
                if (url != null) {
                    java.io.InputStream is = url.openStream();
                    Path tmp = Files.createTempFile("moat-csv", ".csv");
                    Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    is.close();
                    tmp.toFile().deleteOnExit();
                    csv = tmp;
                }
            } catch (Exception ignore) {}
        }
        classes = loadCsv(csv);
        log.info("[MoAT Tagger] 类别: {} (csv={})", classes.size(), csv);
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        var manager = ctx.getNDManager();
        // DJL Image -> BGR uint8，resize 448
        var array = input.toNDArray(manager, Image.Flag.COLOR);
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);
        array = array.toType(DataType.FLOAT32, false).div(255f);
        // NHWC [H,W,3] -> [1, H, W, 3] batch，保持 NHWC 输入（input_1 要求 NHWC BGR）
        // 端输入为 NHWC BGR，故先 BGR->RGB (channel 0<->2 交换)
        // toNDArray 出 BGR，需转 RGB：手工交换通道
        float[] bgr = array.toFloatArray();
        // bgr 是 HWC float，交换通道
        int hw = INPUT_SIZE * INPUT_SIZE;
        for (int i = 0; i < hw; i++) {
            float b = bgr[i * 3];
            float r = bgr[i * 3 + 2];
            bgr[i * 3] = r;
            bgr[i * 3 + 2] = b;
        }
        // HWC -> 添加 batch [1, H, W, 3]
        NDArray nhwc = ctx.getNDManager().create(bgr, new ai.djl.ndarray.types.Shape(1, INPUT_SIZE, INPUT_SIZE, 3));
        // ImageNet normalize (NHWC 最后一维)
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        // 手工 normalize
        for (int i = 0; i < bgr.length; i++) {
            int c = i % 3;
            nhwc = nhwc; // 占位，避免未用
            bgr[i] = (bgr[i] - mean[c]) / std[c];
        }
        nhwc = ctx.getNDManager().create(bgr, new ai.djl.ndarray.types.Shape(1, INPUT_SIZE, INPUT_SIZE, 3));
        nhwc.setName("input_1:0");
        return new NDList(nhwc);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var prob = list.singletonOrThrow();
        float[] scores = prob.toFloatArray();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) idx.add(i);
        idx.sort(Comparator.comparingDouble((Integer i) -> -scores[i]));
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        int k = Math.min(topk, idx.size());
        for (int i = 0; i < k; i++) {
            int id = idx.get(i);
            names.add(id < classes.size() ? classes.get(id) : "tag-" + id);
            probs.add((double) scores[id]);
        }
        return new Classifications(names, probs);
    }

    @Override public Batchifier getBatchifier() { return Batchifier.STACK; }

    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) return Path.of(".");
        Path n = modelPath.toAbsolutePath().normalize();
        return java.nio.file.Files.isDirectory(n) ? n : (n.getParent() == null ? n : n.getParent());
    }

    private static List<String> loadCsv(Path csv) throws Exception {
        List<String> names = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(csv), StandardCharsets.UTF_8))) {
            String line; boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = line.split(",", -1);
                if (parts.length >= 2) names.add(parts[1].trim());
            }
        }
        return names;
    }
}
