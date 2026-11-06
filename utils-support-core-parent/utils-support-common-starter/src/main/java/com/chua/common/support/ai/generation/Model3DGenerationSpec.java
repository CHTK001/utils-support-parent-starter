package com.chua.common.support.ai.generation;

import com.chua.common.support.ai.chat.ChatClient;

/**
 * 3D 模型生成参数构建器（链式调用）。
 *
 * <p>用法：</p>
 * <pre>{@code
 * Model3DGenerationResult result = client.generate3D()
 *     .prompt("一把木椅")
 *     .refImageUrl("https://example.com/chair.png")
 *     .format("glb")
 *     .generate();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class Model3DGenerationSpec {

    /**
     * 底层对话客户端
     */
    private final ChatClient client;

    /**
     * 生成提示词
     */
    private String prompt;

    /**
     * 参考图 URL（图生 3D 必需，文生 3D 可空）
     */
    private String refImageUrl;

    /**
     * 目标模型格式
     */
    private String format = "glb";

    /**
     * 超时时间（秒）
     */
    private int timeoutSeconds = 300;

    /**
     * 创建参数构建器。
     *
     * @param client 底层对话客户端
     */
    public Model3DGenerationSpec(ChatClient client) {
        this.client = client;
    }

    /**
     * 设置提示词。
     *
     * @param prompt 提示词
     * @return 当前构建器
     */
    public Model3DGenerationSpec prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * 设置参考图 URL。
     *
     * @param refImageUrl 参考图 URL
     * @return 当前构建器
     */
    public Model3DGenerationSpec refImageUrl(String refImageUrl) {
        this.refImageUrl = refImageUrl;
        return this;
    }

    /**
     * 设置目标模型格式。
     *
     * @param format 模型格式（glb / gltf / obj / usdz）
     * @return 当前构建器
     */
    public Model3DGenerationSpec format(String format) {
        this.format = format;
        return this;
    }

    /**
     * 设置超时时间。
     *
     * @param timeoutSeconds 超时秒数
     * @return 当前构建器
     */
    public Model3DGenerationSpec timeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }

    /**
     * 执行生成。
     *
     * @return 3D 模型生成结果
     */
    public Model3DGenerationResult generate() {
        return client.generate3D(prompt, refImageUrl, format, timeoutSeconds);
    }

    /**
     * 获取提示词。
     *
     * @return 提示词
     */
    public String prompt() {
        return prompt;
    }

    /**
     * 获取参考图 URL。
     *
     * @return 参考图 URL
     */
    public String refImageUrl() {
        return refImageUrl;
    }

    /**
     * 获取目标模型格式。
     *
     * @return 模型格式
     */
    public String format() {
        return format;
    }

    /**
     * 获取超时时间。
     *
     * @return 超时秒数
     */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }
}
