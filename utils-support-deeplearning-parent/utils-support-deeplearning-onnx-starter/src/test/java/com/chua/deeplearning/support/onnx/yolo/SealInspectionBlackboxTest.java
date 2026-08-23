package com.chua.deeplearning.support.onnx.yolo;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class SealInspectionBlackboxTest {

    @Test
    void blackboxAllImages() throws Exception {
        File root = new File("G:/images");
        File[] files = root.listFiles((d, n) -> {
            String s = n.toLowerCase();
            return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png")
                    || s.endsWith(".bmp") || s.endsWith(".webp") || s.endsWith(".tiff");
        });
        assertNotNull(files);
        assertTrue(files.length > 0, "G:/images 无图片");

        int pass = 0, fail = 0, noImage = 0, totalDet = 0, filesWithDet = 0;
        List<String> fails = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (File f : files) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                List<DetectionInfo> infos;
                try {
                    infos = ImageDetector.create("seal-inspection").detect(bytes);
                } catch (Exception e) {
                    if (f.getName().toLowerCase().endsWith(".webp")) {
                        BufferedImage bi = ImageIO.read(f);
                        if (bi == null) {
                            noImage++;
                            continue;
                        }
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        ImageIO.write(bi, "png", bos);
                        infos = ImageDetector.create("seal-inspection").detect(bos.toByteArray());
                    } else {
                        throw e;
                    }
                }
                pass++;
                // 阈值过滤（SealInspectionTranslator 默认 0.75）+ 人脸重叠过滤（人脸也被判为公章的误检）
                float thr = 0.75f;
                List<DetectionInfo> filtered = new ArrayList<>();
                if (infos != null) {
                    for (DetectionInfo d : infos) {
                        if (d.confidence() < thr) {
                            continue;
                        }
                        filtered.add(d);
                    }
                }
                filtered = filterFaceOverlap(bytes, filtered);
                if (!filtered.isEmpty()) {
                    filesWithDet++;
                    totalDet += filtered.size();
                    log.info("{} -> {} 目标(≥{})", f.getName(), filtered.size(), thr);
                    for (DetectionInfo d : filtered) {
                        log.info("  {} conf={} [{},{},{},{}]", d.label(), String.format("%.2f", d.confidence()), d.x(), d.y(), d.width(), d.height());
                    }
                    // 用 DrawerPipeline 标注出图（需求：检测需要使用 DrawerPipeline 标记出来）
                    byte[] annotated = new com.chua.deeplearning.support.draw.DrawerPipeline(thr)
                            .target(bytes)
                            .boxes(filtered, filtered.stream().map(d2 -> d2.label() + " " + String.format("%.2f", d2.confidence())).toList())
                            .done();
                    File outDir = new File("g:/images/output/seal-inspection");
                    outDir.mkdirs();
                    Files.write(new File(outDir, f.getName().replaceAll("\\.[^.]+$", "") + "_seal.png").toPath(), annotated);
                }
            } catch (Throwable e) {
                fail++;
                fails.add(f.getName() + ": " + e.getMessage());
                log.warn("{} 处理失败: {}", f.getName(), e.toString());
            }
        }
        long dt = System.currentTimeMillis() - t0;
        log.info("===== 黑盒测试汇总 G:/images (阈值 0.75 + DrawerPipeline) =====");
        log.info("总文件: {}  成功: {}  失败: {}  无法解码: {}", files.length, pass, fail, noImage);
        log.info("含印章文件数: {}  总印章数: {}", filesWithDet, totalDet);
        log.info("耗时: {}ms  输出: g:/images/output/seal-inspection", dt);
        assertEquals(0, fail, "有文件未通过: " + String.join(", ", fails));
    }

    /** 人脸重叠过滤：印章框与人脸框 IoU > 0.3 视为人脸误检为公章，剔除。供黑盒验证时抑制人脸误检。 */
    private static List<DetectionInfo> filterFaceOverlap(byte[] image, List<DetectionInfo> seals) {
        if (seals == null || seals.isEmpty()) {
            return seals;
        }
        List<com.chua.deeplearning.support.model.PredictRectangle> faces;
        try {
            faces = com.chua.deeplearning.support.face.FaceDetector.create("scrfd-face-detector").detect(image);
        } catch (Throwable e) {
            return seals;
        }
        if (faces == null || faces.isEmpty()) {
            return seals;
        }
        List<DetectionInfo> out = new ArrayList<>();
        for (DetectionInfo d : seals) {
            boolean overlap = false;
            for (com.chua.deeplearning.support.model.PredictRectangle f : faces) {
                double iou = iou(d.x(), d.y(), d.width(), d.height(), f.x(), f.y(), f.width(), f.height());
                if (iou > 0.3) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                out.add(d);
            } else {
                log.info("  人脸过滤: 剔除 {} [{},{},{},{}] 与人脸重叠", d.label(), d.x(), d.y(), d.width(), d.height());
            }
        }
        return out;
    }

    private static double iou(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        double x1 = Math.max(ax, bx), y1 = Math.max(ay, by);
        double x2 = Math.min(ax + aw, bx + bw), y2 = Math.min(ay + ah, by + bh);
        double inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double aArea = aw * ah, bArea = bw * bh;
        double uni = aArea + bArea - inter;
        return uni <= 0 ? 0 : inter / uni;
    }
}