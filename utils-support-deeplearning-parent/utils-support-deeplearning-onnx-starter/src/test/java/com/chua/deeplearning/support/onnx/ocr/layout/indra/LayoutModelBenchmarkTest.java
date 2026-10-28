package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 布局模型横向基准：同一入口（{@link ImageDetector}）跑多个布局模型，预测结果落盘供评分。
 *
 * <p>默认不执行；需要 {@code -Dlayout.bench=true} 且把各模型的权重准备到
 * {@code target/layout-bench/models/<relativePath>}（模型根目录），例如：</p>
 * <pre>
 * -Dlayout.bench=true
 * </pre>
 *
 * <p>产物：{@code target/layout-bench/pred/<model>/<page>.json}，
 * 由 {@code score_bench.py} 与 {@code src/test/resources/layout-bench/ground-truth.json} 比对。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@EnabledIfSystemProperty(named = "layout.bench", matches = "true")
class LayoutModelBenchmarkTest {

    /**
     * 测试日志（避免使用 System.out 输出）
     */
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(LayoutModelBenchmarkTest.class.getName());

    /** 待评测模型（注册标识）。DJL 模型须排在原生 ORT 模型之前：
     * DJL 的 onnxruntime 引擎要求独占 OrtEnvironment，反过来会报
     * “Tried to specify the thread pool when creating an OrtEnvironment”。 */
    private static final String[] MODELS = {
            "pp-doc-layout", "pp-doc-layout-l", "pp-doc-layout-plus-l",
            "doc-layout-yolo", "rtdetr-layout", "indra-layout"
    };

    /** 基准页（与 ground-truth.json 的键一致）。 */
    private static final String[] PAGES = {"article", "table", "list", "columns", "figure", "mixed", "dense"};

    private static final Path BENCH = Path.of("target", "layout-bench");
    private static final Path PRED = BENCH.resolve("pred");

    @BeforeAll
    static void setup() throws Exception {
        ModelRegistry.setModelRootDir(BENCH.resolve("models").toString());
        ModelRegistry.discoverAll();
        Files.createDirectories(PRED);
    }

    @Test
    void runAll() throws Exception {
        for (String page : PAGES) {
            byte[] png = read("layout-bench/" + page + ".png");
            for (String model : MODELS) {
                long t0 = System.currentTimeMillis();
                List<DetectionInfo> dets;
                try {
                    dets = ImageDetector.create(model).detect(png);
                } catch (Throwable e) {
                    LOG.info("[bench] " + model + " / " + page + " 失败: " + e);
                    continue;
                }
                long ms = System.currentTimeMillis() - t0;
                Path dir = PRED.resolve(model);
                Files.createDirectories(dir);
                StringBuilder sb = new StringBuilder("{\"page\":\"").append(page)
                        .append("\",\"model\":\"").append(model).append("\",\"ms\":").append(ms)
                        .append(",\"boxes\":[");
                for (int i = 0; i < dets.size(); i++) {
                    DetectionInfo d = dets.get(i);
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(String.format(
                            "{\"label\":\"%s\",\"conf\":%.4f,\"bbox\":[%.1f,%.1f,%.1f,%.1f]}",
                            d.label(), d.confidence(), d.x(), d.y(),
                            d.x() + d.width(), d.y() + d.height()));
                }
                sb.append("]}");
                String json = sb.toString();
                if ("indra-layout".equals(model)) {
                    // indra 额外产出块内文字，供 OCR 字符错误率（CER）对比
                    json = json.substring(0, json.length() - 1) + "," + indraTexts(png) + "}";
                }
                Files.writeString(dir.resolve(page + ".json"), json, StandardCharsets.UTF_8);
                LOG.info("[bench] " + model + " / " + page + " -> " + dets.size()
                        + " boxes, " + ms + " ms");
            }
        }
    }

    /** indra 的块内文字（JSON 数组），供 OCR 字符错误率对比。 */
    private static String indraTexts(byte[] png) {
        IndraLayoutTranslator t = new IndraLayoutTranslator();
        try {
            StringBuilder sb = new StringBuilder("\"texts\":[");
            List<IndraLayoutTranslator.Block> blocks = t.blocks(png);
            for (int i = 0; i < blocks.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String text = blocks.get(i).text().replace("\n", " ")
                        .replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append('"').append(text).append('"');
            }
            return sb.append(']').toString();
        } finally {
            t.close();
        }
    }

    private static byte[] read(String name) throws Exception {
        try (InputStream in = LayoutModelBenchmarkTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("缺少基准页: " + name);
            }
            return in.readAllBytes();
        }
    }

    /** 预留：便于外部脚本列举模型。 */
    static List<String> models() {
        return new ArrayList<>(List.of(MODELS));
    }
}
