package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistry;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ONNX 模型标识真实性冒烟门。
 *
 * <p>本模块的调用契约是「模型标识字符串 → ModelRegistry 条目 → Translator」，
 * 三处任一脱节都会让功能整条不可用，而编译期完全看不出来：</p>
 * <ul>
 *   <li>包装器（{@code OnnxXxx}）的默认模型标识必须是注册表里真实存在的键，
 *       否则调用方不显式 {@code .model("...")} 时运行时抛「模型未注册」</li>
 *   <li>派生标识（车牌 检测↔识别、OCR 检测↔识别）必须由同一个前缀推导得出，
 *       前缀不一致时永远推不出已注册的键</li>
 *   <li>Translator 类只有被 {@code reg(...)} 登记才能被引擎取到，
 *       只写类不登记等于功能不存在</li>
 * </ul>
 *
 * <p>不加载任何模型权重、不访问网络：只校验注册表与包装器默认值的一致性。
 * 孤儿 Translator 只观测不判负（部分由引擎直接构造，属另一条链路）。</p>
 *
 * <p>运行方式：{@code java com.chua.deeplearning.support.onnx.OnnxModelIdHonestySmokeTest [classes目录]}，
 * 任一校验失败输出 FAIL 并以退出码 1 结束。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OnnxModelIdHonestySmokeTest {

    /**
     * 包装器所在包名
     */
    private static final String PACKAGE = "com.chua.deeplearning.support.onnx";

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * 包装器 -> 真正交给 ModelRegistry 查询的取模型方法名（多个用逗号分隔）。
     *
     * <p>车牌与 OCR 的 {@code resolveModel()} 只是推导种子，真正被查询的是派生方法，
     * 因此这两项不校验 resolveModel()。</p>
     */
    private static final String[][] WRAPPER_MODEL_METHODS = {
            {"OnnxActionDetector", "resolveModel"},
            {"OnnxDepthEstimator", "resolveModel"},
            {"OnnxEyeDetector", "resolveModel"},
            {"OnnxFaceDetector", "resolveModel"},
            {"OnnxFaceRecognizer", "resolveModel"},
            {"OnnxFeatureExtractor", "resolveModel"},
            {"OnnxImageCaptioning", "resolveModel"},
            {"OnnxImageClarityDetector", "resolveModel"},
            {"OnnxImageClassifier", "resolveModel"},
            {"OnnxImageDetector", "resolveModel"},
            {"OnnxImageEnhancer", "resolveModel"},
            {"OnnxImageQualityAssessor", "resolveModel"},
            {"OnnxImageSegmenter", "resolveModel"},
            {"OnnxLayoutDetector", "resolveModel"},
            {"OnnxLicensePlateRecognizer", "plateDetectModel,plateRecModel"},
            {"OnnxLivenessDetector", "resolveModel"},
            {"OnnxMattingService", "resolveModel"},
            {"OnnxOcrRecognizer", "detectorModel,recognizerModel"},
            {"OnnxPedestrianDetector", "resolveModel"},
            {"OnnxPoseEstimator", "resolveModel"},
            {"OnnxSpeechEnhancer", "resolveModel"},
            {"OnnxSpeechSynthesizer", "resolveModel"},
            {"OnnxTextTranslator", "resolveModel"},
    };

    /**
     * 锚点模型标识：本批次修复的默认值与补登记的 Translator，缺任一即回归。
     */
    private static final String[] ANCHOR_MODEL_IDS = {
            "moat-tagger-v2",
            "cl-tagger",
            "doc-layout-yolo",
            "opencv-face",
            "yolov5-plate-detect",
            "yolov5-plate-recognize",
            "yolov8n-ppe",
            "yolov8n-barcode",
            "yolov8n-fire-smoke",
            "yolov8n-seal-detection",
            "yolov8n-table-detection",
    };

    /**
     * 必须以提供者 {@code "onnx"} 可实例化的扩展点接口全限定名。
     */
    private static final String[] REQUIRED_PROVIDERS = {
            "com.chua.deeplearning.support.audio.AudioFingerprinter",
            "com.chua.deeplearning.support.audio.SpeakerDiarizer",
    };

    /**
     * main。
     *
     * @param args 参数，args[0] 可选，为被检 classes 目录，默认 target/classes
     */
    public static void main(String[] args) {
        try {
            Class.forName(OnnxModelRegistrar.class.getName());
        } catch (Throwable e) {
            System.out.println("[FAIL] 注册器静态初始化失败: " + e);
            System.out.println("结果: PASS=0, FAIL=1");
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("OBSERVE registrySize=" + ModelRegistry.getAll().size());

        for (String id : ANCHOR_MODEL_IDS) {
            check(ModelRegistry.get(id) != null, "注册表存在模型标识 " + id);
        }

        for (String[] entry : WRAPPER_MODEL_METHODS) {
            checkWrapper(entry[0], entry[1]);
        }

        String classesDir = args.length > 0 ? args[0] : "target/classes";
        checkAudioProviderUsable();
        checkExtensionFilesNotEmpty(classesDir);
        observeOrphanTranslators(classesDir);

        System.out.println("========================================");
        System.out.println("OnnxModelIdHonestySmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 校验某个包装器默认（或派生）的模型标识确实可被注册表解析。
     *
     * @param simpleName 包装器类简名，不允许为 null
     * @param methodSpec 逗号分隔的取模型方法名，不允许为 null
     */
    private static void checkWrapper(String simpleName, String methodSpec) {
        String fqcn = PACKAGE + "." + simpleName;
        try {
            Class<?> clazz = Class.forName(fqcn);
            Object wrapper = clazz.getDeclaredConstructor(String.class).newInstance("");
            for (String methodName : methodSpec.split(",")) {
                Method method = clazz.getDeclaredMethod(methodName);
                method.setAccessible(true);
                String modelId = (String) method.invoke(wrapper);
                check(ModelRegistry.get(modelId) != null,
                        simpleName + "." + methodName + "() 默认标识 " + modelId + " 已注册");
            }
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            check(false, simpleName + " 默认模型解析不可用: " + cause);
        }
    }

    /**
     * 校验音频两个能力的 "onnx" 提供者确实可被 SPI 取到，且未指定模型时显式抛错。
     */
    private static void checkAudioProviderUsable() {
        for (String interfaceName : REQUIRED_PROVIDERS) {
            try {
                Class<?> ability = Class.forName(interfaceName);
                Object provider = com.chua.common.support.spi.ServiceProvider.of(ability)
                        .getNewExtension("onnx", "");
                check(provider != null, "扩展点 " + interfaceName + " 的提供者 onnx 可实例化");
                if (provider == null) {
                    continue;
                }
                Method entry = firstUsableMethod(provider);
                if (entry == null) {
                    check(false, interfaceName + " 未找到字节入口方法以验证默认模型口径");
                    continue;
                }
                entry.setAccessible(true);
                try {
                    entry.invoke(provider, new byte[0]);
                    check(false, interfaceName + " 未指定模型却返回了结果: " + entry.getName());
                } catch (InvocationTargetException e) {
                    check(e.getCause() instanceof IllegalStateException,
                            interfaceName + " 未指定模型时显式抛错: " + entry.getName());
                }
            } catch (Throwable e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                check(false, "扩展点 " + interfaceName + " 校验异常: " + cause);
            }
        }
    }

    /**
     * 选取用于验证默认模型口径的入口方法（提取或分离，参数为字节数组）。
     *
     * @param provider 提供者实例，不允许为 null
     * @return 入口方法，找不到时返回 null
     */
    private static Method firstUsableMethod(Object provider) {
        for (Method method : provider.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 1 && types[0] == byte[].class
                    && (method.getName().startsWith("extract") || method.getName().startsWith("diarize"))) {
                return method;
            }
        }
        return null;
    }

    /**
     * 校验模块登记文件不为空：空文件意味着声明了扩展点却没有任何实现。
     *
     * @param classesDir 被检 classes 目录，不允许为 null
     */
    private static void checkExtensionFilesNotEmpty(String classesDir) {
        Path dir = new File(classesDir, "META-INF" + File.separator + "extensions").toPath();
        if (!Files.isDirectory(dir)) {
            check(false, "扩展点登记目录不存在: " + dir);
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                boolean hasEntry = false;
                for (String line : Files.readAllLines(file)) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        hasEntry = true;
                        break;
                    }
                }
                check(hasEntry, "扩展点登记非空 " + file.getFileName());
            }
        } catch (Exception e) {
            check(false, "扩展点登记目录扫描失败: " + e);
        }
    }

    /**
     * 观测未被任何注册项引用的 Translator 类，只输出事实不参与判定。
     *
     * @param classesDir 被检 classes 目录，不允许为 null
     */
    private static void observeOrphanTranslators(String classesDir) {
        Path root = new File(classesDir).toPath();
        if (!Files.isDirectory(root)) {
            System.out.println("[SKIP] classes 目录不存在，跳过孤儿 Translator 观测: " + classesDir);
            return;
        }
        List<String> registered = new ArrayList<>();
        for (ModelRegistry.Entry entry : ModelRegistry.getAll()) {
            if (entry.translatorClassName() != null) {
                registered.add(entry.translatorClassName());
            }
        }
        int total = 0;
        int orphan = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> classFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .toList();
            for (Path classFile : classFiles) {
                String relative = root.relativize(classFile).toString()
                        .replace(File.separatorChar, '.');
                String className = relative.substring(0, relative.length() - ".class".length());
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                if (!simpleName.endsWith("Translator") || className.indexOf('$') >= 0) {
                    continue;
                }
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, false, OnnxModelIdHonestySmokeTest.class.getClassLoader());
                } catch (Throwable e) {
                    continue;
                }
                if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                    continue;
                }
                total++;
                if (!registered.contains(className)) {
                    orphan++;
                    System.out.println("OBSERVE orphanTranslator 未登记 " + className);
                }
            }
        } catch (Exception e) {
            System.out.println("[SKIP] 孤儿 Translator 扫描失败: " + e);
            return;
        }
        System.out.println("OBSERVE orphanTranslators=" + orphan + "/" + total);
    }

    /**
     * 记录单项判定。
     *
     * @param condition 判定条件
     * @param message   说明
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}
