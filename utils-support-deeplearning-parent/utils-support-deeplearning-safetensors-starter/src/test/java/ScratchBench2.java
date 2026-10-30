import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ScratchBench2 {

    static final Path IMG = Path.of("D:/ch/project/utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter/src/test/resources/layout-bench");

    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        String[] pages = {"article", "table"};
        String[] models = {"rtdetr-layout", "pp-doc-layout", "pp-doc-layout-l", "doc-layout-yolo", "indra-layout"};
        for (String model : models) {
            for (String page : pages) {
                byte[] png = Files.readAllBytes(IMG.resolve(page + ".png"));
                long t0 = System.currentTimeMillis();
                List<DetectionInfo> dets = null;
                try {
                    dets = ImageDetector.create(model).detect(png);
                } catch (Throwable e) {
                    System.out.println(model + " / " + page + ": ERROR " + e);
                    continue;
                }
                System.out.println(model + " / " + page + ": " + (System.currentTimeMillis() - t0) + " ms, boxes=" + dets.size());
            }
        }
    }
}
