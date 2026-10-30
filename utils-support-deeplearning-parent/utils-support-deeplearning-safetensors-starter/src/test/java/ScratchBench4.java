import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ScratchBench4 {

    static final Path IMG_DIR = Path.of("D:/ch/project/utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter/src/test/resources/layout-bench");
    static final Path GT_PATH = IMG_DIR.resolve("ground-truth.json");

    static double iou(double[] b1, double[] b2) {
        double x1 = Math.max(b1[0], b2[0]), y1 = Math.max(b1[1], b2[1]);
        double x2 = Math.min(b1[2], b2[2]), y2 = Math.min(b1[3], b2[3]);
        double inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double a1 = (b1[2] - b1[0]) * (b1[3] - b1[1]), a2 = (b2[2] - b2[0]) * (b2[3] - b2[1]);
        if (a1 <= 0 || a2 <= 0) return 0.0;
        return inter / (a1 + a2 - inter + 1e-9);
    }

    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(GT_PATH, StandardCharsets.UTF_8));
        String[] pages = {"article", "table", "mixed", "columns", "dense", "list", "figure"};
        String[] models = {"indra-layout", "rtdetr-layout", "pp-doc-layout", "pp-doc-layout-l", "doc-layout-yolo"};
        for (String model : models) {
            System.out.println("== model: " + model + " ==");
            int totalBoxes = 0, totalGt = 0, totalHit50 = 0;
            double sumIoU = 0;
            for (String page : pages) {
                byte[] png = Files.readAllBytes(IMG_DIR.resolve(page + ".png"));
                long t0 = System.currentTimeMillis();
                List<DetectionInfo> dets = null;
                try {
                    dets = ImageDetector.create(model).detect(png);
                } catch (Throwable e) {
                    System.out.println("   " + page + ": ERROR " + e);
                    continue;
                }
                int nGt = 0, nHit = 0;
                if (root.has(page) && root.get(page).has("blocks")) {
                    for (JsonNode b : root.get(page).path("blocks")) {
                        nGt++;
                    }
                }
                for (DetectionInfo d : dets) {
                    double[] p = new double[]{d.x(), d.y(), d.x() + d.width(), d.y() + d.height()};
                    double best = 0;
                    for (JsonNode b : root.get(page).path("blocks")) {
                        double[] g = new double[]{b.path("bbox").get(0).asDouble(), b.path("bbox").get(1).asDouble(),
                                b.path("bbox").get(2).asDouble(), b.path("bbox").get(3).asDouble()};
                        double i = iou(p, g);
                        if (i > best) best = i;
                    }
                    sumIoU += best;
                    totalBoxes++;
                    if (best >= 0.5) {
                        nHit++;
                        totalHit50++;
                    }
                }
                totalGt += nGt;
                System.out.println("   " + page + ": " + (System.currentTimeMillis() - t0) + "ms, pred=" + dets.size() + ", gt=" + nGt
                        + ", hit@0.5=" + nHit + ", recall@0.5=" + String.format("%.3f", (double) nHit / Math.max(1, nGt))
                        + ", meanIoU=" + String.format("%.3f", sumIoU / Math.max(1, totalBoxes)));
            }
            System.out.println("   TOTAL: pred=" + totalBoxes + ", gt=" + totalGt + ", hit@0.5=" + totalHit50
                    + ", recall@0.5=" + String.format("%.3f", (double) totalHit50 / Math.max(1, totalGt))
                    + ", meanIoU=" + String.format("%.3f", totalBoxes > 0 ? sumIoU / totalBoxes : 0));
        }
    }
}
