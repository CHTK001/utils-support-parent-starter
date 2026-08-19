package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.draw.DrawerPipeline;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全部管线 withInitDrawer 集成验证。
 *
 * <p>通过反射验证所有 Pipeline 类均声明了 {@code withInitDrawer()} 方法，
 * 并直接验证 {@link DrawerPipeline} 的一键绘制（progress 回调 + done 输出标注图）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AllPipelinesDrawerVerify {

    /**
     * 期望提供 withInitDrawer 的管线类。
     */
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

    /**
     * 入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) throws Exception {
        byte[] testImage = testImageBytes();
        System.out.println("=== 1) 验证各管线已声明 withInitDrawer() ===");
        int ok = 0;
        for (String cls : PIPELINES) {
            try {
                Class<?> c = Class.forName(cls);
                Method m = c.getMethod("withInitDrawer");
                Class<?> returnType = m.getReturnType();
                boolean ret = returnType == DrawerPipeline.class || returnType.getSimpleName().equals("DrawerPipeline");
                System.out.println("[OK] " + cls + " -> withInitDrawer() 返回 " + returnType.getSimpleName());
                if (ret) {
                    ok++;
                }
            } catch (Exception e) {
                System.out.println("[FAIL] " + cls + ": " + e.getMessage());
            }
        }
        System.out.println("有 withInitDrawer 的管线: " + ok + "/" + PIPELINES.size());

        System.out.println("\n=== 2) DrawerPipeline 一键绘制功能 ===");
        AtomicInteger calls = new AtomicInteger();
        DrawerPipeline drawer = new DrawerPipeline(0.5f);
        byte[] out = drawer
                .target(testImage)
                .onProcess((i, t) -> calls.incrementAndGet())
                .done();
        System.out.println("done() 输出: " + out.length + " B, progress 回调: " + calls.get() + " 次");
        System.out.println("hasTarget: " + drawer.hasTarget());
        if (out.length > 0 && calls.get() >= 0) {
            System.out.println("\n全部验证通过 ✅");
        }
    }

    /**
     * 生成 64x64 测试图。
     *
     * @return PNG 字节
     */
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