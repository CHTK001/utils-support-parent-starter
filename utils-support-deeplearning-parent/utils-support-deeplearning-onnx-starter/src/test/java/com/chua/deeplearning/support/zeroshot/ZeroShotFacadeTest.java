package com.chua.deeplearning.support.zeroshot;

import java.util.List;
import java.util.Map;

/**
 * 零样本门面类（ZeroShot）单元测试。
 *
 * <p>验证 ZeroShot 门面类的模型列表方法和 API 一致性。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 *   java ZeroShotFacadeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ZeroShotFacadeTest {

    /** 强制加载 OnnxModelRegistrar SPI，触发模型注册 */
    static {
        try {
            Class.forName("com.chua.deeplearning.support.onnx.OnnxModelRegistrar");
        } catch (ClassNotFoundException ignored) {
            // OnnxModelRegistrar 不在 classpath 时跳过
        }
    }

    /** 创建 ZeroShotFacadeTest 实例 */
    private ZeroShotFacadeTest() {
    }

    /** Main */
    public static void main(String[] args) {
        System.out.println("===== ZeroShot 门面类单元测试 =====");
        System.out.println();

        int passed = 0;
        int failed = 0;

        // 测试 1: listClassifiers
        System.out.println("[测试 1] listClassifiers()");
        try {
            List<String> classifiers = ZeroShot.listClassifiers();
            System.out.println("  结果: " + classifiers);
            if (classifiers.contains("siglip-zero-shot-classification")) {
                System.out.println("  ✅ 通过 - 包含 siglip-zero-shot-classification");
                passed++;
            } else {
                System.out.println("  ❌ 失败 - 未包含 siglip-zero-shot-classification");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 测试 2: listDetectors
        System.out.println("[测试 2] listDetectors()");
        try {
            List<String> detectors = ZeroShot.listDetectors();
            System.out.println("  结果: " + detectors);
            if (detectors.contains("yolov8s-world")) {
                System.out.println("  ✅ 通过 - 包含 yolov8s-world");
                passed++;
            } else {
                System.out.println("  ❌ 失败 - 未包含 yolov8s-world");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 测试 3: listSegmenters
        System.out.println("[测试 3] listSegmenters()");
        try {
            List<String> segmenters = ZeroShot.listSegmenters();
            System.out.println("  结果: " + segmenters);
            if (segmenters.contains("clipseg-zero-shot")) {
                System.out.println("  ✅ 通过 - 包含 clipseg-zero-shot");
                passed++;
            } else {
                System.out.println("  ❌ 失败 - 未包含 clipseg-zero-shot");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 测试 4: listAll
        System.out.println("[测试 4] listAll()");
        try {
            Map<String, String> all = ZeroShot.listAll();
            System.out.println("  结果: " + all.size() + " 个模型");
            System.out.println("  类型分布:");
            long clsCount = all.values().stream().filter(v -> v.equals("classification")).count();
            long detCount = all.values().stream().filter(v -> v.equals("detection")).count();
            long segCount = all.values().stream().filter(v -> v.equals("segmentation")).count();
            System.out.println("    分类: " + clsCount);
            System.out.println("    检测: " + detCount);
            System.out.println("    分割: " + segCount);
            if (all.containsKey("siglip-zero-shot-classification")
                    && all.containsKey("yolov8s-world")
                    && all.containsKey("clipseg-zero-shot")) {
                System.out.println("  ✅ 通过 - 包含所有关键零样本模型");
                passed++;
            } else {
                System.out.println("  ❌ 失败 - 缺少关键零样本模型");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
            failed++;
        }
        System.out.println();

        // 测试 5: 构建器 API 一致性
        System.out.println("[测试 5] 构建器 API 一致性");
        try {
            ZeroShot.ClassifierBuilder cb = ZeroShot.classifier("siglip-zero-shot-classification");
            ZeroShot.DetectorBuilder db = ZeroShot.detector("yolov8s-world");
            ZeroShot.SegmenterBuilder sb = ZeroShot.segmenter("clipseg-zero-shot");
            if (cb != null && db != null && sb != null) {
                System.out.println("  ✅ 通过 - 所有构建器创建成功且非空");
                passed++;
            } else {
                System.out.println("  ❌ 失败 - 构建器为 null");
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
        System.out.println("  总计: " + (passed + failed));
        if (failed == 0) {
            System.out.println("  🎉 全部通过！");
        } else {
            System.out.println("  ⚠️ 有 " + failed + " 个测试失败");
        }
    }
}
