package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OCR 批量测试：逐张 G:\images 下文字/票据/表格图，跑完整 OCR 管线，
 * 结果绘制到原图上（绿框 + 中文识别文本），输出至 G:\images\output\paddleocrv6-medium\。
 *
 * <p>测试模型：paddleocrv6-medium-det + paddleocrv6-medium-rec + doc-orientation 方向矫正。</p>
 *
 * <pre>{@code
 *   OcrBatchTest G:\images
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class OcrBatchTest {

    /** OCR 名称集合 */
    private static final Set<String> OCR_NAMES = Set.of(
            "led文字.jpg", "表格.jpg", "车票.png",
            "车票ticket_180.png", "车票ticket_270.png", "车票ticket_90.png",
            "画作表格.png", "气象文字.png", "有文字图片.png",
            "机器上的文字.jpg", "倾斜角的文字.jpg", "有文字.jpg",
            "周报.png", "票房表格.png",
            "很不清楚的文字图片用于测试文字高清修复模型.png",
            "很不清楚的文字图片用于测试文字高清修复模型1.png",
            "很不清楚的文字图片用于测试文字高清修复模型2.png"
    );

    private OcrBatchTest() {
    }

    public static void main(String[] args) throws Exception {
        String dir = "G:\\images";
        String outDir = "G:\\images\\output\\paddleocrv6-medium";
        File folder = new File(dir);
        File out = new File(outDir);
        if (!out.exists()) {
            out.mkdirs();
        }

        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
                .build();

        int total = 0;
        int pass = 0;
        int fail = 0;

        for (String name : OCR_NAMES) {
            File f = new File(folder, name);
            if (!f.exists()) {
                System.out.println("[ocr] SKIP " + name + " (文件不存在)");
                continue;
            }
            total++;
            byte[] img = Files.readAllBytes(f.toPath());
            long t0 = System.currentTimeMillis();
            try {
                // 识别在"矫正后"的图上进行，绘制也必须用矫正后的图，
                // 否则旋转图（90/180/270）的框坐标与绘制底图坐标系不一致，导致框错位。
                OcrPipeline.OcrRecognizeResult rr = ocr.recognizeDetailWithImage(img);
                List<OcrResult> results = rr.results();
                long elapsed = System.currentTimeMillis() - t0;

                List<DetectionInfo> boxes = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (OcrResult r : results) {
                    PredictRectangle b = r.boundingBox();
                    if (b == null) {
                        continue;
                    }
                    boxes.add(new DetectionInfo(
                            String.format("%.2f", r.confidence()), r.confidence(),
                            b.x(), b.y(), b.width(), b.height(), r.angle()));
                    labels.add(r.text());
                }
                byte[] drawn = ImageUtils.drawDetectionsWithLabels(rr.image(), boxes, labels);
                String outFile = outDir + File.separator + replaceExt(name);
                Files.write(Path.of(outFile), drawn);

                String topText = results.isEmpty() ? "(无文字)" : results.get(0).text();
                System.out.printf("[ocr] %-40s 文本数=%-3d 耗时=%5dms 已输出=%s top='%s'%n",
                        name, results.size(), elapsed, replaceExt(name),
                        topText.length() > 30 ? topText.substring(0, 30) + "..." : topText);
                if (!results.isEmpty()) pass++; else fail++;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - t0;
                System.out.printf("[ocr] %-40s 异常 耗时=%5dms err='%s'%n",
                        name, elapsed, e.getMessage().length() > 60
                                ? e.getMessage().substring(0, 60) + "..." : e.getMessage());
                fail++;
            }
        }

        System.out.println();
        System.out.printf("[ocr-batch] 总计=%d  识别成功=%d  失败/跳过=%d  输出目录=%s%n", total, pass, fail, outDir);
    }

    private static String replaceExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".png";
    }
}