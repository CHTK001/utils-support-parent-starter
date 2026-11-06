// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.alibaba.support.image;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 阿里云通义万相图片生成客户端
 *
 * <p>基于 DashScope 通义万相 API 的 {@link ImageClient} 实现，通过 HTTP 协议
 * 调用通义万相（Wanx）系列模型的图片生成接口。该 API 采用异步任务模式，
 * 提交任务后需轮询任务状态直至完成。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"alibaba"})
public class AlibabaImageClient implements ImageClient {

    /**
     * 通义万相默认 API 地址
     */
    private static final String DEFAULT_URL = "https://dashscope.aliyuncs.com";

    /**
     * 默认模型
    */
    private static final String DEFAULT_MODEL = "wanx-v1";
    /**
     * 默认推理步数
    */
    private static final int DEFAULT_STEPS = 50;
    /**
     * 默认尺寸（像素）
    */
    private static final int DEFAULT_SIZE = 1024;
    /**
     * 授权请求头名称
    */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    /**
     * Bearer 令牌前缀
    */
    private static final String TOKEN_PREFIX = "Bearer ";
    /**
     * JSON 内容类型
    */
    private static final String CONTENT_TYPE_JSON = "application/json";
    /**
     * 任务成功状态
    */
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /**
     * 任务失败状态
    */
    private static final String STATUS_FAILED = "FAILED";
    /**
     * 连接超时时间（秒）
    */
    private static final int CONNECT_TIMEOUT_SECONDS = 30;

    /**
     * 任务轮询间隔（毫秒）
     */
    private static final long POLL_INTERVAL_MS = 2000;

    /**
     * 最大轮询次数
     */
    private static final int MAX_POLL_COUNT = 60;

    /**
     * 客户端配置
     */
    private final ImageClientSetting setting;

    /**
     * 当前使用的模型名称
     */
    private String model;

    /**
     * 当前图片宽度
     */
    private Integer width;

    /**
     * 当前图片高度
     */
    private Integer height;

    /**
     * 当前提示词
     */
    private String prompt;

    /**
     * 当前质量等级
     */
    private String quality;

    /**
     * 当前风格
     */
    private String style;

    /**
     * 当前随机种子
     */
    private Long seed;

    /**
     * 当前推理步数
     */
    private Integer steps;

