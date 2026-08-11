package com.chua.common.support.ai.generation;

import com.chua.common.support.ai.chat.ChatClient;

/**
 * 图像生成参数构建器（链式调用）。
 *
 * <p>用法：
 * <pre>{@code
 * ImageGenerationResult result = client.generateImage()
 *     .prompt("一只柴犬在樱花树下")
 *     .ratio("16:9")
 *     .n(3)
 *     .generate();
 * }</pre>
 *
 * @author CH
 * @since 2026/08/11
 */
public class ImageGenerationSpec {

    private final ChatClient client;
    private String prompt;
    private String ratio;
    private int n = 1;
    private int width;
    private int height;
    private String quality;
    private String refImageKey;

    public ImageGenerationSpec(ChatClient client) {
        this.client = client;
    }

    public ImageGenerationSpec prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public ImageGenerationSpec ratio(String ratio) {
        this.ratio = ratio;
        return this;
    }

    public ImageGenerationSpec n(int n) {
        this.n = n;
        return this;
    }

    public ImageGenerationSpec width(int width) {
        this.width = width;
        return this;
    }

    public ImageGenerationSpec height(int height) {
        this.height = height;
        return this;
    }

    public ImageGenerationSpec quality(String quality) {
        this.quality = quality;
        return this;
    }

    public ImageGenerationSpec refImageKey(String refImageKey) {
        this.refImageKey = refImageKey;
        return this;
    }

    public ImageGenerationResult generate() {
        return client.generateImage(prompt, ratio, n, width, height, quality, refImageKey);
    }

    public String prompt() { return prompt; }
    public String ratio() { return ratio; }
    public int n() { return n; }
    public int width() { return width; }
    public int height() { return height; }
    public String quality() { return quality; }
    public String refImageKey() { return refImageKey; }
}