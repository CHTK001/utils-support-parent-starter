package com.chua.zhipu.support.video;

import com.chua.common.support.ai.video.VideoClient;
import com.chua.common.support.ai.video.VideoClientSetting;
import com.chua.common.support.ai.video.VideoResponse;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"zhipu", "glm"})
public class ZhipuVideoClient implements VideoClient {

    /** 默认 API 地址 */
    private static final String DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4";
    /** 配置对象 */
    private final VideoClientSetting setting;
    /** 模型名称 */
    private String model;
    /** 图片宽度 */
    private Integer width;
    /** 图片高度 */
    private Integer height;
    /** 提示词 */
    private String prompt;
    /** 耗时（毫秒） */
    private Integer duration;
    /** 图片风格 */
    private String style;
    /** 参考图 */
    private byte[] referenceImage;
    /** 参考图强度 */
    private Double imageStrength;

    /**
     * 创建 zhipu视频客户端 实例
     * @param setting setting
     */
    public ZhipuVideoClient(VideoClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.duration = setting.getDuration();
        this.style = setting.getStyle();
    }

    @Override
    /** 模型 */
    public VideoClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 获取大小 */
    public VideoClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    /** 提示符 */
    public VideoClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    /** 持续时间 */
    public VideoClient duration(Integer duration) {
        this.duration = duration;
        return this;
    }

    @Override
    /** Style */
    public VideoClient style(String style) {
        this.style = style;
        return this;
    }

    @Override
    /** 引用镜像 */
    public VideoClient referenceImage(byte[] image) {
        this.referenceImage = image;
        return this;
    }

    /**
     * 引用镜像
     *
     * @param image 镜像
     * @return 引用镜像的结果
     */
    public VideoClient referenceImage(BufferedImage image) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bos);
            this.referenceImage = bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("转换参考图失败", e);
        }
        return this;
    }

    @Override
    /** 镜像strength */
    public VideoClient imageStrength(double strength) {
        this.imageStrength = strength;
        return this;
    }

    @Override
    /** Seed */
    public VideoClient seed(Long seed) {
        return this;
    }

    @Override
    /** 创建任务 */
    public String createTask(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String requestBody = JsonObject.create()
                .fluentPut("model", model != null ? model : "cogvideo")
                .fluentPut("prompt", actualPrompt)
                .fluentPut(width != null && height != null, "size", width + "x" + height)
                .fluentPut(duration != null, "duration", duration)
                .fluentPut(style != null && !style.isBlank(), "style", style)
                .fluentPut(referenceImage != null, "image_base64", Base64.getEncoder().encodeToString(referenceImage))
                .fluentPut(imageStrength != null, "image_strength", imageStrength)
                .toJSONString();
        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path("/video/generations")
                .header("Authorization", "Bearer " + setting.getAppKey())
                .json()
                .body(requestBody)
                .connectTimeout(60000)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("视频生成请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(resp.getBodyString(), Map.class);
        String taskId = root.containsKey("id") ? (String) root.get("id")
                : root.containsKey("request_id") ? (String) root.get("request_id") : null;
        if (taskId == null || taskId.isBlank()) {
            throw new RuntimeException("视频生成未返回任务 ID");
        }
        return taskId;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 查询任务
     *
     * @param taskId 任务标识
     * @return 查询任务的结果
     */
    public VideoResponse queryTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        try {
            ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                    .path("/video/status/" + taskId)
                    .header("Authorization", "Bearer " + setting.getAppKey())
                    .header("Content-Type", "application/json")
                    .connectTimeout(60000)
                    .get();
            if (!resp.isSuccess()) {
                return VideoResponse.builder().taskId(taskId).status(VideoResponse.Status.FAILED)
                        .errorMessage("查询任务状态失败: " + resp.getStatusCode()).build();
            }
            Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(resp.getBodyString(), Map.class);
            String taskStatus = root.containsKey("task_status") ? (String) root.get("task_status") : "";
            VideoResponse.Status status = mapStatus(taskStatus);
            VideoResponse.VideoResponseBuilder builder = VideoResponse.builder().taskId(taskId).status(status);
            if (status == VideoResponse.Status.SUCCESS) {
                List<Map<String, Object>> results = root.containsKey("video_result")
                        ? (List<Map<String, Object>>) root.get("video_result") : null;
                if (results != null && !results.isEmpty()) {
                    builder.videoUrl((String) results.getFirst().get("url"));
                }
                if (builder.build().getVideoUrl() == null && root.containsKey("url")) {
                    builder.videoUrl((String) root.get("url"));
                }
                if (root.containsKey("duration")) {
                    builder.duration(((Number) root.get("duration")).intValue());
                }
            } else if (status == VideoResponse.Status.FAILED) {
                builder.errorMessage(root.containsKey("error_message")
                        ? (String) root.get("error_message") : "任务执行失败");
            }
            if (root.containsKey("progress")) {
                builder.progress(((Number) root.get("progress")).intValue());
            }
            return builder.build();
        } catch (Exception e) {
            log.error("查询视频生成任务失败: {}", e.getMessage(), e);
            return VideoResponse.builder().taskId(taskId).status(VideoResponse.Status.FAILED)
                    .errorMessage(e.getMessage()).build();
        }
    }

    /**
     * 映射状态
     *
     * @param taskStatus 任务状态
     * @return 映射状态的结果
     */
    private VideoResponse.Status mapStatus(String taskStatus) {
        if (taskStatus == null) {
            return VideoResponse.Status.PENDING;
        }
        return switch (taskStatus.toUpperCase()) {
            case "SUCCESS", "SUCCEEDED", "SUCCESSFUL" -> VideoResponse.Status.SUCCESS;
            case "FAILED", "FAIL" -> VideoResponse.Status.FAILED;
            case "RUNNING", "PROCESSING" -> VideoResponse.Status.RUNNING;
            default -> VideoResponse.Status.PENDING;
        };
    }

    @Override public void close() {}

    /** normalizebaseurl */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
