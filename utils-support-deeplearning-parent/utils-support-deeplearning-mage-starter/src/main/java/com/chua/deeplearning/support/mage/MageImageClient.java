package com.chua.deeplearning.support.mage;

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
* Microsoft Mage 图像生成客户端（SPI 提供者="mage"）。
*
* <p>通过 HTTP 调用<b>自部署</b>的 Mage 推理服务（见本模块 {@code scripts/server.py}），
* 后端为微软 Mage 家族的 4B 生成模型：
* <ul>
*   <li><b>Mage-Flow</b> — 文生图（Base / RL / 4 步 Turbo 变体）</li>
*   <li><b>Mage-Flow-Edit</b> — 指令图像编辑（设置参考图后自动切换编辑接口）</li>
* </ul>
*
* <p>服务端接口约定（OpenAI 风格子集，图片始终以 Base64 返回）：
* <pre>
* POST /v1/images/generations  {model, prompt, negative_prompt, width, height, steps, cfg, seed}
* POST /v1/images/edits        {model, prompt, images_b64[], max_size}
* GET  /v1/models
* </pre>
*
* <p>使用前提：先在 GPU 机器上部署 Mage 服务（Mage 官方仅发布 PyTorch 权重，
* 无法像其他模型一样以 ONNX 形式内嵌运行），然后配置 baseurl 指向该服务。
*
* <p>调用示例：
* <pre>{@code
*   // 文生图（Turbo 4 步）
*   BufferedImage image = ImageClient.create("mage", "sk-xxx")
*       .baseUrl("http://gpu-host:7861")
*       .model("mage-flow-turbo")
*       .size(1024, 1024)
*       .generate("一只柴犬在樱花树下");
*
*   // 指令编辑（带参考图自动走 edits 接口）
*   BufferedImage edited = ImageClient.create("mage", "sk-xxx")
*       .model("mage-flow-edit-turbo")
*       .referenceImage(Files.readAllBytes(Path.of("dog.jpg")))
*       .generate("把背景换成一片向日葵田");
* }</pre>nceImage(Files.readAllBytes(Path.of("dog.jpg")))
*       .generate("把背景换成一片向日葵田");
* }</pre>
*
* <p>模型 ID 约定：
* <ul>
*   <li>{@code mage-flow-base} — Mage-Flow-4B-Base，30 步</li>
*   <li>{@code mage-flow} — Mage-Flow-4B（RL 对齐），20 步</li>
*   <li>{@code mage-flow-turbo} — Mage-Flow-4B-Turbo，4 步蒸馏</li>
*   <li>{@code mage-flow-edit-base} — Mage-Flow-Edit-4B-Base，30 步</li>
*   <li>{@code mage-flow-edit} — Mage-Flow-Edit-4B（RL 对齐），30 步</li>
*   <li>{@code mage-flow-edit-turbo} — Mage-Flow-Edit-4B-Turbo，4 步蒸馏</li>
* </ul>
* 服务端按 标识 自动映射到对应的 🤗 Hub 权重（microsoft/Mage-流*）。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("mage")
public class MageImageClient implements ImageClient {

    /**
    * 默认服务地址
    */
    private static final String DEFAULT_URL = "http://127.0.0.1:7861";

    /**
    * 默认生成模型
    */
    private static final String DEFAULT_MODEL = "mage-flow-turbo";

    /**
    * 读超时（毫秒）：扩散模型推理 + 首次加载权重可能耗时较长
    */
    private static final long READ_TIMEOUT_MILLIS = 300_000L;

    /**
    * 客户端配置
    */
    private final ImageClientSetting setting;

    /**
    * 当前使用的模型 标识
    */
    private String model;

    /**
    * 输出宽度（像素），16 的倍数
    */
    private Integer width;

    /**
    * 输出高度（像素），16 的倍数
    */
    private Integer height;

    /**
    * 正向提示词（未通过方法参数传入时使用此值）
    */
    private String prompt;

    /**
    * 反向提示词
    */
    private String negativePrompt;

    /**
    * 随机种子；空 表示由服务端随机
    */
    private Long seed;

    /**
    * 去噪步数；覆盖模型默认值（基础 30 / RL 20 / Turbo 4）
    */
    private Integer steps;

    /**
    * CFG 引导系数；Turbo 模型应为 1.0
    */
    private Double cfg;

    /**
    * 编辑参考图列表（字节数据）；非空时 generate 走图像编辑接口
    */
    private final List<byte[]> referenceImages = new ArrayList<>();

    /**
    * 构造 Mage 图像生成客户端。
    *
    * @param setting 客户端配置
    */
    public MageImageClient(ImageClientSetting setting) {
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
    /** 模型 */
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 大小 */
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    /** 提示符 */
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    /** negative提示符 */
    public ImageClient negativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
        return this;
    }

    @Override
    /** Seed */
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    /** Steps */
    public ImageClient steps(Integer steps) {
        this.steps = steps;
        return this;
    }

    /**
    * 设置 CFG 引导系数。
    *
    * <p>Turbo 变体固定为 1.0（蒸馏模型不使用 CFG），Base/RL 通常取 5.0 左右。
    *
    * @param cfg CFG 引导系数
    * @return 当前客户端实例
    */
    public ImageClient cfg(double cfg) {
        this.cfg = cfg;
        return this;
    }

