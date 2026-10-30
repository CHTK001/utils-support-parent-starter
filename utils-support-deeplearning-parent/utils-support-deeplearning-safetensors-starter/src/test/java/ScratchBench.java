import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class ScratchBench {

    static Path GT = Path.of("D:/ch/project/utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter/src/test/resources/layout-bench/ground-truth.json");
    static Path IMG = Path.of("D:/ch/project/utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter/src/test/resources/layout-bench");

    public static void main(String[] args) throws Exception {
        ModelRegistry.setModelRootDir("target/layout-bench/models");
        ModelRegistry.discoverAll();
        String[] pages = {"article", "table", "mixed", "columns", "dense", "list", "figure"};
        String[] models = {"indra-layout", "rtdetr-layout", "pp-doc-layout", "pp-doc-layout-l", "pp-doc-layout-plus-l", "doc-layout-yolo"};
        for (String page : pages) {
            byte[] png = Files.readAllBytes(IMG.resolve(page + ".png"));
            for (String model : models) {
                long t0 = System.currentTimeMillis();
                List<DetectionInfo> dets = null;
                try {
                    dets = com.chua.deeplearning.support.image.ImageDetector.create(model).detect(png);
                } catch (Throwable e) {
                    System.out.println(model + " / " + page + ": ERROR " + e);
                    continue;
                }
                System.out.println(model + " / " + page + ": " + (System.currentTimeMillis() - t0) + " ms, boxes=" + dets.size());
            }
        }
    }
}
