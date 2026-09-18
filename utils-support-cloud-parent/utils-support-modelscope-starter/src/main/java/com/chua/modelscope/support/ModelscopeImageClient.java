package com.chua.modelscope.support;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
* 模型scope 图像生成客户端（SPI 提供者="modelscope"）。
*
* <p>调用 ModelScope API-Inference 的 OpenAI 兼容图像端点
* {@code POST {baseUrl}/v1/images/generations}。ModelScope 在该端点下挂载了
* 多种文生图模型（如 通义千问-镜像、st Diffusion 系列、FLUX.1、Wan 等），
* 鉴权使用 Bearer 令牌（{@code MODELSCOPE_TOKEN}，从魔搭个人中心获取）。
*
* <p>ModelScope 扩展字段（negative_prompt / seed / steps / guidance_scale）通过
* 请求体顶层字段透传，与 打开AI 官方字段不冲突。
*
* <p>调用示例：
* <pre>{@code
*   // 文生图
*   BufferedImage image = ImageClient.create("modelscope", "ms-xxx")
*       .model("Qwen/Qwen-Image")
*       .size(1024, 1024)
*       .negativePrompt("低质量、模糊")
*       .steps(30)
*       .seed(42L)
*       .generate("一只柴犬在樱花树下");
*
*   // 异步任务（适合长耗时模型）
*   String taskId = ImageClient.create("modelscope", "ms-xxx")
*       .model("damo/text-to-image-large")
*       .size(1024, 1024)
*       .createTask("夜景城市");
*   ImageResponse resp = client.queryTask(taskId);
* }</pre>ze(1024, 1024)
*       .createTask("夜景城市");
*   ImageResponse resp = client.queryTask(taskId);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("modelscope")
public class ModelscopeImageClient implements ImageClient {

    /**
    * 默认生成模型（魔搭公开的高质量文生图模型）
    */
    private static final String DEFAULT_MODEL = "Qwen/Qwen-Image";

    private final ImageClientSetting setting; // setting

    private String model; // 模型
    private Integer width; // width
    private Integer height; // height
    private String prompt; // 提示符
    private String negativePrompt; // negative提示符
    private Long seed; // 参见
    private Integer steps; // steps
    private Double guidanceScale; // guidancescale
    private final List<byte[]> referenceImages = new ArrayList<>(); // 引用镜像

