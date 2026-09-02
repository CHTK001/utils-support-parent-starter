package com.chua.deeplearning.support.onnx.classification;

import com.chua.deeplearning.support.engine.ModelRegistry;

import java.util.Collections;
import java.util.Map;

/**
 * SiglipZeroShotClassificationTranslator 功能测试。
 *
 * @author CH
 * @since 4.0.0.47
 */
public final class SiglipZeroShotClassificationTest {

    private SiglipZeroShotClassificationTest() {
    }

    static {
        try {
            Class.forName("com.chua.deeplearning.support.onnx.OnnxModelRegistrar");
        } catch (ClassNotFoundException ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== SiglipZeroShotClassificationTranslator Test =====");
        System.out.println();

        int passed = 0;
        int failed = 0;

        // 测试 1: 构造函数 + candidates 设置
        System.out.println("[测试 1] 构造函数 candidates 设置");
        try {
            var translator = new SiglipZeroShotClassificationTranslator(
                    Map.of("candidates", "apple,banana,orange"));
            System.out.println("  ✅ 通过 - 构造函数执行成功");
            passed++;
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 测试 2: 默认构造函数
        System.out.println("[测试 2] 默认构造函数");
        try {
            var translator = new SiglipZeroShotClassificationTranslator();
            System.out.println("  ✅ 通过 - 默认构造函数执行成功");
            passed++;
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 测试 3: 模型注册
        System.out.println("[测试 3] siglip-zero-shot-classification 已注册");
        try {
            var entry = ModelRegistry.get("siglip-zero-shot-classification");
            if (entry != null) {
                System.out.println("  ✅ 通过 - 模型已注册");
                passed++;
            } else {
                System.out.println("  ❌ 失败 - 模型未注册");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 汇总
        System.out.println("===== 测试汇总 =====");
        System.out.println("  通过: " + passed);
        System.out.println("  失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
