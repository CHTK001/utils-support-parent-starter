package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.chat.ModelDefinition;

import java.util.List;

/**
 * 本地 AI 能力示例公共基类。
 *
 * <p>提供模型列表打印与计时工具，各能力 Example 复用。</p>
 *
 * @since 4.0.0.42
 */
public abstract class ExampleBase {

    /**
     * 打印模型列表。
     *
     * @param capability 能力名
     * @param provider   提供商
     * @param models     模型列表
     */
    protected static void printModels(String capability, String provider, List<ModelDefinition> models) {
        System.out.println("===== [" + capability + "] provider=" + provider + " =====");
        if (models == null || models.isEmpty()) {
            System.out.println("  (无可用模型)");
            return;
        }
        for (ModelDefinition m : models) {
            System.out.println("  - " + m.getId() + (m.getDescription() != null ? " | " + m.getDescription() : ""));
        }
        System.out.println();
    }

    /**
     * 打印结果与耗时。
     *
     * @param capability 能力名
     * @param provider   提供商
     * @param model      模型
     * @param startMs    开始时间戳
     */
    protected static void printResult(String capability, String provider, String model, long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        System.out.println("[完成] " + capability + " provider=" + provider + " model=" + model + " 耗时=" + elapsed + "ms");
        System.out.println();
    }
}
