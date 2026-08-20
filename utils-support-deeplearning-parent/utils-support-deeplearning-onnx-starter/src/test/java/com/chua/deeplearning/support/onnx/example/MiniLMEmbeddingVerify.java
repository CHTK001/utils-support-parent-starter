package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.embedding.EmbeddingClient;

/**
 * MiniLM 文本嵌入 int8 / fp32 双版本对比验证。
 *
 * <p>验证要点：</p>
 * <ul>
 *   <li>两个版本均输出 384 维、L2 归一化向量（模长 ≈ 1.0）</li>
 *   <li>语义一致性：相似文本余弦相似度 → 高；不同文本 → 低</li>
 *   <li>int8 与 fp32 输出向量余弦相似度 → 高（量化保真度）</li>
 * </ul>
 *
 * <p>用法：{@code mvn -o exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=com.chua.deeplearning.support.onnx.example.MiniLMEmbeddingVerify}</p>
 *
 * @since 4.0.0.42
 */
public final class MiniLMEmbeddingVerify {

    private MiniLMEmbeddingVerify() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        boolean pass = true;
        pass &= check("int8", "minilm", "minilm-int8");
        pass &= check("fp32", "minilm", "minilm-fp32");
        pass &= similarityCheck();
        if (pass) {
            System.out.println("[MiniLMEmbeddingVerify] ALL PASS");
        } else {
            System.out.println("[MiniLMEmbeddingVerify] FAIL");
            System.exit(1);
        }
    }

    private static boolean check(String label, String provider, String model) {
        try (EmbeddingClient client = EmbeddingClient.create(provider, "")) {
            client.model(model);
            float[] v1 = client.embedding("hello world");
            float[] v2 = client.embedding("hello world");
            boolean ok = v1 != null && v1.length == 384
                    && v2 != null && v2.length == 384;
            double norm = norm(v1);
            ok = ok && Math.abs(norm - 1.0) < 1e-3;
            System.out.printf("[%s] model=%s dim=%d norm=%.4f equal(重复输入)=%s%n",
                    label, model, v1 == null ? 0 : v1.length, norm, ok && near(v1, v2));
            return ok && near(v1, v2);
        } catch (Exception e) {
            System.out.println("[" + label + "] FAIL: " + e.getMessage());
            return false;
        }
    }

    private static boolean similarityCheck() {
        try (EmbeddingClient int8 = EmbeddingClient.create("minilm", "");
             EmbeddingClient fp32 = EmbeddingClient.create("minilm", "")) {
            int8.model("minilm-int8");
            fp32.model("minilm-fp32");
            float[] a1 = int8.embedding("how to learn programming");
            float[] a2 = int8.embedding("how to study coding");
            float[] b1 = fp32.embedding("how to learn programming");
            float[] b2 = int8.embedding("banana bread recipe");

            double sameInt8 = cosine(a1, a2);
            double diffInt8 = cosine(a1, b2);
            double cross = cosine(a1, b1);

            boolean ok = sameInt8 > 0.55 && diffInt8 < 0.6 && cross > 0.99;
            System.out.printf("[similarity] int8相似=%s int8不相似=%s int8/fp32交叉=%s%n",
                    String.format("%.4f", sameInt8), String.format("%.4f", diffInt8),
                    String.format("%.4f", cross));
            return ok;
        } catch (Exception e) {
            System.out.println("[similarity] FAIL: " + e.getMessage());
            return false;
        }
    }

    private static double norm(float[] v) {
        double s = 0;
        for (float f : v) {
            s += f * f;
        }
        return Math.sqrt(s);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }

    private static boolean near(float[] a, float[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-4) {
                return false;
            }
        }
        return true;
    }
}
