package com.chua.deeplearning.support.onnx.example;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import com.chua.deeplearning.support.engine.ModelRegistry;

import java.nio.file.Path;

/**
 * DINOv2-small fp16 与 fp32 视觉特征验证。
 *
 * <p>验证要点：</p>
 * <ul>
 *   <li>fp16 与 fp32 均输出 384 维 CLS 特征向量（DINOv2 ViT-S/14）</li>
 *   <li>fp16 与 fp32 特征余弦相似度 → 高（半精度保真度）</li>
 * </ul>
 *
 * <p>用法：{@code mvn -o exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=com.chua.deeplearning.support.onnx.example.DinoV2Fp16Verify}</p>
 *
 * @since 4.0.0.42
 */
public final class DinoV2Fp16Verify {

    private DinoV2Fp16Verify() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        String imagePath = args.length > 0 ? args[0] : "D:/images/1ai.png";
        Image image = ImageFactory.getInstance().fromFile(Path.of(imagePath));

        float[] fp32 = embed("dino-v2-small-embedding", image);
        float[] fp16 = embed("dino-v2-small-embedding-fp16", image);

        boolean ok = fp32 != null && fp32.length == 384
                && fp16 != null && fp16.length == 384;
        double cos = cosine(fp32, fp16);
        ok = ok && cos > 0.98;

        System.out.println("[fp32] dim=" + (fp32 == null ? 0 : fp32.length)
                + " head=" + head(fp32));
        System.out.println("[fp16] dim=" + (fp16 == null ? 0 : fp16.length)
                + " head=" + head(fp16));
        System.out.printf("[similarity] fp32/fp16 余弦=%s%n", String.format("%.4f", cos));
        System.out.println(ok ? "[DinoV2Fp16Verify] ALL PASS" : "[DinoV2Fp16Verify] FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static float[] embed(String modelId, Image image) {
        var translator = ModelRegistry.createTranslator(modelId, null);
        try {
            Object out = translator.translate(image);
            return (float[]) out;
        } catch (Exception e) {
            System.out.println("[" + modelId + "] FAIL: " + e.getMessage());
            return null;
        } finally {
            if (translator instanceof AutoCloseable ac) {
                try {
                    ac.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(float[] v) {
        double s = 0;
        for (float f : v) {
            s += f * f;
        }
        return Math.sqrt(s);
    }

    private static String head(float[] v) {
        if (v == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(5, v.length); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%.4f", v[i]));
        }
        return sb.append("]").toString();
    }
}
