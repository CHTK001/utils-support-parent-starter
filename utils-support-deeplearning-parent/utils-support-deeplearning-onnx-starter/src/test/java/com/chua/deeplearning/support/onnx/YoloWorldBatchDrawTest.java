package com.chua.deeplearning.support.onnx;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.utils.ImageUtils;
import com.chua.deeplearning.support.draw.DrawerPipeline;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class YoloWorldBatchDrawTest {
    public static void main(String[] args) throws Exception {
        String inputDir = "G:/images";
        String outputDir = "G:/images/output/yolov8s-world";
        if (args.length > 0) inputDir = args[0];
        if (args.length > 1) outputDir = args[1];
        Path in = Path.of(inputDir);
        Path outRoot = Path.of(outputDir);
        Files.createDirectories(outRoot);
        System.out.println("输入: " + in);
        System.out.println("输出: " + outRoot);
        ImageDetector detector = null;
        try {
            detector = ImageDetector.create("yolov8s-world");
            System.out.println("detector: " + detector);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("创建 detector 失败: " + e.getMessage());
            return;
        }
        File[] files = new File(inputDir).listFiles((d,n)-> n.toLowerCase().matches(".*\\.(jpg|jpeg|png|webp)"));
        if (files==null || files.length==0) { System.out.println("无图片"); return; }
        int ok=0, fail=0;
        for (File f: files) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                long t0 = System.currentTimeMillis();
                List<DetectionInfo> infos = detector.detect(bytes);
                long dt = System.currentTimeMillis()-t0;
                System.out.printf("%s -> %d 个目标 %dms%n", f.getName(), infos==null?0:infos.size(), dt);
                if (infos!=null) for (DetectionInfo d: infos) {
                    System.out.printf("  %s %.2f [%.1f,%.1f %.1fx%.1f]%n", d.label(), d.confidence(), d.x(), d.y(), d.width(), d.height());
                }
                // 绘图 — 改用 DrawerPipeline 统一绘制（红框+标签底，自动适配已归一化框）
                List<String> labels = infos == null ? List.of() : infos.stream().map(d -> d.label() + " " + String.format("%.2f", d.confidence())).toList();
                DrawerPipeline pipeline = new DrawerPipeline(0.1f);
                byte[] drawn = pipeline.target(bytes).boxes(infos == null ? List.of() : infos, labels).done();
                File out = new File(outRoot.toFile(), f.getName().replaceAll("\\.[^.]+$","")+".png");
                Files.write(out.toPath(), drawn);
                ok++;
            } catch (Throwable e) {
                System.out.println("失败 "+f.getName()+": "+e.getMessage());
                e.printStackTrace();
                fail++;
            }
        }
        System.out.printf("完成 ok=%d fail=%d%n", ok, fail);
    }
}
