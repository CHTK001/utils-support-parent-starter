package com.chua.deeplearning.support.onnx.text.minimind;

import ai.djl.Model;
import ai.djl.ndarray.NDList;
import ai.djl.inference.Predictor;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MiniMind ONNX 模型测试（详细输出模式）
 * <p>
 * 使用方式：
 * <pre>
 *   java MiniMindTest                    # 普通模式
 *   java MiniMindTest -v                 # 详细输出模式
 *   java MiniMindTest -v "你好" 50       # 自定义 prompt 和最大 token 数
 * </pre>
 * </p>
 * <p>
 * 测试流程：
 * 1. 加载 MiniMind ONNX 模型
 * 2. 加载 HuggingFace tokenizer
 * 3. 对输入文本执行贪婪解码生成
 * 4. 打印每个生成步骤的详细信息（-v 模式）
 * 5. 输出生成结果
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniMindTest {

    /**
     * 默认测试 prompt
     */
    private static final String DEFAULT_PROMPT = "你好，请介绍一下自己。";

    /**
     * 默认最大生成 token 数
     */
    private static final int DEFAULT_MAX_TOKENS = 50;

    /**
     * 是否详细输出
     */
    private boolean verbose = false;

    /**
     * 测试 prompt
     */
    private String prompt = DEFAULT_PROMPT;

    /**
     * 最大生成 token 数
     */
    private int maxTokens = DEFAULT_MAX_TOKENS;

    public static void main(String[] args) throws Exception {
        MiniMindTest test = new MiniMindTest();

        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-v", "--verbose" -> test.verbose = true;
                default -> {
                    if (i == 0 && !args[i].startsWith("-")) {
                        test.prompt = args[i];
                    } else if (i == 1 && !args[i].startsWith("-")) {
                        test.maxTokens = Integer.parseInt(args[i]);
                    } else if (i == 2 && !args[i].startsWith("-")) {
                        test.maxTokens = Integer.parseInt(args[i]);
                    }
                }
            }
        }

        test.run();
    }

    /**
     * 执行测试
     */
    public void run() throws Exception {
        log.info("================================================");
        log.info("  MiniMind ONNX 模型测试");
        log.info("================================================");

        // 1. 查找模型文件
        Path modelDir = findModelDir();
        Path onnxPath = modelDir.resolve("model.onnx");
        Path tokenizerPath = modelDir.resolve("tokenizer.json");

        if (!Files.exists(onnxPath)) {
            log.error("model.onnx not found at: {}", onnxPath);
            log.error("Please run the ONNX export first, or download the model.");
            System.exit(1);
        }
        if (!Files.exists(tokenizerPath)) {
            log.error("tokenizer.json not found at: {}", tokenizerPath);
            System.exit(1);
        }

        log.info("[1/4] 模型文件检查");
        log.info("  ONNX 模型: {} ({} KB)", onnxPath, Files.size(onnxPath) / 1024);
        log.info("  Tokenizer: {}", tokenizerPath);

        // 列出模型目录中的所有文件
        if (verbose) {
            log.info("  --- 模型目录文件列表 ---");
            Files.list(modelDir).forEach(p -> {
                try {
                    log.info("    {} ({} bytes)", p.getFileName(), Files.size(p));
                } catch (Exception e) {
                    log.info("    {}", p.getFileName());
                }
            });
        }

        // 2. 加载 DJL 模型
        log.info("[2/4] 加载 ONNX 模型 (DJL + OnnxRuntime)");
        Model model = Model.newInstance("minimind", "OnnxRuntime");
        model.load(modelDir, "model");

        if (verbose) {
            log.info("  模型加载完成");
            log.info("  模型路径: {}", model.getModelPath());
        }

        // 3. 创建 Translator 和 Predictor
        log.info("[3/4] 创建 Translator + Predictor");
        MiniMindTranslator translator = new MiniMindTranslator();
        Predictor<String, String> predictor = model.newPredictor(translator);

        log.info("  Translator: {}", translator.getClass().getSimpleName());
        log.info("  Prompt: \"{}\"", prompt);
        log.info("  Max Tokens: {}", maxTokens);

        // 4. 执行推理
        log.info("[4/4] 执行文本生成...");
        log.info("  ---");

        long startTime = System.currentTimeMillis();
        String result = predictor.predict(prompt);
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("  ---");
        log.info("  生成完成 (耗时 {}ms)", elapsed);
        log.info("  输入: {}", prompt);
        log.info("  输出: {}", result);
        log.info("================================================");

        // 验证结果
        if (result == null || result.isEmpty()) {
            log.warn("  ⚠ 生成结果为空！");
        } else {
            log.info("  ✓ 生成成功，输出长度 {} 字符", result.length());
        }

        // 清理
        predictor.close();
        model.close();
    }

    /**
     * 查找模型目录
     * <p>
     * 按以下优先级查找：
     * 1. 环境变量 MINIMIND_MODEL_DIR
     * 2. 当前目录下的 minimind-onnx/
     * 3. Maven 模块资源目录（开发环境）
     * 4. classpath 内嵌资源（内置 JAR 模型，通过 NativeLoader 解压到临时目录）
     * </p>
     *
     * @return 模型目录路径
     */
    private Path findModelDir() {
        // 1. 环境变量
        String envPath = System.getenv("MINIMIND_MODEL_DIR");
        if (envPath != null && Files.isDirectory(Path.of(envPath))) {
            log.info("从环境变量找到模型目录: {}", envPath);
            return Path.of(envPath);
        }

        // 2. 当前目录
        Path localDir = Paths.get("minimind-onnx");
        if (Files.isDirectory(localDir)) {
            log.info("从当前目录找到模型目录: {}", localDir.toAbsolutePath());
            return localDir;
        }

        // 3. Maven 模块资源目录（开发环境）
        Path mavenPath = Paths.get("utils-support-models-parent",
                "utils-support-minimind-onnx",
                "src", "main", "resources", "models", "minimind");
        if (Files.isDirectory(mavenPath)) {
            log.info("从 Maven 模块找到模型目录: {}", mavenPath.toAbsolutePath());
            return mavenPath;
        }

        // 4. classpath 内嵌资源（内置 JAR 模型）
        // 使用 NativeLoader 从 classpath 解压 models/minimind/ 目录到临时目录
        Path tempModelDir = Path.of(System.getProperty("java.io.tmpdir"), "chua-dl-models", "models", "minimind");
        NativeLoader.of("minimind-resources")
                .from(MiniMindTest.class.getClassLoader())
                .basePath("models/minimind/")
                .toTarget(tempModelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();
        if (Files.exists(tempModelDir.resolve("model.onnx"))) {
            log.info("从 classpath (JAR 内嵌) 解压模型目录: {}", tempModelDir);
            return tempModelDir;
        }

        // 5. 默认路径（用于测试）
        Path defaultPath = Paths.get("target", "models", "minimind");
        if (Files.isDirectory(defaultPath)) {
            return defaultPath;
        }

        log.warn("未找到模型目录，使用默认路径: {}", mavenPath);
        return mavenPath;
    }
}