    /**
    * modelscope镜像客户端。
    * @param setting setting
    */
    public ModelscopeImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.prompt = setting.getPrompt();
        this.negativePrompt = setting.getNegativePrompt();
        this.seed = setting.getSeed();
        this.steps = setting.getSteps();
        if (setting.getReferenceImage() != null && setting.getReferenceImage().length > 0) {
            this.referenceImages.add(setting.getReferenceImage());
        }
    }

    @Override
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    public ImageClient negativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
        return this;
    }

    @Override
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public ImageClient steps(Integer steps) {
        this.steps = steps;
        return this;
    }

    /**
    * 设置 CFG 引导系数（modelscope 字段名 guidance_scale）。
    * 该值越高生成结果越贴合提示词，越低则更具创造性。
    *
    * @param guidance CFG 引导系数
    * @return 当前客户端实例（链式调用）
    */
    public ImageClient guidanceScale(double guidance) {
        this.guidanceScale = guidance;
        return this;
    }

    @Override
    public ImageClient referenceImage(byte[] image) {
        if (image != null && image.length > 0) {
            this.referenceImages.add(image);
        }
        return this;
    }

    @Override
    public ImageClient referenceImage(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return referenceImage(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("参考图编码失败", e);
        }
    }

    @Override
    public ImageClient imageStrength(double strength) {
        log.warn("ModelScope 文生图端点不支持 imageStrength，已忽略");
        return this;
    /**
    * control类型。
    * @param controlType control类型
    * @return control类型的结果
    */
    }

    @Override
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("ModelScope provider 不支持 ControlNet（controlType=" + controlType + "）");
    /**
    * quality。
    * @param quality quality
    * @return quality的结果
    */
    }

    @Override
    public ImageClient quality(String quality) {
        log.warn("ModelScope provider 不支持 quality 字段，已忽略");
        return this;
    /**
    * style。
    * @param style style
    * @return style的结果
    */
    }

    @Override
    public ImageClient style(String style) {
        log.warn("ModelScope provider 不支持 style 字段，已忽略");
        return this;
    /**
    * generate。
    * @param prompt 提示符
    * @return generate的结果
    * @param json json
    * @param taskId 任务id
    */
    }

    @Override
    public BufferedImage generate(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        JsonObject body = JsonObject.create()
                .fluentPut("model", model != null ? model : DEFAULT_MODEL)
                .fluentPut("prompt", actualPrompt)
                .fluentPut("n", 1)
                .fluentPut("response_format", "b64_json");
        if (width != null && height != null) {
            body.fluentPut("size", width + "x" + height);
        }
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            body.fluentPut("negative_prompt", negativePrompt);
        }
        if (seed != null) {
            body.fluentPut("seed", seed);
        }
        if (steps != null) {
            body.fluentPut("steps", steps);
        }
        if (guidanceScale != null) {
            body.fluentPut("guidance_scale", guidanceScale);
        }
        if (!referenceImages.isEmpty()) {
            List<String> refs = new ArrayList<>(referenceImages.size());
            for (byte[] ref : referenceImages) {
                refs.add(Base64.getEncoder().encodeToString(ref));
            }
            body.fluentPut("image", refs);
        }

        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path(ModelscopeConstants.PATH_IMAGES_GENERATIONS)
                .header("Authorization", buildAuthHeader())
                .json()
                .body(body.toJSONString())
                .connectTimeout(ModelscopeConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(ModelscopeConstants.READ_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("ModelScope 图像生成失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        return parseFirstImage(resp.getBodyString());
    }

    @Override
    public String createTask(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String useModel = model != null ? model : DEFAULT_MODEL;
        JsonObject body = JsonObject.create()
                .fluentPut("model", useModel)
                .fluentPut("prompt", actualPrompt)
                .fluentPut("n", 1)
                .fluentPut("response_format", "url");
        if (width != null && height != null) {
            body.fluentPut("size", width + "x" + height);
        }
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            body.fluentPut("negative_prompt", negativePrompt);
        }
        if (seed != null) {
            body.fluentPut("seed", seed);
        }
        if (steps != null) {
            body.fluentPut("steps", steps);
        }
        if (guidanceScale != null) {
            body.fluentPut("guidance_scale", guidanceScale);
        }
        if (!referenceImages.isEmpty()) {
            List<String> refs = new ArrayList<>(referenceImages.size());
            for (byte[] ref : referenceImages) {
                refs.add(Base64.getEncoder().encodeToString(ref));
            }
            body.fluentPut("image", refs);
        }

        String path = String.format(ModelscopeConstants.PATH_MODEL_INFER_ASYNC, useModel);
        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path(path)
                .header("Authorization", buildAuthHeader())
                .json()
                .body(body.toJSONString())
                .connectTimeout(ModelscopeConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(ModelscopeConstants.READ_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("ModelScope 异步任务提交失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        Map<String, Object> root;
        try {
            root = Json.fromJson(resp.getBodyString(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("ModelScope 异步任务响应解析失败: " + resp.getBodyString(), e);
        }
        Object taskId = root.get("task_id");
        if (taskId == null) {
            taskId = root.get("request_id");
        }
        if (taskId == null) {
            throw new RuntimeException("ModelScope 异步任务响应缺少 task_id: " + resp.getBodyString());
        }
        return taskId.toString();
    }

    @Override
    public ImageResponse queryTask(String taskId) {
        String useModel = model != null ? model : DEFAULT_MODEL;
        String path = String.format(ModelscopeConstants.PATH_MODEL_TASK, useModel, taskId);
        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path(path)
                .header("Authorization", buildAuthHeader())
                .connectTimeout(ModelscopeConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(ModelscopeConstants.READ_TIMEOUT_MILLIS)
                .get();
        if (!resp.isSuccess()) {
            throw new RuntimeException("ModelScope 任务查询失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        Map<String, Object> root;
        try {
            root = Json.fromJson(resp.getBodyString(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("ModelScope 任务响应解析失败: " + resp.getBodyString(), e);
        }
        Object statusObj = root.getOrDefault("status", root.get("task_status"));
        String status = statusObj == null ? "UNKNOWN" : statusObj.toString();
        String normalized = status.toUpperCase();
        ImageResponse.ImageResponseBuilder builder = ImageResponse.builder();
        switch (normalized) {
            case "SUCCESS", "SUCCEEDED", "DONE", "FINISHED" -> builder.status(ImageResponse.Status.SUCCESS);
            case "FAILED", "ERROR", "CANCELED" -> builder.status(ImageResponse.Status.FAILED);
            default -> builder.status(ImageResponse.Status.PENDING);
        }
        Object outputsObj = root.get("output_images");
        if (outputsObj == null) {
            outputsObj = root.get("output");
        }
        if (outputsObj == null) {
            outputsObj = root.get("data");
        }
        if (outputsObj instanceof List<?> list && !list.isEmpty()) {
            Object first = list.getFirst();
            if (first instanceof Map<?, ?> map) {
                Object url = map.get("url");
                Object b64 = map.get("b64_json");
                if (url != null) {
                    builder.imageUrl(url.toString());
                } else if (b64 != null) {
                    try {
                        builder.imageBytes(Base64.getDecoder().decode(b64.toString()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        Object message = root.get("message");
        if (message != null) {
            builder.errorMessage(message.toString());
        }
        return builder.build();
    }

    @Override
    public List<ModelDefinition> models() {
        return MODELS;
    }

    @Override
    public void close() {
        referenceImages.clear();
    }

    @SuppressWarnings("unchecked")
    private BufferedImage parseFirstImage(String json) {
        Map<String, Object> root;
        try {
            root = Json.fromJson(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("ModelScope 图像响应解析失败: " + json, e);
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof List) || ((List<Object>) dataObj).isEmpty()) {
            throw new RuntimeException("ModelScope 图像返回数据为空: " + json);
        }
        Map<String, Object> first = (Map<String, Object>) ((List<Object>) dataObj).getFirst();
        Object b64 = first.get("b64_json");
        if (b64 != null && !b64.toString().isBlank()) {
            try {
                return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64.toString())));
            } catch (Exception e) {
                throw new RuntimeException("ModelScope 图像 Base64 解码失败", e);
            }
        }
        Object url = first.get("url");
        if (url != null && !url.toString().isBlank()) {
            try {
                ClientResponse resp = HttpClientFactory.of(url.toString()).get();
                if (!resp.isSuccess()) {
                    throw new RuntimeException("ModelScope 图像 URL 下载失败: " + resp.getStatusCode());
                }
                return ImageIO.read(new ByteArrayInputStream(resp.getBody()));
            } catch (IOException e) {
                throw new RuntimeException("ModelScope 图像 URL 解析失败", e);
            }
        }
        throw new RuntimeException("ModelScope 响应缺少 b64_json 与 url 字段: " + json);
    }

    private String buildAuthHeader() {
        String appKey = setting.getAppKey();
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalStateException("ModelScope provider 需要设置 API Token（魔搭个人中心 -> 访问令牌）");
        }
        return "Bearer " + appKey;
    }

    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = ModelscopeConstants.DEFAULT_INFERENCE_BASE_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        return url;
    }

    /**
    * 模型scope 知名图像生成模型清单（节选自魔搭 模型 页面）。
    */
    private static final List<ModelDefinition> MODELS = List.of(
            ModelDefinition.builder()
                    .id("Qwen/Qwen-Image").name("Qwen-Image").provider("modelscope")
                    .description("Qwen-Image 20B 文生图，中英文字渲染优秀")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("Qwen/Qwen-Image-Edit").name("Qwen-Image-Edit").provider("modelscope")
                    .description("Qwen-Image 指令编辑版本")
                    .capabilities(List.of("image-editing")).build(),
            ModelDefinition.builder()
                    .id("AI-ModelScope/stable-diffusion-v1-5").name("Stable Diffusion 1.5").provider("modelscope")
                    .description("Stable Diffusion 1.5 文生图")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("AI-ModelScope/sdxl-turbo").name("SDXL Turbo").provider("modelscope")
                    .description("SDXL-Turbo 1~4 步蒸馏文生图")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("AI-ModelScope/FLUX.1-schnell").name("FLUX.1 Schnell").provider("modelscope")
                    .description("FLUX.1 schnell 1~4 步蒸馏文生图")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("AI-ModelScope/FLUX.1-dev").name("FLUX.1 Dev").provider("modelscope")
                    .description("FLUX.1 dev 50 步高质量文生图")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("damo/text-to-image-large").name("Text-to-Image Large").provider("modelscope")
                    .description("达摩院 text-to-image-large 异步推理模型")
                    .capabilities(List.of("text-to-image")).build()
    );
}
