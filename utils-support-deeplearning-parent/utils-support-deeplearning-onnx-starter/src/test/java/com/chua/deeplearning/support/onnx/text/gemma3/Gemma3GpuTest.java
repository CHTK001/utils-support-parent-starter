package com.chua.deeplearning.support.onnx.text.gemma3;

import com.chua.deeplearning.support.ai.DetectionConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gemma-3-270M 推理测试：验证采样修复（无重复循环）、CPU/GPU 推理链路。
 *
 * <p>默认跳过（避免常规构建触发长时推理与 GPU 依赖）；显式执行：</p>
 * <pre>
 *   mvn test -Dtest=Gemma3GpuTest -Dgemma.it=true            (CPU)
 *   mvn test -Dtest=Gemma3GpuTest -Dgemma.it=true -Pgpu     (GPU，需 CUDA 12 运行时)
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
class Gemma3GpuTest {

    /** 生成配置：温度采样 + 重复惩罚 + top-k，用于验证无重复循环 */
    private static DetectionConfiguration samplingConfig(boolean useGpu) {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("temperature", 0.7f);
        opt.put("repeatPenalty", 1.3f);
        opt.put("topK", 50);
        return new DetectionConfiguration.DetectionConfigurationBuilder()
                .useGpu(useGpu)
                .systemOption(opt)
                .build();
    }

    /**
     * CPU 推理 + 无重复循环验证。
     */
    @Test
    void cpuInferenceNoDegenerateLoop() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gemma.it"), "未开启 -Dgemma.it=true，跳过");
        try (Gemma3Translator t = new Gemma3Translator(samplingConfig(false))) {
            String reply = t.chat("用一句话介绍你自己");
            assertNotNull(reply);
            assertFalse(reply.isBlank(), "回复不应为空");
            assertFalse(hasLongRepeat(reply), "回复不应陷入重复循环: " + reply);
        }
    }

    /**
     * GPU 推理（CUDA EP）+ 无重复循环验证。
     */
    @Test
    void gpuInferenceNoDegenerateLoop() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gemma.it"), "未开启 -Dgemma.it=true，跳过");
        try (Gemma3Translator t = new Gemma3Translator(samplingConfig(true))) {
            String reply = t.chat("用一句话介绍你自己");
            assertNotNull(reply);
            assertFalse(reply.isBlank(), "回复不应为空");
            assertFalse(hasLongRepeat(reply), "回复不应陷入重复循环: " + reply);
        }
    }

    /**
     * 采样配置下回复应有合理长度（非空且未陷入退化）。
     */
    @Test
    void samplingProducesReasonableLength() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gemma.it"), "未开启 -Dgemma.it=true，跳过");
        try (Gemma3Translator t = new Gemma3Translator(samplingConfig(false))) {
            String reply = t.chat("请介绍自己");
            assertNotNull(reply);
            assertTrue(reply.length() > 0, "回复长度应大于 0");
            assertTrue(reply.length() < 2000, "回复长度不应异常膨胀（退化循环），实际=" + reply.length());
        }
    }

    /**
     * 检测回复文本中是否出现明显重复片段（≥4 次连续重复的 n-gram）。
     */
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
