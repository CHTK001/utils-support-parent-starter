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

    private final ChatClient client;
    private String prompt;
    private String ratio;
    private String cameraMovement;
    private String refImageKey;
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