    /**
     * 构造阿里云通义万相图片生成客户端
     *
     * @param setting 客户端配置
     */
    public AlibabaImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
    }

    /**
     * 模型
    */
    @Override
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    /**
     * 获取大小
    */
    @Override
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * 提示符
    */
    @Override
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * Quality
    */
    @Override
    public ImageClient quality(String quality) {
        this.quality = quality;
        return this;
    }

    /**
     * Style
    */
    @Override
    public ImageClient style(String style) {
        this.style = style;
        return this;
    }

    /**
     * Seed
    */
    @Override
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Steps
    */
    @Override
    public ImageClient steps(Integer steps) {
        this.steps = steps;
        return this;
    }

    /**
     * 引用镜像
    */
    @Override
    public ImageClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    /**
     * 引用镜像
    */
    @Override
    public ImageClient referenceImage(BufferedImage image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    /**
     * 镜像strength
    */
    @Override
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("该服务商不支持参考图强度");
    }

    /**
     * control类型
    */
    @Override
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("该服务商不支持ControlNet");
    }

    /**
     * Generate
    */
    @Override
    public BufferedImage generate(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String taskId = createTask(actualPrompt);
        ImageResponse response = pollTask(taskId);
        if (response.getStatus() == ImageResponse.Status.SUCCESS) {
            String imageUrl = response.getImageUrl();
            ClientResponse imageResponse = HttpClientFactory.of(imageUrl)
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS * 1000)
                    .readTimeout(60000)
                    .get();
            if (!imageResponse.isSuccess()) {
                throw new RuntimeException("下载通义万相图片失败: HTTP " + imageResponse.getStatusCode());
            }
            byte[] bytes = imageResponse.getBody();
            if (bytes == null || bytes.length == 0) {
                throw new RuntimeException("下载通义万相图片失败: 响应体为空");
            }
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new RuntimeException("通义万相图片生成请求失败", e);
            }
        }
        throw new RuntimeException("通义万相图片生成失败: " + response.getErrorMessage());
    }

    /**
     * 创建任务
    */
    @Override
    public String createTask(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String actualModel = model != null ? model : DEFAULT_MODEL;
        String sizeStr = buildSize();
        int actualSteps = steps != null ? steps : DEFAULT_STEPS;
        String requestBody = "{\"model\":\"" + actualModel
                + "\",\"input\":{\"messages\":[{\"role\":\"user\",\"content\":\""
                + escapeJson(actualPrompt) + "\"}]}"
                + ",\"parameters\":{\"size\":\"" + sizeStr + "\",\"steps\":" + actualSteps + "}}";
        ClientResponse response = HttpClientFactory.of(normalizeBaseUrl())
                .path("/api/v1/services/aigc/text-generation/generation")
                .header(HEADER_AUTHORIZATION, TOKEN_PREFIX + setting.getAppKey())
                .json()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS * 1000)
                .readTimeout(60000)
                .body(requestBody)
                .post();
        if (response.getStatusCode() != 200) {
            throw new RuntimeException("通义万相提交任务失败: " + response.getStatusCode()
                    + " - " + response.getBodyString());
        }
        String body = response.getBodyString();
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(body, Map.class);
        Map<String, Object> output = (Map<String, Object>) root.get("output");
        if (output == null) {
            throw new RuntimeException("通义万相返回的任务 ID 为空");
        }
        String taskId = (String) output.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new RuntimeException("通义万相返回的任务 ID 为空");
        }
        log.debug("通义万相任务已提交，taskId: {}", taskId);
        return taskId;
    }

    /**
     * 查询任务
    */
    @Override
    public ImageResponse queryTask(String taskId) {
        try {
            ClientResponse response = HttpClientFactory.of(normalizeBaseUrl())
                    .path("/api/v1/tasks/" + taskId)
                    .header(HEADER_AUTHORIZATION, TOKEN_PREFIX + setting.getAppKey())
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS * 1000)
                    .readTimeout(30000)
                    .get();
            if (response.getStatusCode() != 200) {
                return ImageResponse.builder()
                        .taskId(taskId)
                        .status(ImageResponse.Status.FAILED)
                        .errorMessage("查询任务失败: " + response.getStatusCode())
                        .build();
            }
            String body = response.getBodyString();
            Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(body, Map.class);
            Map<String, Object> output = (Map<String, Object>) root.get("output");
            if (output == null) {
                return ImageResponse.builder()
                        .taskId(taskId)
                        .status(ImageResponse.Status.FAILED)
                        .errorMessage("响应中缺少 output 字段")
                        .build();
            }
            String taskStatus = (String) output.get("task_status");
            if ("SUCCEEDED".equals(taskStatus)) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
                if (results != null && !results.isEmpty()) {
                    String imageUrl = (String) results.getFirst().get("url");
                    return ImageResponse.builder()
                            .taskId(taskId)
                            .status(ImageResponse.Status.SUCCESS)
                            .imageUrl(imageUrl)
                            .build();
                }
                return ImageResponse.builder()
                        .taskId(taskId)
                        .status(ImageResponse.Status.FAILED)
                        .errorMessage("通义万相返回的结果列表为空")
                        .build();
            } else if ("FAILED".equals(taskStatus)) {
                String errorMsg = output.get("message") != null ? (String) output.get("message") : "未知错误";
                return ImageResponse.builder()
                        .taskId(taskId)
                        .status(ImageResponse.Status.FAILED)
                        .errorMessage(errorMsg)
                        .build();
            } else {
                return ImageResponse.builder()
                        .taskId(taskId)
                        .status(ImageResponse.Status.RUNNING)
                        .build();
            }
        } catch (RuntimeException e) {
            return ImageResponse.builder()
                    .taskId(taskId)
                    .status(ImageResponse.Status.FAILED)
                    .errorMessage("查询任务失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 轮询任务直至完成
     *
     * @param taskId 任务 标识
     * @return 任务最终响应
     */
    private ImageResponse pollTask(String taskId) {
        for (int i = 0; i < MAX_POLL_COUNT; i++) {
            ImageResponse response = queryTask(taskId);
            if (response.getStatus() == ImageResponse.Status.SUCCESS
                    || response.getStatus() == ImageResponse.Status.FAILED) {
                return response;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ImageResponse.builder()
                        .taskId(taskId)
                        .status(ImageResponse.Status.FAILED)
                        .errorMessage("轮询任务被中断")
                        .build();
            }
        }
        return ImageResponse.builder()
                .taskId(taskId)
                .status(ImageResponse.Status.FAILED)
                .errorMessage("轮询超时，任务未在预期时间内完成")
                .build();
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
    }

    /**
     * 构建图片尺寸字符串
     *
     * @return 如 "1024x1024"
     */
    private String buildSize() {
        int w = width != null ? width : 1024;
        int h = height != null ? height : 1024;
        return w + "x" + h;
    }

    /**
     * 规范化 API 基础地址
     *
     * @return 规范化后的 URL
     */
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

    /**
     * 转义 JSON 字符串中的特殊字符
     *
     * @param input 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}