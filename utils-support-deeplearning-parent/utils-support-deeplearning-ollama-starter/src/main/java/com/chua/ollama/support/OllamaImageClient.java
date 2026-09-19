package com.chua.ollama.support;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.spi.annotations.Spi;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.generate.OllamaGenerateImageRequest;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.models.response.OllamaImageResult;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Ollama 本地图片生成客户端（SPI provider="ollama"）。
 *
 * <p>基于 ollama4j 原生 API（{@code POST /api/generate} 图像 生成 实验 特性）
 * 实现 文生图，输出 经 Base64 解码 为 {@link BufferedImage}。
 * Ollama 图像 生成为 实验 能力，仅 部分 模型（如 支持 图像 输出 的 模型）可用，
 * 默认 返回 不支持 时 由 底层 异常 提示。
 * 模型列表 通过 {@code listModels()} 动态 获取。
 * 默认 地址 {@code http://localhost:11434}，无需 API Key。
 *
 * <p>调用 示例：
 * <pre>{@code
 *   BufferedImage img = ImageClient.create("ollama", "")
 *       .model("image-model")
 *       .size(1024, 1024)
 *       .generate("一只 可爱 的 猫");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ollama")
public class OllamaImageClient implements ImageClient {

    /**
     * 客户端 配置
     */
    private final ImageClientSetting setting;

    /**
     * ollama4j 原生 客户端
     */
    private final Ollama ollama;

    /**
     * 当前 模型 名称
     */
    private String model;

    /**
     * 图片 宽度
     */
    private Integer width;

    /**
     * 图片 高度
     */
    private Integer height;

    /**
     * 异步 任务 缓存（Ollama 无 原生 异步，以 本地 记录 模拟 轮询 契约）。
     * <p>使用 有界 缓存 防止 长时间 运行 下 内存 无界 增长；超出 容量 时 最老 任务 被 驱逐。</p>
     */
    private final Map<String, ImageResponse> taskCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, ImageResponse>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, ImageResponse> eldest) {
                            return size() > 256;
                        }
                    });

    /**
     * 创建 Ollama 图片 生成 客户端。
     *
     * @param setting 客户端 配置（provider 应为 "ollama"，apiKey 可为 空）
     */
    public OllamaImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.ollama = OllamaSupport.client(setting != null ? setting.getBaseUrl() : null);
        this.model = setting != null ? setting.getModel() : null;
    }

    @Override
    /** 提供者 */
    public ImageClient provider(String provider) {
        return this;
    }

    @Override
    /** 模型 */
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 尺寸 */
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    /** 正向提示词 */
    public ImageClient prompt(String prompt) {
        return this;
    }

    @Override
    /** 图片生成 */
    public BufferedImage generate(String prompt) {
        String text = prompt != null ? prompt : null;
        OllamaGenerateImageRequest request = new OllamaGenerateImageRequest();
        request.setModel(model != null && !model.isBlank() ? model : "image-model");
        request.setPrompt(text != null ? text : "");
        request.setWidth(width);
        request.setHeight(height);
        try {
            OllamaImageResult result = ollama.generateImage(request);
            if (result == null || result.getImage() == null || result.getImage().isBlank()) {
                throw new RuntimeException("Ollama generateImage 未 返回 图像 数据");
            }
            byte[] imageBytes = Base64.getDecoder().decode(result.getImage());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new RuntimeException("Ollama generateImage 返回 的 图像 无法 解码");
            }
            return image;
        } catch (io.github.ollama4j.exceptions.OllamaException e) {
            throw OllamaSupport.wrap("generateImage", e);
        } catch (java.io.IOException e) {
            throw OllamaSupport.wrap("generateImage(decode)", e);
        }
    }

    @Override
    /** 创建任务 */
    public String createTask(String prompt) {
        // Ollama 图像 生成 为 同步 流式 端点，无 原生 异步 任务；
        // 此处 立即 执行 生成 并 缓存 结果，返回 本地 任务 标识 供 queryTask 轮询。
        String taskId = "ollama-image-" + System.currentTimeMillis();
        ImageResponse response;
        try {
            BufferedImage image = generate(prompt);
            byte[] bytes = toBytes(image);
            response = ImageResponse.builder()
                    .taskId(taskId)
                    .status(ImageResponse.Status.SUCCESS)
                    .imageBytes(bytes)
                    .progress(100)
                    .build();
        } catch (Exception e) {
            response = ImageResponse.builder()
                    .taskId(taskId)
                    .status(ImageResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
        taskCache.put(taskId, response);
        return taskId;
    }

    @Override
    /** 查询任务 */
    public ImageResponse queryTask(String taskId) {
        ImageResponse response = taskCache.get(taskId);
        if (response == null) {
            return ImageResponse.builder()
                    .taskId(taskId)
                    .status(ImageResponse.Status.FAILED)
                    .errorMessage("未知 任务: " + taskId)
                    .build();
        }
        return response;
    }

    @Override
    /** 模型列表 */
    public List<ModelDefinition> models() {
        try {
            List<Model> raw = ollama.listModels();
            List<ModelDefinition> result = new ArrayList<>();
            if (raw != null) {
                for (Model m : raw) {
                    if (m == null || m.getName() == null || m.getName().isBlank()) {
                        continue;
                    }
                    result.add(ModelDefinition.builder()
                            .id(m.getName())
                            .name(m.getName())
                            .provider("ollama")
                            .description("本地 Ollama 模型")
                            .capabilities(List.of("image-generation"))
                            .build());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[Ollama] 获取 模型列表 异常: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        taskCache.clear();
    }

    /**
    * 转换 图片 为 PNG 字节。
    *
    * @param image 图片
    * @return PNG 字节 数组
    */
    private byte[] toBytes(BufferedImage image) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw OllamaSupport.wrap("image-encode", e);
        }
    }
}
