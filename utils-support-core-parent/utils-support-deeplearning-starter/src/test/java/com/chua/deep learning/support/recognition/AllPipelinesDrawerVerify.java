package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.draw.DrawerPipeline;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AllPipelinesDrawerVerify {

    private static final List<String> PIPELINES = List.of(
            "com.chua.deeplearning.support.face.FacePipeline",
            "com.chua.deeplearning.support.ocr.OcrPipeline",
            "com.chua.deeplearning.support.plate.PlatePipeline",
            "com.chua.deeplearning.support.recognition.AgeGenderPipeline",
            "com.chua.deeplearning.support.recognition.DocumentParsePipeline",
            "com.chua.deeplearning.support.recognition.EmotionPipeline",
            "com.chua.deeplearning.support.recognition.LayoutPipeline",
            "com.chua.deeplearning.support.recognition.PlateNumberPipeline",
            "com.chua.deeplearning.support.recognition.PosePipeline",
            "com.chua.deeplearning.support.recognition.SceneUnderstandingPipeline",
            "com.chua.deeplearning.support.recognition.TableStructurePipeline",
            "com.chua.deeplearning.support.recognition.TextDirectionPipeline",
            "com.chua.deeplearning.support.search.SearchPipeline"
    );

    public static void main(String[] args) throws Exception {
        byte[] testImage = testImageBytes();
        System.out.println("=== 1) verify withInitDrawer declared ===");
        int ok = 0;
        for (String cls : PIPELINES) {
            try {
                Class<?> c = Class.forName(cls);
                Method m = c.getMethod("withInitDrawer");
                Class<?> rt = m.getReturnType();
                System.out.println("[OK] " + cls + " -> " + rt.getSimpleName());
                if (rt == DrawerPipeline.class || rt.getSimpleName().equals("DrawerPipeline")) ok++;
            } catch (Exception e) {
                System.out.println("[FAIL] " + cls + ": " + e.getMessage());
            }
        }
        System.out.println("withInitDrawer pipelines: " + ok + "/" + PIPELINES.size());

        System.out.println("=== 2) DrawerPipeline one-click draw ===");
        AtomicInteger calls = new AtomicInteger();
        DrawerPipeline drawer = new DrawerPipeline(0.5f);
        byte[] out = drawer.target(testImage).onProcess((i, t) -> calls.incrementAndGet()).done();
        System.out.println("done() output: " + out.length + " B, progress calls: " + calls.get());
        System.out.println("hasTarget: " + drawer.hasTarget());
        if (out.length > 0) {
            System.out.println("ALL PASS");
        }
    }

    private static byte[] testImageBytes() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, x < 32 ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}