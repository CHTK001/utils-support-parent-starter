package com.chua.deeplearning.support.onnx.embedding.bge;

import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * bge-small-zh-v1.5 EmbeddingClient SPI 端到端测试（512 维，中文 + 英文）。
 *
 * <p>通过 {@link EmbeddingClient#create} SPI 工厂加载 bge provider（原始架构，
 * 不直接操作 ORT），单个 client 加载一次模型，复用执行全部断言。</p>
 *
 * <p>用法：
 * <pre>{@code
 *   java -cp <classpath> com.chua.deeplearning.support.onnx.embedding.bge.BgeEmbeddingClientTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BgeEmbeddingClientTest {

    /**
     * bge provider 名称（SPI 注册名）
     */
    private static final String PROVIDER_BGE = "bge";

    /**
     * 默认模型名
     */
    private static final String MODEL_BGE = "bge-small-zh-v1.5";

    /**
     * 期望嵌入维度（512）
     */
    private static final int EXPECTED_DIM = 512;

    public static void main(String[] args) {
        BgeEmbeddingClientTest runner = new BgeEmbeddingClientTest();
        System.exit(runner.runTest() ? 0 : 1);
    }

    /**
     * 单 client 加载一次模型，复用跑全部断言。
     *
     * @return 是否全部通过
     */
    public boolean runTest() {
        boolean ok = true;
        try (EmbeddingClient client = EmbeddingClient.create(PROVIDER_BGE, "")
                .model(MODEL_BGE)) {
            ok &= testEmbedding(client, "hello world", "英文");
            ok &= testEmbedding(client, "你好世界，这是中文嵌入测试", "中文");
            ok &= testBatch(client);
            ok &= testResponse(client);
            ok &= testAsync(client);
        } catch (Exception e) {
            log.error("[FAIL] bge SPI 测试异常: {}", e.getMessage(), e);
            ok = false;
        }
        log.info("========== bge-small-zh-v1.5 SPI 测试: {} ==========", ok ? "全部通过" : "有失败");
        return ok;
    }

    /**
     * 单句嵌入：校验维度与 L2 归一化。
     *
     * @param client client
     * @param text   输入文本
     * @param label  用例标签
     * @return 是否通过
     */
    private boolean testEmbedding(EmbeddingClient client, String text, String label) {
        try {
            long start = System.currentTimeMillis();
            float[] v = client.embedding(text);
            long elapsed = System.currentTimeMillis() - start;
            boolean dimOk = v != null && v.length == EXPECTED_DIM;
            boolean normOk = dimOk && Math.abs(normOf(v) - 1.0) < 0.01;
            log.info("[PASS={}] {}嵌入: dim={} norm={:.4f} {}ms",
                    dimOk && normOk, label, v == null ? -1 : v.length, normOf(v), elapsed);
            return dimOk && normOk;
        } catch (Exception e) {
            log.error("[FAIL] {}嵌入异常: {}", label, e.getMessage());
            return false;
        }
    }

    /**
     * 批量嵌入。
     *
     * @param client client
     * @return 是否通过
     */
    private boolean testBatch(EmbeddingClient client) {
        try {
            long start = System.currentTimeMillis();
            float[][] vs = client.embeddingBatch(new String[]{"文档一", "document two", "第三个样本"});
            long elapsed = System.currentTimeMillis() - start;
            boolean ok = vs != null && vs.length == 3;
            for (float[] v : vs) {
                if (v == null || v.length != EXPECTED_DIM) {
                    ok = false;
                    break;
                }
            }
            log.info("[PASS={}] 批量嵌入: count={} dim={} {}ms", ok, vs == null ? -1 : vs.length, EXPECTED_DIM, elapsed);
            return ok;
        } catch (Exception e) {
            log.error("[FAIL] 批量嵌入异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * EmbeddingResponse 包装。
     *
     * @param client client
     * @return 是否通过
     */
    private boolean testResponse(EmbeddingClient client) {
        try {
            EmbeddingResponse resp = client.embeddingWithResponse("test response");
            boolean ok = resp != null
                    && resp.embeddings() != null
                    && resp.embeddings().size() == 1
                    && resp.embeddings().get(0).dimensions() == EXPECTED_DIM
                    && resp.embeddings().get(0).vector() != null;
            log.info("[PASS={}] EmbeddingResponse: count={} dim={}",
                    ok, resp == null || resp.embeddings() == null ? -1 : resp.embeddings().size(), EXPECTED_DIM);
            return ok;
        } catch (Exception e) {
            log.error("[FAIL] EmbeddingResponse 异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 异步嵌入。
     *
     * @param client client
     * @return 是否通过
     */
    private boolean testAsync(EmbeddingClient client) {
        try {
            CompletableFuture<float[]> future = client.embeddingAsync("async test");
            float[] v = future.get();
            boolean ok = v != null && v.length == EXPECTED_DIM;
            log.info("[PASS={}] 异步嵌入: dim={}", ok, v == null ? -1 : v.length);
            return ok;
        } catch (Exception e) {
            log.error("[FAIL] 异步嵌入异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 计算向量 L2 范数。
     *
     * @param v 向量
     * @return L2 范数
     */
    private double normOf(float[] v) {
        if (v == null) {
            return 0;
        }
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        return Math.sqrt(sum);
    }
}