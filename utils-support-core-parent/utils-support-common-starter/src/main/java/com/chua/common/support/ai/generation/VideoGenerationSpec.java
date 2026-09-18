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
    private final ChatClient client;

    /** 生成提示词 */
    private String prompt;

    /** 宽高比 */
    private String ratio;

    /** 镜头运动描述 */
    private String cameraMovement;

    /** 参考图键 */
    private String refImageKey;

    /** 超时时间（秒） */
    private int timeoutSeconds = 300;

    /**
    * 创建 VideoGenerationSpec 实例
    * @param client client
    */
    public VideoGenerationSpec(ChatClient client) {
        this.client = client;
    }

    /**
     * Prompt
     * @param prompt 提示词，不允许为 null
     * @return VideoGenerationSpec 对象
     */
    public VideoGenerationSpec prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * Ratio
     * @param ratio 比率，不允许为 null
     * @return VideoGenerationSpec 对象
     */
    public VideoGenerationSpec ratio(String ratio) {
        this.ratio = ratio;
        return this;
    }

    /**
     * CameraMovement
     * @param cameraMovement 方法入参 cameraMovement
     * @return VideoGenerationSpec 对象
     */
    public VideoGenerationSpec cameraMovement(String cameraMovement) {
        this.cameraMovement = cameraMovement;
        return this;
    }

    /**
     * RefImageKey
     * @param refImageKey refImage键，不允许为 null
     * @return VideoGenerationSpec 对象
     */
    public VideoGenerationSpec refImageKey(String refImageKey) {
        this.refImageKey = refImageKey;
        return this;
    }

    /**
     * TimeoutSeconds
     * @param timeoutSeconds 超时时间Seconds，不允许为 null
     * @return VideoGenerationSpec 对象
     */
    public VideoGenerationSpec timeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }

    /**
     * Generate
     * @return VideoGeneration结果 对象
     */
    public VideoGenerationResult generate() {
        return client.generateVideo(prompt, ratio, cameraMovement, refImageKey, timeoutSeconds);
    }

    /** Prompt */
    public String prompt() { return prompt; }
    /** Ratio */
    public String ratio() { return ratio; }
    /** CameraMovement */
    public String cameraMovement() { return cameraMovement; }
    /** RefImageKey */
    public String refImageKey() { return refImageKey; }
    /** TimeoutSeconds */
    public int timeoutSeconds() { return timeoutSeconds; }
}
