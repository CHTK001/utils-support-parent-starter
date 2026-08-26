package com.chua.deeplearning.support.onnx.generation;

import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small SD 独立出图 CLI。
 *
 * <p>所有参数均为<b>选填</b>，缺省值见 {@code printUsage}。</p>
 *
 * <pre>
 * 用法示例：
 *   java SmallSdRun -p "a corgi running on grass, sunny day"
 *   java SmallSdRun -p "赛博朋克城市" -W 512 -H 768 -s 25 -g 7.5 -S 42 -o out.png -d auto
 *   java SmallSdRun -p "..." -n "blurry, low quality"
 * </pre>
 *
 * <p>参数映射（系统属性透传给编排器）：
 * {@code small.sd.width/height/steps/guidance/seed/negative} 与
 * {@code deeplearning.device}。</p>
 */
public final class SmallSdRun {

    private SmallSdRun() {
    }

    /**
     * CLI 入口。
     *
     * @param args 选项与位置参数
     * @throws Exception 失败
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        String positionalPrompt = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-p", "--prompt" -> opt.put("prompt", value(args, ++i, a));
                case "-n", "--negative" -> opt.put("negative", value(args, ++i, a));
                case "-W", "--width" -> opt.put("width", value(args, ++i, a));
                case "-H", "--height" -> opt.put("height", value(args, ++i, a));
                case "-s", "--steps" -> opt.put("steps", value(args, ++i, a));
                case "-g", "--guidance" -> opt.put("guidance", value(args, ++i, a));
                case "-S", "--seed" -> opt.put("seed", value(args, ++i, a));
                case "-o", "--out" -> opt.put("out", value(args, ++i, a));
                case "-d", "--device" -> opt.put("device", value(args, ++i, a));
                case "-h", "--help" -> printUsage();
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("未知选项: " + a + "（--help 查看用法）");
                    }
                    positionalPrompt = a;
                }
            }
        }
        String prompt = opt.getOrDefault("prompt", positionalPrompt);
        if (prompt == null || prompt.isBlank()) {
            printUsage();
            throw new IllegalArgumentException("缺少提示词（-p 或位置参数）");
        }

        // 透传给编排器（构造时读取系统属性）
        if (opt.containsKey("width")) {
            System.setProperty("small.sd.width", opt.get("width"));
        }
        if (opt.containsKey("height")) {
            System.setProperty("small.sd.height", opt.get("height"));
        }
        if (opt.containsKey("steps")) {
            System.setProperty("small.sd.steps", opt.get("steps"));
        }
        if (opt.containsKey("guidance")) {
            System.setProperty("small.sd.guidance", opt.get("guidance"));
        }
        if (opt.containsKey("seed")) {
            System.setProperty("small.sd.seed", opt.get("seed"));
        }
        if (opt.containsKey("negative")) {
            System.setProperty("small.sd.negative", opt.get("negative"));
        }
        if (opt.containsKey("device")) {
            System.setProperty("deeplearning.device", opt.get("device"));
        }

        long start = System.currentTimeMillis();
        ITranslator<Object, Object> translator = AbstractIdentificationEngine.getInstance()
                .get("small-stable-diffusion-combined", ITranslator.class);
        if (translator == null) {
            System.err.println("translator is null! registered models: "
                    + com.chua.deeplearning.support.engine.ModelRegistry.getAll().stream()
                    .map(e -> e.modelId()).toList());
            return;
        }
        Object result = translator.translate(prompt);

        Path out = Path.of(opt.getOrDefault("out", "out.png"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, (byte[]) result);
        var img = ImageIO.read(out.toFile());
        System.out.printf("[RUN] 完成: %dx%d, 耗时 %.1fs, 输出 %s%n",
                img.getWidth(), img.getHeight(), (System.currentTimeMillis() - start) / 1000.0,
                out.toAbsolutePath());
    }

    /**
     * 读取选项值。
     *
     * @param args 参数数组
     * @param i    值下标
     * @param flag 选项名（用于报错）
     * @return 值
     */
    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少值");
        }
        return args[i];
    }

    /**
     * 打印用法。
     */
    private static void printUsage() {
        System.out.println("""
                Small SD 出图 CLI（所有参数选填）
                用法: java SmallSdRun [选项] [提示词]
                  -p,  --prompt <text>    提示词（或直接写位置参数）
                  -n,  --negative <text>  负面提示词（默认无）
                  -W,  --width <int>      宽度（默认 512，8 的倍数）
                  -H,  --height <int>     高度（默认 512）
                  -s,  --steps <int>      去噪步数（默认 20，越大越精细越慢）
                  -g,  --guidance <float> CFG 引导（默认 7.5）
                  -S,  --seed <long>      随机种子（默认随机；固定可复现）
                  -o,  --out <path>       输出 PNG 路径（默认 out.png）
                  -d,  --device <mode>    auto / cpu / gpu（默认 auto）
                  -h,  --help             本说明
                示例:
                  java SmallSdRun -p "a corgi, sunny day" -S 42 -o dog.png
                  java SmallSdRun "赛博朋克城市夜景" -W 512 -H 768 -s 25""");
    }
}
