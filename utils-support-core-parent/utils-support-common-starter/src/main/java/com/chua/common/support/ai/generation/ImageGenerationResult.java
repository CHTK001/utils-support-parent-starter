package com.chua.common.support.ai.generation;

import java.util.List;
import java.util.Objects;

/**
 * 图像生成结果。
 *
 * <p>统一各服务商的图像生成返回结果。
 *
 * @param images 生成的图片列表
 * @param prompt 生成提示词
 * @author CH
 * @since 2026/08/11
 * @return 结果值
 */
public record ImageGenerationResult(List<GeneratedImage> images, String prompt) {

    /**
     * 规范构造器：对图片列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 全部构造点传入的列表均非空且元素非空，
     * 因此使用 {@link List#copyOf} 拒绝 null 列表与 null 元素。</p>
     *
     * @param images 生成的图片列表
     * @param prompt 生成提示词
     */
    public ImageGenerationResult {
        images = List.copyOf(Objects.requireNonNull(images, "images 不能为 null"));
    }

    /**
     * 单个生成的图片。
     *
     * @param key      图片标识
     * @param thumbUrl 缩略图 URL
     * @param oriUrl   原始尺寸 URL
     * @param rawUrl   预览尺寸 URL
     * @param width    宽度
     * @param height   高度
     * @param format   格式（png/jpeg/webp）
     */
    public record GeneratedImage(String key, String thumbUrl, String oriUrl, String rawUrl,
                                 int width, int height, String format) {
    }
}
