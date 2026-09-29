package com.chua.openai.support.video;

import com.chua.common.support.ai.video.VideoClient;
import com.chua.common.support.ai.video.VideoClientSetting;
import com.chua.common.support.ai.video.VideoResponse;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI Videos 兼容协议的 {@link VideoClient} 公共骨架。
 *
 * <p>创建侧（{@code POST {baseUrl}/videos}）在整个兼容族里是统一的，差异只出现在<em>轮询侧</em>：
 * 协议标准是 {@code GET {baseUrl}/videos/{id}}，而部分聚合站把轮询挪到了站点根路径
 * （如 Agnes 的 {@code /agnesapi?video_id=..&model_name=..}）。因此本类把创建、解析、
 * 分辨率换算等全部共用逻辑收在一处，只把「怎么把任务号取回来」抽象成
 * {@link #fetchTask(String)}，由子类各自实现——<b>不做「标准失败就回退」的运行时兜底</b>，
 * 那种写法会让标准实现替非标准实现背锅，也让 401/500 之类的真实错误被误判成路径不对。</p>
 *
 * <p><b>分辨率的形状差异</b>：本项目的 {@code VideoClient#size(int,int)} 传的是像素
 * {@code WxH}，而协议要的是「档位 + 宽高比」两个独立字段
 * （{@code size=720P|1080P|1K|2K} 与 {@code aspect_ratio=21:9|16:9|4:3|1:1|3:4|9:16}）。
 * 映射表见 {@link #SIZE_TABLE}，推导逻辑见 {@link #resolveAspectRatio()} 与
 * {@link #resolveTier(String)}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
abstract class AbstractOpenAiVideosClient implements VideoClient {

    /**
     * OpenAI 官方默认 API 地址。
     */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * 分辨率档位，按像素从低到高排列，与 {@link #SIZE_TABLE} 的列下标一一对应。
     *
     * <p>刻意不含 {@code 1K}：该档固定 1024x1024，与宽高比正交，
     * 放进按宽高选档的序列里只会把非方形需求误升成方片。</p>
     */
    private static final String[] SIZE_TIERS = {"720P", "1080P", "2K"};

    /**
     * 宽高比 → 各档位的输出像素（列顺序同 {@link #SIZE_TIERS}）。
     *
     * <p>取自协议官方像素表；{@code 2K} 为 {@code 720P} 的两倍。</p>
     */
    private static final Map<String, int[][]> SIZE_TABLE = Map.of(
            "21:9", new int[][]{{1470, 630}, {2206, 946}, {2940, 1260}},
            "16:9", new int[][]{{1280, 720}, {1920, 1080}, {2560, 1440}},
            "4:3", new int[][]{{1112, 834}, {1664, 1248}, {2224, 1668}},
            "1:1", new int[][]{{960, 960}, {1440, 1440}, {1920, 1920}},
            "3:4", new int[][]{{834, 1112}, {1248, 1664}, {1668, 2224}},
            "9:16", new int[][]{{720, 1280}, {1080, 1920}, {1440, 2560}});

    /**
     * 协议允许的时长下界（秒，含端点）。
     */
    private static final int MIN_SECONDS = 4;

    /**
     * 协议允许的时长上界（秒，含端点）。
     */
    private static final int MAX_SECONDS = 12;

    /**
     * 客户端配置。
     */
    private final VideoClientSetting setting;

    /**
     * 当前使用的模型名称。
     */
    private String model;

    /**
     * 视频宽度（像素）。
     */
    private Integer width;

    /**
     * 视频高度（像素）。
     */
    private Integer height;

    /**
     * 提示词（未通过方法参数传入时使用此值）。
     */
    private String prompt;

    /**
     * 反向提示词。
     */
    private String negativePrompt;

    /**
     * 视频时长（秒）。
     */
    private Integer duration;

    /**
     * 视频风格描述。
     */
    private String style;

    /**
     * 随机种子。
     */
    private Long seed;

    /**
     * 构造视频生成客户端。
     *
     * @param setting 客户端配置，含 provider、appKey、baseUrl
     */
    AbstractOpenAiVideosClient(VideoClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.duration = setting.getDuration();
        this.style = setting.getStyle();
        this.negativePrompt = setting.getNegativePrompt();
        this.seed = setting.getSeed();
    }

    /**
     * 拉取任务状态的原始响应。
     *
     * <p>子类按各自协议的轮询端点实现；返回非 2xx 的响应也应原样返回，
     * 由 {@link #queryTask(String)} 统一转成失败态。</p>
     *
     * @param taskId {@link #createTask(String)} 返回的任务标识
     * @return 原始响应
     */
    abstract ClientResponse fetchTask(String taskId);

    /**
     * 取当前模型名（子类的轮询请求可能需要它作为查询参数）。
     *
     * @return 模型名，可为 null
     */
    final String currentModel() {
        return model;
    }

    /**
     * 取客户端配置。
     *
     * <p>供子类在需要派生另一个实现时复用同一套凭证与地址，
     * 避免把 {@code setting} 暴露成可变字段。</p>
     *
     * @return 客户端配置，永不为 null
     */
    final VideoClientSetting currentSetting() {
        return setting;
    }

    @Override
    public final VideoClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public final VideoClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    public final VideoClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    public final VideoClient negativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
        return this;
    }

    @Override
    public final VideoClient duration(Integer duration) {
        this.duration = duration;
        return this;
    }

    @Override
    public final VideoClient style(String style) {
        this.style = style;
        return this;
    }

    @Override
    public final VideoClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public final String createTask(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String requestBody = buildRequestBody(
                model != null ? model : "sora-2",
                composePrompt(actualPrompt),
                videoMode(),
                clampSeconds(duration),
                resolveTier(resolveAspectRatio()),
                resolveAspectRatio(),
                seed).toJSONString();
        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path("/videos")
                .header("Authorization", authHeader())
                .json()
                .body(requestBody)
                .connectTimeout(120000)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("视频生成请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        Map<String, Object> root = Json.fromJson(resp.getBodyString(), Map.class);
        // video_id 才是取结果的键；id / task_id 是同一个任务号，兼容只回传其中一种的渠道
        String taskId = firstNonBlank(str(root.get("video_id")), str(root.get("id")), str(root.get("task_id")));
        if (taskId == null) {
            throw new RuntimeException("视频生成未返回任务 ID: " + resp.getBodyString());
        }
        return taskId;
    }

    /**
     * 本次请求使用的生成模式。
     *
     * <p>协议的 {@code mode} 为必填项，且与参考图字段强绑定：{@code keyframe}
     * 必须同时给 {@code first_frame}，否则判 400。默认子类无参考图能力，一律文生视频；
     * 支持图生视频的子类覆盖此方法。</p>
     *
     * @return 生成模式
     */
    String videoMode() {
        return "text";
    }

    /**
     * 构建创建任务的请求体。
     *
     * <p>默认实现只发文生视频字段。支持参考图的子类覆盖此方法以追加媒体字段。
     * 注意协议要求 {@code seconds} 为<b>字符串</b>、{@code size} 为<b>档位</b>而非像素。</p>
     *
     * @param model       模型名
     * @param prompt      提示词（已合并风格与反向描述）
     * @param mode        生成模式
     * @param seconds     时长（秒，已收敛到协议区间）
     * @param tier        分辨率档位
     * @param aspectRatio 宽高比
     * @param seed        随机种子，可为 null
     * @return 请求体构造器
     */
    JsonObject buildRequestBody(String model, String prompt, String mode, int seconds,
            String tier, String aspectRatio, Long seed) {
        return JsonObject.create()
                .fluentPut("model", model)
                .fluentPut("prompt", prompt)
                .fluentPut("mode", mode)
                .fluentPut("seconds", String.valueOf(seconds))
                .fluentPut("size", tier)
                .fluentPut("aspect_ratio", aspectRatio)
                .fluentPut(seed != null, "seed", seed);
    }

    @Override
    public final VideoResponse queryTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        try {
            ClientResponse resp = fetchTask(taskId);
            if (!resp.isSuccess()) {
                return VideoResponse.builder().taskId(taskId).status(VideoResponse.Status.FAILED)
                        .errorMessage("查询任务状态失败: " + resp.getStatusCode() + " - " + resp.getBodyString())
                        .build();
            }
            return parseTask(taskId, Json.fromJson(resp.getBodyString(), Map.class));
        } catch (Exception e) {
            log.error("查询视频生成任务失败 taskId={}: {}", taskId, e.getMessage(), e);
            return VideoResponse.builder().taskId(taskId).status(VideoResponse.Status.FAILED)
                    .errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public void close() {
        // HttpClientFactory 创建的 HTTP 客户端由框架管理，无需手动关闭
    }

    /**
     * 解析轮询响应。
     *
     * @param taskId 任务标识
     * @param root 响应 JSON 根节点
     * @return 任务状态视图
     */
    private VideoResponse parseTask(String taskId, Map<String, Object> root) {
        VideoResponse.Status status = mapStatus(str(root.get("status")));
        VideoResponse.VideoResponseBuilder builder = VideoResponse.builder().taskId(taskId).status(status);
        Object progress = root.get("progress");
        if (progress instanceof Number number) {
            builder.progress(number.intValue());
        }
        Object seconds = root.get("seconds");
        if (seconds instanceof Number number) {
            builder.duration(number.intValue());
        }
        if (status == VideoResponse.Status.SUCCESS) {
            String url = str(root.get("url"));
            if (url == null) {
                // 少数渠道把产物放在 data[] 里，取第一个非空项
                url = firstResultUrl(root);
            }
            builder.videoUrl(url);
        } else if (status == VideoResponse.Status.FAILED) {
            builder.errorMessage(extractError(root));
        }
        return builder.build();
    }

    /**
     * 从响应中取出产物 URL（兼容 {@code data[]} 形态）。
     *
     * @param root 响应 JSON 根节点
     * @return 产物地址，取不到返回 null
     */
    private String firstResultUrl(Map<String, Object> root) {
        Object data = root.get("data");
        if (data instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> item) {
            return str(item.get("url"));
        }
        return null;
    }

    /**
     * 提取失败原因。
     *
     * @param root 响应 JSON 根节点
     * @return 可读错误文案
     */
    private String extractError(Map<String, Object> root) {
        Object error = root.get("error");
        if (error instanceof Map<?, ?> map) {
            String message = str(map.get("message"));
            if (message != null) {
                return message;
            }
        }
        String text = str(error);
        return text != null ? text : "任务执行失败";
    }

    /**
     * 映射任务状态。
     *
     * @param status 协议返回的状态串
     * @return 统一状态枚举
     */
    private static VideoResponse.Status mapStatus(String status) {
        if (status == null || status.isBlank()) {
            return VideoResponse.Status.PENDING;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "completed", "succeeded", "success", "successful" -> VideoResponse.Status.SUCCESS;
            case "failed", "fail", "error", "cancelled", "canceled" -> VideoResponse.Status.FAILED;
            case "in_progress", "processing", "running", "generating" -> VideoResponse.Status.RUNNING;
            default -> VideoResponse.Status.PENDING;
        };
    }

    /**
     * 拼装最终提示词。
     *
     * <p>协议的 {@code /v1/videos} 请求体里<em>没有</em> {@code style} 与
     * {@code negative_prompt} 字段（传了会 400）。但调用方仍会链式设置这两个值，
     * 直接丢弃等于静默吃掉用户输入，故在此合并进提示词文本。</p>
     *
     * @param base 原始提示词
     * @return 合并风格与反向描述后的提示词
     */
    private String composePrompt(String base) {
        StringBuilder sb = new StringBuilder(base);
        if (style != null && !style.isBlank()) {
            sb.append("，画面风格：").append(style);
        }
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            sb.append("。避免出现：").append(negativePrompt);
        }
        return sb.toString();
    }

    /**
     * 收敛时长到协议允许区间。
     *
     * <p>协议只接受 {@value #MIN_SECONDS}~{@value #MAX_SECONDS} 秒的字符串，越界直接 400。
     * 模型配置里声明的 {@code durations} 正常不会越界，这里兜底。</p>
     *
     * @param seconds 期望时长（秒），可为 null
     * @return 区间内的时长
     */
    private static int clampSeconds(Integer seconds) {
        int value = seconds == null ? MIN_SECONDS : seconds;
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
    }

    /**
     * 由像素宽高推协议宽高比。
     *
     * @return {@code SIZE_TABLE} 中最接近的键
     */
    private String resolveAspectRatio() {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return "16:9";
        }
        double target = (double) width / height;
        String best = "16:9";
        double bestDiff = Double.MAX_VALUE;
        for (String ratio : SIZE_TABLE.keySet()) {
            double candidate = parseRatio(ratio);
            double diff = Math.abs(target - candidate) / candidate;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = ratio;
            }
        }
        return best;
    }

    /**
     * 由像素宽高推协议分辨率档位。
     *
     * <p>取该宽高比下<em>能容纳</em>目标像素的最低档；都比目标小则取最高档 2K。</p>
     *
     * @param aspectRatio 协议宽高比
     * @return 分辨率档位
     */
    private String resolveTier(String aspectRatio) {
        int[][] dims = SIZE_TABLE.get(aspectRatio);
        if (dims == null || width == null || height == null || width <= 0 || height <= 0) {
            return SIZE_TIERS[0];
        }
        for (int i = 0; i < SIZE_TIERS.length; i++) {
            if (dims[i][0] >= width && dims[i][1] >= height) {
                return SIZE_TIERS[i];
            }
        }
        return SIZE_TIERS[SIZE_TIERS.length - 1];
    }

    /**
     * 解析 {@code "16:9"} 形式的比值为数值。
     *
     * @param ratio 比值文本
     * @return 宽除以高的数值
     */
    private static double parseRatio(String ratio) {
        String[] parts = ratio.split(":");
        return (double) Integer.parseInt(parts[0].trim()) / Integer.parseInt(parts[1].trim());
    }

    /**
     * 取第一个非空白字符串。
     *
     * @param values 候选值
     * @return 第一个非空白项，全为空白返回 null
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 取对象的字符串形式，null 与空白串统一归为 null。
     *
     * @param value 任意对象
     * @return 非空白字符串，否则 null
     */
    private static String str(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * URL 编码查询参数。
     *
     * @param value 原始值
     * @return 编码后的值
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 取授权请求头值。
     *
     * @return {@code Bearer <key>}
     */
    final String authHeader() {
        return "Bearer " + setting.getAppKey();
    }

    /**
     * 规范化 API 基础地址。
     *
     * <p>去掉末尾多余斜杠，未配置时用默认地址。</p>
     *
     * @return 规范化后的基础地址
     */
    final String normalizeBaseUrl() {
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
