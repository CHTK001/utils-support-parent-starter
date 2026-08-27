package com.chua.deeplearning.support.onnx.text.qwen;

import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qwen2-0.5B KV cache 推理测试：验证 DJL tokenizer 修复后的正常中文输出、
 * 采样（无重复循环）与 GPU（CUDA EP）KV cache 推理链路。
 *
 * <p>需要本地已下载模型（缓存 %TEMP%\chua-dl-models\download\qwen2-0.5b-onnx\model.onnx）。
 * 默认跳过；显式执行：</p>
 * <pre>
 *   mvn test -Dtest=QwenGpuTest -Dqwen.it=true            (CPU)
 *   mvn test -Dtest=QwenGpuTest -Dqwen.it=true -Pgpu     (GPU，需 CUDA 12 运行时)
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
class QwenGpuTest {

    static {
        ModelRegistry.discoverAll();
    }

    /** CPU 推理（KV cache） + 采样无重复 + DJL tokenizer 正常中文输出验证。 */
    @Test
    void cpuInferenceNoDegenerateLoop() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("qwen.it"), "未开启 -Dqwen.it=true，跳过");
        try (OnnxQwenTranslator t = new OnnxQwenTranslator("qwen2-1.5b-onnx", false, 0.7f, 1.2f, 40)) {
            String reply = t.chat("用一句话介绍你自己");
            assertNotNull(reply);
            assertFalse(reply.isBlank(), "回复不应为空");
            assertFalse(hasLongRepeat(reply), "回复不应陷入重复循环: " + reply);
        }
    }

    /** GPU 推理（CUDA EP + KV cache）。 */
    @Test
    void gpuInferenceNoDegenerateLoop() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("qwen.it"), "未开启 -Dqwen.it=true，跳过");
        Assumptions.assumeTrue(cudaProviderAvailable(), "当前 classpath 无 CUDA provider（需 -Pgpu 引入 onnxruntime_gpu），跳过 GPU 测试");
        try (OnnxQwenTranslator t = new OnnxQwenTranslator("qwen2-1.5b-onnx", true, 0.7f, 1.2f, 40)) {
            String reply = t.chat("用一句话介绍你自己");
            assertNotNull(reply);
            assertFalse(reply.isBlank(), "回复不应为空");
            assertFalse(hasLongRepeat(reply), "回复不应陷入重复循环: " + reply);
        }
    }

    /** KV cache 生成应有合理长度（非空且未异常膨胀）。 */
    @Test
    void samplingProducesReasonableLength() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("qwen.it"), "未开启 -Dqwen.it=true，跳过");
        try (OnnxQwenTranslator t = new OnnxQwenTranslator("qwen2-1.5b-onnx", false, 0.7f, 1.2f, 40)) {
            String reply = t.chat("请介绍自己");
            assertNotNull(reply);
            assertTrue(reply.length() > 0, "回复长度应大于 0");
            assertTrue(reply.length() < 2000, "回复长度不应异常膨胀（退化循环），实际=" + reply.length());
        }
    }

    private static boolean cudaProviderAvailable() {
        try {
            for (ai.onnxruntime.OrtProvider p :
                    ai.onnxruntime.OrtEnvironment.getEnvironment().getAvailableProviders()) {
                if (p.name().toUpperCase().contains("CUDA")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 检测回复文本中是否出现明显重复片段（≥4 次连续重复的 n-gram）。 */
    private static boolean hasLongRepeat(String text) {
        String t = text == null ? "" : text;
        if (t.length() < 12) {
            return false;
        }
        for (int n = 4; n <= 10; n++) {
            for (int i = 0; i + n * 4 <= t.length(); i++) {
                String gram = t.substring(i, i + n);
                int count = 1;
                int j = i + n;
                while (j + n <= t.length() && t.startsWith(gram, j)) {
                    count++;
                    j += n;
                }
                if (count >= 4) {
                    return true;
                }
            }
        }
        return false;
    }
}