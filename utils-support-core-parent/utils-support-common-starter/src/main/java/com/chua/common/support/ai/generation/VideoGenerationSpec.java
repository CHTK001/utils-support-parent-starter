package com.chua.common.support.ai.generation;

import com.chua.common.support.ai.chat.ChatClient;

/**
 * 视频生成参数构建器（链式调用）。
 *
 * <p>用法：
 * <pre>{@code
 * VideoGenerationResult result = client.generateVideo()
 *     .prompt("一只柴犬在雪地里奔跑")
 *     .ratio("16:9")
 *     .cameraMovement("推进")
 *     .generate();
 * }</pre>
 *
 * @author CH
 * @since 2026/08/11
 */
public class VideoGenerationSpec {

    /** 底层对话客户端 */
    /** 客户端 */
    private final ChatClient client;

    /** 生成提示词 */
    /** Prompt */
    private String prompt;

    /** 宽高比 */
    /** 比率 */
    private String ratio;

    /** 镜头运动描述 */
    /** Cameramovement */
    private String cameraMovement;

    /** 参考图键 */
    /** 引用图片密钥 */
    private String refImageKey;

    /** 超时时间（秒） */
    /** 超时秒 */
    private int timeoutSeconds = 300;

    public VideoGenerationSpec(ChatClient client) {
        this.client = client;
    }

    public VideoGenerationSpec prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public VideoGenerationSpec ratio(String ratio) {
        this.ratio = ratio;
        return this;
    }

    public VideoGenerationSpec cameraMovement(String cameraMovement) {
        this.cameraMovement = cameraMovement;
        return this;
    }

    public VideoGenerationSpec refImageKey(String refImageKey) {
        this.refImageKey = refImageKey;
        return this;
    }

    public VideoGenerationSpec timeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }

    public VideoGenerationResult generate() {
        return client.generateVideo(prompt, ratio, cameraMovement, refImageKey, timeoutSeconds);
    }

    public String prompt() { return prompt; }
    public String ratio() { return ratio; }
    public String cameraMovement() { return cameraMovement; }
    public String refImageKey() { return refImageKey; }
    public int timeoutSeconds() { return timeoutSeconds; }
}