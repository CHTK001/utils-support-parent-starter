package com.chua.deeplearning.support.onnx.generation;

import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small SD 独立运行器：脱离 Maven/Surefire 直接执行全流程，便于定位环境问题。
 *
 * <pre>
 * 用法：java -cp "target\test-classes;target\classes;&lt;依赖classpath&gt;" com.chua.deeplearning.support.onnx.generation.SmallSdRun [提示词]
 * </pre>
 */
public final class SmallSdRun {

    private SmallSdRun() {
    }

    /**
     * 入口。
     *
     * @param args args[0] 可选提示词
     * @throws Exception 失败
     */
    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        String prompt = args.length > 0 ? args[0] : "a corgi running on grass, sunny day, photo";
        var translator = AbstractIdentificationEngine.getInstance()
                .get("small-stable-diffusion-combined", ITranslator.class);
        Object result = translator.translate(prompt);
        Path out = Path.of("out.png");
        Files.write(out, (byte[]) result);
        var img = ImageIO.read(out.toFile());
        System.out.printf("[RUN] 完成: %dx%d, 耗时 %.1fs, 输出 %s%n",
                img.getWidth(), img.getHeight(), (System.currentTimeMillis() - start) / 1000.0,
                out.toAbsolutePath());
    }
}
