package com.chua.deeplearning.support.onnx.generation;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small SD 全流程冒烟测试。
 *
 * <p>默认跳过（避免常规构建触发 3GB 权重下载与长时推理）；
 * 显式执行：{@code mvn test -Dtest=SmallSdSmokeTest -Dsmall.sd.it=true}。</p>
 *
 * <p>首次运行自动从 HuggingFace（自动回退 hf-mirror.com）下载权重到
 * {@code %TEMP%/chua-dl-models/download/small-stable-diffusion-combined/}。</p>
 */
class SmallSdSmokeTest {

    /**
     * 文生图一次并落盘 out.png。
     *
     * @throws Exception 推理失败
     */
    @Test
    void generateOnce() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("small.sd.it"), "未开启 -Dsmall.sd.it=true，跳过");

        long start = System.currentTimeMillis();
        var engine = com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance();
        var translator = engine.get("small-stable-diffusion-combined",
                com.chua.deeplearning.support.translator.ITranslator.class);
        Object result = translator.translate("a corgi running on grass, sunny day, photo");
        long cost = (System.currentTimeMillis() - start) / 1000;

        org.junit.jupiter.api.Assertions.assertNotNull(result);
        Path out = Path.of("out.png");
        Files.write(out, (byte[]) result);
        var img = ImageIO.read(out.toFile());
        System.out.printf("[SMOKE] 出图完成: %dx%d, 耗时 %ds, 输出 %s%n",
                img.getWidth(), img.getHeight(), cost, out.toAbsolutePath());
    }
}
