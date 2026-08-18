package com.chua.deeplearning.support.onnx.style;

import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风格迁移管线 — 基于 common-starter Pipeline 框架。
 *
 * <p>将 9 种风格迁移模型（5 种 Fast Neural Style + 4 种 AnimeGANv2）编排为流水线节点，
 * 输入一张图片，依次经过各风格节点处理，输出每种风格的迁移结果。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * // 1. 全部 9 种风格
 * StyleTransferPipeline pipeline = StyleTransferPipelineBuilder.newBuilder()
 *         .allStyles()
 *         .build();
 * Map<String, BufferedImage> results = pipeline.process(inputImage);
 *
 * // 2. 仅 Fast Neural Style
 * StyleTransferPipeline pipeline = StyleTransferPipelineBuilder.newBuilder()
 *         .allFastNeuralStyles()
 *         .build();
 *
 * // 3. 自定义选择风格
 * StyleTransferPipeline pipeline = StyleTransferPipelineBuilder.newBuilder()
 *         .style("style-candy")
 *         .style("style-mosaic")
 *         .animeGan("anime-gan-v2-hayao")
 *         .build();
 *
 * // 4. 处理并保存到目录
 * pipeline.processAndSave(inputImage, Path.of("output/"));
 * }</pre>
 *
 * @author CH
 * @version 4.0.0.42
 * @since 2026/8/15
 */
@Slf4j
public class StyleTransferPipeline {

    /**
     * 底层流水线引擎
     */
    private final Pipeline pipeline;

    /**
     * 管线包含的风格名称（有序）
     */
    private final List<String> styleNames;

    /**
     * 包内可见构造，通过 {@link StyleTransferPipelineBuilder#build()} 创建。
     */
    StyleTransferPipeline(Pipeline pipeline, List<String> styleNames) {
        this.pipeline = pipeline;
        this.styleNames = Collections.unmodifiableList(styleNames);
    }

    /**
     * 创建管线构建器。
     *
     * @return StyleTransferPipelineBuilder
     */
    public static StyleTransferPipelineBuilder newBuilder() {
        return StyleTransferPipelineBuilder.newBuilder();
    }

    /**
     * 快捷方式：创建包含全部 9 种风格的管线。
     *
     * @return StyleTransferPipeline
     */
    public static StyleTransferPipeline createAll() {
        return newBuilder().allStyles().build();
    }

    /**
     * 快捷方式：创建仅包含 Fast Neural Style 的管线。
     *
     * @return StyleTransferPipeline
     */
    public static StyleTransferPipeline createFastNeural() {
        return newBuilder().allFastNeuralStyles().build();
    }

    /**
     * 快捷方式：创建仅包含 AnimeGANv2 的管线。
     *
     * @return StyleTransferPipeline
     */
    public static StyleTransferPipeline createAnimeGan() {
        return newBuilder().allAnimeGanStyles().build();
    }

    // ==================== 执行 API ====================

    /**
     * 执行管线：输入图片 → 各风格节点处理 → 返回结果映射。
     *
     * <p>每个风格节点从 {@link PipelineContext#getOriginalData()} 取原始输入，
     * 应用对应滤镜后，将结果存入 {@code ctx.setAttribute(styleName, outputImage)}。</p>
     *
     * @param input 输入图像
     * @return 风格名称 → 输出图像的映射（有序）
     */
    public Map<String, BufferedImage> process(BufferedImage input) {
        if (input == null) {
            throw new IllegalArgumentException("输入图像不能为空");
        }

        PipelineContext<BufferedImage> ctx = pipeline.execute(input);

        Map<String, BufferedImage> results = new LinkedHashMap<>();
        for (String name : styleNames) {
            BufferedImage output = ctx.getAttribute(name);
            if (output != null) {
                results.put(name, output);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("风格迁移管线执行完成: 输入 {}x{}, 输出 {} 种风格",
                    input.getWidth(), input.getHeight(), results.size());
        }

        return results;
    }

    /**
     * 执行管线并保存结果到指定目录。
     *
     * <p>文件命名规则：风格名称中的 {@code -} 替换为 {@code _}，扩展名 {@code .png}。
     * 例如 {@code style-candy} → {@code style_candy.png}。</p>
     *
     * @param input     输入图像
     * @param outputDir 输出目录（自动创建）
     * @return 风格名称 → 保存路径的映射
     * @throws IOException 保存失败
     */
    public Map<String, Path> processAndSave(BufferedImage input, Path outputDir) throws IOException {
        Map<String, BufferedImage> results = process(input);
        Files.createDirectories(outputDir);

        Map<String, Path> saved = new LinkedHashMap<>();
        for (Map.Entry<String, BufferedImage> entry : results.entrySet()) {
            String fileName = entry.getKey().replace("-", "_") + ".png";
            Path outPath = outputDir.resolve(fileName);
            Files.write(outPath, ImageUtils.encode(ImageUtils.toMat(entry.getValue()), "png"));
            saved.put(entry.getKey(), outPath);

            if (log.isDebugEnabled()) {
                log.debug("已保存: {} → {}", entry.getKey(), outPath);
            }
        }

        return saved;
    }

    /**
     * 获取管线包含的风格名称列表。
     *
     * @return 不可变风格名称列表
     */
    public List<String> getStyleNames() {
        return styleNames;
    }

    /**
     * 打印管线拓扑结构。
     */
    public void printTree() {
        pipeline.printTree();
    }
}