package com.chua.common.support.ai.generation;

/**
 * 图像生成结果。
 *
 * <p>统一各服务商的图像生成返回结果。
 *
 * @param images 生成的图片列表
 * @param prompt 生成提示词
 * @author CH
 * @since 2026/08/11
 */
public record ImageGenerationResult(java.util.List<GeneratedImage> images, String prompt) {

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