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

    /** 底层对话客户端 */
    private final ChatClient client;

    /** 生成提示词 */
    private String prompt;

    /** 宽高比 */
    private String ratio;

    /** 生成数量 */
    private int n = 1;

    /** 图像宽度（像素） */
    private int width;

    /** 图像高度（像素） */
    private int height;

    /** 图像质量 */
    private String quality;

    /** 参考图键 */
    private String refImageKey;

    /**
    * 创建 ImageGenerationSpec 实例
    * @param client client
     */
    public ImageGenerationSpec(ChatClient client) {
        this.client = client;
    }

    /** Prompt */
    public ImageGenerationSpec prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /** Ratio */
    public ImageGenerationSpec ratio(String ratio) {
        this.ratio = ratio;
        return this;
    }

    /** N */
    public ImageGenerationSpec n(int n) {
        this.n = n;
        return this;
    }

    /** Width */
    public ImageGenerationSpec width(int width) {
        this.width = width;
        return this;
    }

    /** Height */
    public ImageGenerationSpec height(int height) {
        this.height = height;
        return this;
    }

    /** Quality */
    public ImageGenerationSpec quality(String quality) {
        this.quality = quality;
        return this;
    }

    /** RefImageKey */
    public ImageGenerationSpec refImageKey(String refImageKey) {
        this.refImageKey = refImageKey;
        return this;
    }

    /** Generate */
    public ImageGenerationResult generate() {
        return client.generateImage(prompt, ratio, n, width, height, quality, refImageKey);
    }

    /** Prompt */
    public String prompt() { return prompt; }
    /** Ratio */
    public String ratio() { return ratio; }
    /** N */
    public int n() { return n; }
    /** Width */
    public int width() { return width; }
    /** Height */
    public int height() { return height; }
    /** Quality */
    public String quality() { return quality; }
    /** RefImageKey */
    public String refImageKey() { return refImageKey; }
}