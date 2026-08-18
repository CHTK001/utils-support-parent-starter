package com.chua.deeplearning.support.onnx.style;

import com.chua.common.support.image.filter.ImageFilter;
import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.onnx.animegan.AnimeGanImageFilter;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 风格迁移管线构建器。
 *
 * <p>链式添加风格节点，最终 {@link #build()} 构建管线。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * // 全部 9 种风格
 * StyleTransferPipeline pipeline = StyleTransferPipelineBuilder.newBuilder()
 *         .allStyles()
 *         .build();
 *
 * // 自定义选择风格
 * StyleTransferPipeline pipeline = StyleTransferPipelineBuilder.newBuilder()
 *         .style("style-candy")
 *         .animeGan("anime-gan-v2-hayao")
 *         .build();
 * }</pre>
 *
 * @author CH
 * @version 4.0.0.42
 * @since 2026/8/15
 */
@Slf4j
public class StyleTransferPipelineBuilder {

    /**
     * Fast Neural Style Transfer 支持的 5 种风格
     */
    private static final String[] FAST_NEURAL_STYLES = {
            "style-candy", "style-mosaic", "style-rain-princess", "style-udnie", "style-pointilism"
    };

    /**
     * AnimeGANv2 支持的 4 种动漫风格
     */
    private static final String[] ANIME_GAN_STYLES = {
            "anime-gan-v2-hayao", "anime-gan-v2-shinkai", "anime-gan-v2-paprika", "anime-gan-v2-face-portrait"
    };

    /** 风格名称列表 */
    /** Stylenames */
    private final List<String> styleNames = new ArrayList<>();

    /**
     * 创建构建器实例。
     *
     * @return StyleTransferPipelineBuilder
     */
    public static StyleTransferPipelineBuilder newBuilder() {
        return new StyleTransferPipelineBuilder();
    }

    /**
     * 添加一个 Fast Neural Style 风格节点。
     *
     * @param modelName 模型名称（如 style-candy, style-mosaic 等）
     * @return this
     */
    public StyleTransferPipelineBuilder style(String modelName) {
        styleNames.add(modelName);
        return this;
    }

    /**
     * 添加一个 AnimeGANv2 风格节点。
     *
     * @param modelName 模型名称（如 anime-gan-v2-hayao 等）
     * @return this
     */
    public StyleTransferPipelineBuilder animeGan(String modelName) {
        styleNames.add(modelName);
        return this;
    }

    /**
     * 添加全部 5 种 Fast Neural Style 风格节点。
     *
     * @return this
     */
    public StyleTransferPipelineBuilder allFastNeuralStyles() {
        for (String name : FAST_NEURAL_STYLES) {
            styleNames.add(name);
        }
        return this;
    }

    /**
     * 添加全部 4 种 AnimeGANv2 风格节点。
     *
     * @return this
     */
    public StyleTransferPipelineBuilder allAnimeGanStyles() {
        for (String name : ANIME_GAN_STYLES) {
            styleNames.add(name);
        }
        return this;
    }

    /**
     * 添加全部 9 种风格节点（5 种 Fast Neural Style + 4 种 AnimeGANv2）。
     *
     * @return this
     */
    public StyleTransferPipelineBuilder allStyles() {
        allFastNeuralStyles();
        allAnimeGanStyles();
        return this;
    }

    /**
     * 构建风格迁移管线。
     *
     * <p>内部使用 {@link PipelineBuilder} 将每个风格创建为一个 {@code task} 节点，
     * 节点逻辑：取原始输入 → 创建 Filter → converter() → 存入 context 属性。</p>
     *
     * @return StyleTransferPipeline
     * @throws IllegalStateException 未添加任何风格时抛出
     */
    public StyleTransferPipeline build() {
        if (styleNames.isEmpty()) {
            throw new IllegalStateException("至少需要添加一个风格节点");
        }

        PipelineBuilder pb = PipelineBuilder.newBuilder("style-transfer");

        for (String modelName : styleNames) {
            final String name = modelName;
            pb.task(name, ctx -> {
                try {
                    @SuppressWarnings("unchecked")
                    BufferedImage input = (BufferedImage) ctx.getOriginalData();
                    ImageFilter filter = createFilter(name);
                    BufferedImage output = filter.converter(input);
                    ctx.setAttribute(name, output);

                    if (log.isDebugEnabled()) {
                        log.debug("风格迁移节点完成: {} ({}x{} → {}x{})",
                                name, input.getWidth(), input.getHeight(),
                                output.getWidth(), output.getHeight());
                    }
                } catch (Exception e) {
                    log.error("风格迁移节点失败: {}", name, e);
                    ctx.setAttribute(name + ":error", e.getMessage());
                }
                return null;
            }).taskEnd();
        }

        Pipeline pipeline = pb.build();
        return new StyleTransferPipeline(pipeline, new ArrayList<>(styleNames));
    }

    /**
     * 根据模型名称创建对应的 ImageFilter。
     *
     * @param modelName 模型名称
     * @return ImageFilter 实例
     */
    private static ImageFilter createFilter(String modelName) {
        if (modelName.startsWith("style-")) {
            return new StyleTransferImageFilter(modelName);
        } else if (modelName.startsWith("anime-gan-v2-")) {
            return new AnimeGanImageFilter(modelName);
        }
        throw new IllegalArgumentException("未知的风格迁移模型: " + modelName);
    }
}