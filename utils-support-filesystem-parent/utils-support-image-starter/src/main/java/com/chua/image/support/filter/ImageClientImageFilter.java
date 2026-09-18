package com.chua.image.support.filter;

import com.chua.common.support.ai.image.ImageClient;

import java.awt.image.BufferedImage;

/**
 * AI 图像滤镜 - 使用 {@link ImageClient} 转换图像
 *
 * <p>通过注入的 AI 客户端将源图像作为参考图, 应用文本提示词生成风格化新图像。
 * 适用于 DALL-E、Midjourney、Stable Diffusion 等支持图生图(img2img)的 AI 服务。
 *
 * <p>使用示例:
 * <pre>{@code
 *   // 1. 创建 AI 客户端
 *   ImageClient client = ImageClient.create("openai", "sk-xxx")
 *       .model("dall-e-2")
 *       .size(1024, 1024);
 *
 *   // 2. 创建滤镜并注入客户端
 *   ImageClientImageFilter filter = new ImageClientImageFilter()
 *       .imageClient(client)
 *       .prompt("转换为水彩画风格")
 *       .imageStrength(0.6);
 *
 *   // 3. 应用滤镜
 *   BufferedImage result = filter.converter(sourceImage);
 * }</pre>
 *
 * <p>注意事项:
 * <ul>
 *   <li>必须先注入 {@link ImageClient}, 否则 {@link #filter(BufferedImage, BufferedImage)} 会抛出异常</li>
 *   <li>实际处理需要联网调用 AI 服务, 离线环境下不可用</li>
 *   <li>不同 provider 对 imageStrength 等参数支持程度不同</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageClientImageFilter extends AbstractImageClientFilter {

    /**
    * 提示词
    */
    private String prompt = "";

    /**
    * 参考图影响强度, 范围 0.0 ~ 1.0
    */
    private double imageStrength = 0.6;


    /**
    * 默认构造, 后续需要注入 镜像客户端 并设置 提示符
    */
    public ImageClientImageFilter() {
    }


    /**
    * 设置 AI 图像生成客户端 (覆盖父类以支持链式调用)
    *
    * @param imageClient AI 客户端实例
    * @return 当前滤镜实例
    */
    @Override
    public ImageClientImageFilter imageClient(ImageClient imageClient) {
        super.imageClient(imageClient);
        return this;
    }


    /**
    * 设置提示词
    *
    * @param prompt 提示词
    * @return 当前滤镜实例
    */
    public ImageClientImageFilter prompt(String prompt) {
        this.prompt = prompt == null ? "" : prompt;
        return this;
    }


    /**
    * 设置参考图影响强度
    *
    * @param imageStrength 强度, 范围 0.0 ~ 1.0
    * @return 当前滤镜实例
    */
    public ImageClientImageFilter imageStrength(double imageStrength) {
        this.imageStrength = Math.max(0.0, Math.min(1.0, imageStrength));
        return this;
    }


    /**
    * 应用 AI 图像转换
    *
    * <p>调用注入的 {@link ImageClient}, 将源图像作为参考图,
    * 结合 提示词 和 imageStrength 调用 {@code referenceImage(src).prompt(...).imageStrength(...).generate()}。
    *
    * @param src 源图像
    * @param dst 目标图像 (本滤镜忽略, 始终创建新图像)
    * @return AI 生成的新图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        ImageClient client = requireClient();
        return client
                .referenceImage(src)
                .prompt(prompt)
                .imageStrength(imageStrength)
                .generate();
    }
}
