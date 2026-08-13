package com.chua.deeplearning.support.onnx;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.DetectedObjects.DetectedObject;
import ai.djl.translate.Translator;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.detection.multi.AbstractMultiClassYolov8Translator;
import com.chua.deeplearning.support.onnx.detection.single.AbstractSingleClassYolov8Translator;
import com.chua.deeplearning.support.onnx.layout.doclaynet.DocLayNetYolov8Translator;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import java.io.FileWriter;

/**
 * 6 个新模型翻译器综合推理测试：使用 yolov8n.onnx (COCO 80 类) 作为通用 YOLOv8 演示权重。
 * 每个翻译器按 classes.size() 截断 nc，因此 doclaynet(11) / table(1) / 其它 都从 nc=80 真实 ONNX
 * 拿前 N 个 class scores，验证处理流程。
 *
 * 输出: target/translator-inference-results.json
 */
public class SixNewTranslatorsInferenceTest {

    static int totalOk = 0;
    static int totalFail = 0;
    static StringBuilder allResults = new StringBuilder();

    public static void main(String[] args) throws Exception {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();

        // 构造合成测试图: 640x640 RGB 渐变背景 + 几个深色方块
        BufferedImage img = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 640; y++) {
            for (int x = 0; x < 640; x++) {
                int r = (x * 255 / 639);
                int g = (y * 255 / 639);
                int b = ((x + y) * 255 / 1278);
                img.setRGB(x, y, new Color(r, g, b).getRGB());
            }
        }
        // 中心放一个 200x150 深色矩形: 模拟物体
        for (int y = 220; y < 420; y++) {
            for (int x = 220; x < 420; x++) {
                img.setRGB(x, y, new Color(40, 40, 40).getRGB());
            }
        }
        Path tmpPng = Files.createTempFile("six-trans-test-", ".png");
        ImageIO.write(img, "png", tmpPng.toFile());

        Image image = ImageFactory.getInstance().fromFile(tmpPng.toAbsolutePath());
        System.out.println("合成测试图: " + image.getWidth() + "x" + image.getHeight());

        // 6 个新翻译器
        runTranslator("doclaynet-yolo-imgsz640", null, "DocLayNetYolov8Translator", image);
        runTranslator("yolov8n-table-detection", "TableDetectionYolov8Translator", null, image);
        runTranslator("yolov8n-seal-detection", "SealDetectionYolov8Translator", null, image);
        runTranslator("yolov8n-barcode", "BarcodeDetectionYolov8Translator", null, image);
        runTranslator("yolov8n-ppe", "PpeDetectionYolov8Translator", null, image);
        runTranslator("yolov8n-fire-smoke", "FireSmokeDetectionYolov8Translator", null, image);

        Files.deleteIfExists(tmpPng);

        // 写 JSON 结果
        Path jsonOut = Path.of("target/translator-inference-results.json");
        jsonOut.toFile().getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(jsonOut.toFile())) {
            w.write(buildJson());
        }
        System.out.println("\n========================================");
        System.out.println("汇总: " + totalOk + " 成功, " + totalFail + " 失败");
        System.out.println("JSON: " + jsonOut.toAbsolutePath());
        System.out.println("========================================");
        System.exit(totalFail > 0 ? 1 : 0);
    }

    static void runTranslator(String modelId, String singleCls, String multiCls, Image image) {
        try {
            Path modelPath = ModelRegistry.resolveModelPath(modelId);
            if (modelPath == null || !Files.exists(modelPath)) {
                System.out.println("[" + modelId + "] 模型路径无效");
                totalFail++;
                recordResult(modelId, false, 0, 0, 0, 0, "model path invalid");
                return;
            }
            long t0 = System.currentTimeMillis();
            Translator<Image, DetectedObjects> translator;
            if (singleCls != null) {
                translator = createSingleClass(singleCls);
            } else if (multiCls != null) {
                translator = createMultiClass(multiCls);
            } else {
                translator = new DocLayNetYolov8Translator();
            }

            // 用单一 wrapper 跑推理 (走 DJL Model + Predictor)
            try (ai.djl.Model model = ai.djl.Model.newInstance(modelId, "OnnxRuntime")) {
                model.load(modelPath, modelId);
                long loadMs = System.currentTimeMillis() - t0;
                try (ai.djl.inference.Predictor<Image, DetectedObjects> p = model.newPredictor(translator)) {
                    long t1 = System.currentTimeMillis();
                    DetectedObjects result = p.predict(image);
                    long inferMs = System.currentTimeMillis() - t1;
                    int numDet = result.getNumberOfObjects();
                    List<Object> detections = new ArrayList<>();
                    int maxToShow = Math.min(5, numDet);
                    for (int i = 0; i < maxToShow; i++) {
                        DetectedObject d = (DetectedObject) result.item(i);
                        detections.add(new Object[]{d.getClassName(), d.getProbability(), d.getBoundingBox()});
                    }
                    System.out.println(String.format("[OK] %s: load=%dms infer=%dms detections=%d",
                            modelId, loadMs, inferMs, numDet));
                    for (int i = 0; i < Math.min(3, numDet); i++) {
                        DetectedObject d = (DetectedObject) result.item(i);
                        System.out.println(String.format("    #%d class=%s prob=%.4f", i + 1, d.getClassName(), d.getProbability()));
                    }
                    totalOk++;
                    recordResult(modelId, true, loadMs, inferMs, numDet, 0, "OK");
                }
            }
        } catch (Throwable e) {
            System.out.println("[" + modelId + "] FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            totalFail++;
            recordResult(modelId, false, 0, 0, 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static Translator<Image, DetectedObjects> createSingleClass(String name) throws Exception {
        Class<?> cls = Class.forName("com.chua.deeplearning.support.onnx.detection.single." + name);
        Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return (Translator<Image, DetectedObjects>) ctor.newInstance();
    }

    static Translator<Image, DetectedObjects> createMultiClass(String name) throws Exception {
        Class<?> cls = Class.forName("com.chua.deeplearning.support.onnx.detection.multi." + name);
        Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return (Translator<Image, DetectedObjects>) ctor.newInstance();
    }

    static void recordResult(String modelId, boolean ok, long loadMs, long inferMs, int detections, int extra, String msg) {
        allResults.append(String.format(
            "{\"model_id\":\"%s\",\"ok\":%s,\"load_ms\":%d,\"infer_ms\":%d,\"detections\":%d,\"msg\":\"%s\"},",
            modelId, ok, loadMs, inferMs, detections, msg));
    }

    static String buildJson() {
        String all = allResults.toString();
        if (all.endsWith(",")) all = all.substring(0, all.length() - 1);
        return "{\n" +
                "  \"test\": \"SixNewTranslatorsInferenceTest\",\n" +
                "  \"date\": \"2026-08-13\",\n" +
                "  \"model_file\": \"yolov8n.onnx (COCO 80 classes, 12.3MB)\",\n" +
                "  \"summary\": \"ok=" + totalOk + " fail=" + totalFail + "\",\n" +
                "  \"results\": [\n    " + all + "\n  ]\n" +
                "}\n";
    }
}