    @Override
    /** 引用镜像 */
    public ImageClient referenceImage(byte[] image) {
        if (image != null && image.length > 0) {
            this.referenceImages.add(image);
        }
        return this;
    }

    @Override
    /** 引用镜像 */
    public ImageClient referenceImage(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return referenceImage(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("参考图编码失败", e);
        }
    }

    @Override
    /** 镜像strength */
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("Mage-Flow-Edit 由指令驱动编辑强度，不支持 imageStrength");
    }

    @Override
    /** control类型 */
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("Mage-Flow 不支持 ControlNet");
    }

    @Override
    /** Generate */
    public BufferedImage generate(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        JsonObject body = JsonObject.create()
                .fluentPut("model", model != null ? model : DEFAULT_MODEL)
                .fluentPut("prompt", actualPrompt)
                .fluentPut(true, "negative_prompt", negativePrompt)
                .fluentPut("width", width != null ? width : 1024)
                .fluentPut("height", height != null ? height : 1024)
                .fluentPut(steps != null, "steps", steps)
                .fluentPut(cfg != null, "cfg", cfg)
                .fluentPut(seed != null, "seed", seed);

        boolean editMode = !referenceImages.isEmpty();
        List<String> imagesB64 = null;
        if (editMode) {
            imagesB64 = new ArrayList<>(referenceImages.size());
            for (byte[] ref : referenceImages) {
                imagesB64.add(Base64.getEncoder().encodeToString(ref));
            }
            body.fluentPut("images_b64", imagesB64);
        }

        String path = editMode ? "/v1/images/edits" : "/v1/images/generations";
        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path(path)
                .header("Authorization", buildAuthHeader())
                .json()
                .body(body.toJSONString())
                .connectTimeout(10_000L)
                .readTimeout(READ_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("Mage 图像请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        return parseFirstImage(resp.getBodyString(), path);
    }

    @Override
    /** 创建任务 */
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("Mage 服务为同步接口，请使用 generate()");
    }

    @Override
    /** 查询任务 */
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("Mage 服务为同步接口，请使用 generate()");
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return MODELS;
    }

    @Override
    /** 关闭 */
    public void close() {
        referenceImages.clear();
    }

    /**
    * 解析响应中的第一张 基础64 图片并解码为 {@link BufferedImage}。
    *
    * @param json 服务端 JSON 响应
    * @param path 请求路径（用于错误信息）
    * @return 解码后的图片
    * @throws RuntimeException 响应数据缺失或解码失败时抛出
    */
    @SuppressWarnings("unchecked")
    private BufferedImage parseFirstImage(String json, String path) {
        Map<String, Object> root;
        try {
            root = Json.fromJson(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Mage 响应解析失败: " + json, e);
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof List) || ((List<Object>) dataObj).isEmpty()) {
            throw new RuntimeException("Mage " + path + " 返回的图片数据为空: " + json);
        }
        Map<String, Object> first = (Map<String, Object>) ((List<Object>) dataObj).getFirst();
        Object b64 = first.get("b64_json");
        if (b64 == null || b64.toString().isBlank()) {
            throw new RuntimeException("Mage 响应缺少 b64_json 字段: " + json);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64.toString());
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new RuntimeException("Mage 图片解码失败", e);
        }
    }

    /**
    * 构建 授权 头。
    *
    * @return 已配置 app键 时返回 Bearer 头，否则返回空串（不携带认证）
    */
    private String buildAuthHeader() {
        String appKey = setting.getAppKey();
        return (appKey == null || appKey.isBlank()) ? "" : "Bearer " + appKey;
    }

    /**
    * 规范化服务基地址。
    *
    * <p>移除末尾斜杠与多余的 {@code /v1} 后缀（路径由客户端拼接），
    * 未配置时使用默认地址 {@value DEFAULT_URL}。
    *
    * @return 规范化后的 URL
    */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
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
    * Mage-流 全家族模型定义。
    */
    private static final List<ModelDefinition> MODELS = List.of(
            ModelDefinition.builder()
                    .id("mage-flow-base").name("Mage-Flow Base").provider("mage")
                    .description("Mage-Flow-4B-Base 文生图基础版，30 步")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("mage-flow").name("Mage-Flow RL").provider("mage")
                    .description("Mage-Flow-4B RL 对齐版文生图，20 步，GenEval 0.90 开源最佳")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("mage-flow-turbo").name("Mage-Flow Turbo").provider("mage")
                    .description("Mage-Flow-4B-Turbo 4 步蒸馏文生图，A100 约 0.59s/张")
                    .capabilities(List.of("text-to-image")).build(),
            ModelDefinition.builder()
                    .id("mage-flow-edit-base").name("Mage-Flow Edit Base").provider("mage")
                    .description("Mage-Flow-Edit-4B-Base 指令图像编辑基础版，30 步")
                    .capabilities(List.of("image-editing")).build(),
            ModelDefinition.builder()
                    .id("mage-flow-edit").name("Mage-Flow Edit RL").provider("mage")
                    .description("Mage-Flow-Edit-4B RL 对齐版指令图像编辑，30 步")
                    .capabilities(List.of("image-editing")).build(),
            ModelDefinition.builder()
                    .id("mage-flow-edit-turbo").name("Mage-Flow Edit Turbo").provider("mage")
                    .description("Mage-Flow-Edit-4B-Turbo 4 步蒸馏编辑，A100 约 1.02s/次")
                    .capabilities(List.of("image-editing")).build()
    );
}
