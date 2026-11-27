package com.chua.deeplearning.support.wevisone;

import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.image.UnderstandResult;
import com.chua.deeplearning.support.image.UnderstandTask;
import com.chua.deeplearning.support.image.VlmBox;
import com.chua.deeplearning.support.image.VlmClient;
import com.chua.deeplearning.support.image.VlmMask;
import com.chua.deeplearning.support.image.VlmObject;
import com.chua.deeplearning.support.image.VlmPoint;
import com.chua.deeplearning.support.image.VlmRequest;
import com.chua.deeplearning.support.image.VlmResult;
import com.chua.deeplearning.support.image.VlmTask;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * WeVisOne-VL 指令式视觉推理客户端（远程 HTTP 实现）。
 *
 * <p>本模块不内嵌权重、不依赖任何推理引擎：真正的推理跑在独立的 Python 服务里
 * （见本模块 {@code python/}），本类只做「契约 ↔ 协议」的映射，因此可以在任何
 * JVM 里使用，也可以同时对接多台推理机（每个实例一个 {@code baseUrl}）。
 *
 * <p>典型用法：
 * <pre>{@code
 * VlmClient client = VlmClient.create("wevisone").model("WeVisOne-VL-2B");
 * VlmResult r = ((WevisoneVlClient) client)
 *         .baseUrl("http://192.168.50.192:8765")
 *         .infer(VlmRequest.of(pngBytes, VlmTask.DETECTION, "cat\nperson"));
 * // r.objects() 目标的 box 为归一化 0..1；r.targetMissing() 表示模型回答了 None
 * }</pre>
 *
 * <p>{@link #understand(byte[], UnderstandTask)} 是旧契约的兼容入口：WeVisOne-VL 是指令式模型，
 * Florence-2 的固定前缀任务大多没有等价语义，因此只映射 OCR 与三种 caption，
 * 其余任务明确抛 {@link UnsupportedOperationException}，不做「猜一个相近任务」的静默降级。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi("wevisone")
@Slf4j
public class WevisoneVlClient implements VlmClient {

    /**
     * 默认模型标识（与官方 2B 权重目录名一致）
     */
    public static final String DEFAULT_MODEL = "WeVisOne-VL-2B";

    /**
     * Florence-2 caption 任务映射到 WeVisOne-VL 的 freeform 提示词
     * （措辞取自官方 demo 的 CAPTION_PRESET_PROMPTS）
     */
    private static final Map<UnderstandTask, String> CAPTION_PROMPTS = Map.of(
            UnderstandTask.CAPTION, "describe the image with bounding boxes in detail",
            UnderstandTask.DETAILED_CAPTION, "describe the image with bounding boxes in detail",
            UnderstandTask.MORE_DETAILED_CAPTION, "describe the image with bounding boxes in great detail");

    /**
     * 当前模型标识（仅作为协议字段回传，服务端决定实际权重）
     */
    private String modelName = DEFAULT_MODEL;

    /**
     * 服务地址
     */
    private String baseUrl = WevisoneHttpClient.DEFAULT_URL;

    /**
     * 推理超时（秒）
     */
    private int inferTimeoutSeconds = WevisoneHttpClient.DEFAULT_INFER_TIMEOUT_SECONDS;

    /**
     * HTTP 客户端（首次使用时创建；改地址/超时会置空以重建）
     */
    private volatile WevisoneHttpClient httpClient;

    /**
     * 无参构造器（SPI 反射入口）。
     */
    public WevisoneVlClient() {
    }

    /**
     * 构造器。
     *
     * @param baseUrl 服务地址
     */
    public WevisoneVlClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 设置服务地址（链式）。
     *
     * @param baseUrl 服务地址，如 {@code http://192.168.50.192:8765}
     * @return 当前实例
     */
    public WevisoneVlClient baseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("WeVisOne-VL 服务地址不能为空");
        }
        this.baseUrl = baseUrl.trim();
        this.httpClient = null;
        return this;
    }

    /**
     * 设置推理超时（链式）。
     *
     * @param seconds 超时秒数，必须大于 0
     * @return 当前实例
     */
    public WevisoneVlClient timeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("推理超时必须大于 0 秒: " + seconds);
        }
        this.inferTimeoutSeconds = seconds;
        this.httpClient = null;
        return this;
    }

    /**
     * 服务是否就绪（可达且模型已加载）。
     *
     * @return 就绪返回 true
     */
    public boolean available() {
        return client().isReady();
    }

    /**
     * 当前服务地址。
     *
     * @return 服务地址
     */
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public VlmClient model(String model) {
        if (model != null && !model.isBlank()) {
            this.modelName = model.trim();
        }
        return this;
    }

    /**
     * 当前模型标识。
     *
     * @return 模型标识
     */
    public String modelName() {
        return modelName;
    }

    /**
     * 旧契约入口：把 Florence-2 的任务前缀翻译成本模型的指令。
     *
     * <p>只支持 {@code OCR}/{@code OCR_WITH_REGION}（→ {@code ocr}）与三种 caption
     * （→ {@code freeform}）；其余 Florence-2 专有任务（区域标注、指代分割前缀等）
     * 在本模型上没有等价语义，直接抛 {@link UnsupportedOperationException}。
     *
     * @param imageData 图像字节
     * @param task      Florence-2 任务
     * @return 理解结果（仅文本，结构化结果请用 {@link #infer(VlmRequest)}）
     */
    @Override
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        if (imageData == null || imageData.length == 0) {
            throw new IllegalArgumentException("图像数据不能为空");
        }
        if (task == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        final VlmRequest request;
        if (task == UnderstandTask.OCR || task == UnderstandTask.OCR_WITH_REGION) {
            request = VlmRequest.of(imageData, VlmTask.OCR, null);
        } else {
            String prompt = CAPTION_PROMPTS.get(task);
            if (prompt == null) {
                throw new UnsupportedOperationException("WeVisOne-VL 不支持 Florence-2 任务 " + task
                        + "：本模型是指令式模型，请改用 infer(VlmRequest) 指定 VlmTask");
            }
            request = new VlmRequest(imageData, VlmTask.FREEFORM, null, prompt, List.of(), false);
        }
        return new UnderstandResult(task, infer(request).text());
    }

    /**
     * 指令式视觉推理。
     *
     * @param request 推理请求
     * @return 结构化结果
     * @throws IllegalStateException 推理服务不可达或返回错误
     */
    @Override
    public VlmResult infer(VlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("推理请求不能为空");
        }
        JsonObject response = client().infer(request.imageData(), request);
        return new VlmResult(request.task(), text(response), objects(response), masks(response), latency(response));
    }

    /**
     * 获取（必要时创建）HTTP 客户端。
     *
     * @return HTTP 客户端
     */
    private WevisoneHttpClient client() {
        WevisoneHttpClient current = this.httpClient;
        if (current == null) {
            synchronized (this) {
                current = this.httpClient;
                if (current == null) {
                    current = new WevisoneHttpClient(baseUrl, inferTimeoutSeconds);
                    this.httpClient = current;
                }
            }
        }
        return current;
    }

    /**
     * 提取响应文本。
     *
     * @param response 响应 JSON
     * @return 文本（缺省为空串）
     */
    private static String text(JsonObject response) {
        String value = string(response.get("text"));
        log.debug("[WeVisOne] 推理完成，响应长度 {}", value.length());
        return value;
    }

    /**
     * 解析结构化目标：优先读 {@code objects}（带标签），并兼容扁平的 {@code boxes}/{@code points}。
     *
     * @param response 响应 JSON
     * @return 目标列表
     */
    private static List<VlmObject> objects(JsonObject response) {
        List<VlmObject> result = new ArrayList<>();
        for (Object item : collection(response.get("objects"))) {
            if (!(item instanceof Map<?, ?> object)) {
                continue;
            }
            VlmBox box = box(object.get("box"));
            VlmPoint point = point(object.get("point"));
            if (box == null && point == null) {
                continue;
            }
            result.add(new VlmObject(string(object.get("label")), box, point));
        }
        if (!result.isEmpty()) {
            return result;
        }
        for (Object item : collection(response.get("boxes"))) {
            VlmBox box = box(item);
            if (box != null) {
                result.add(VlmObject.ofBox(null, box));
            }
        }
        for (Object item : collection(response.get("points"))) {
            VlmPoint point = point(item);
            if (point != null) {
                result.add(VlmObject.ofPoint(null, point));
            }
        }
        return result;
    }

    /**
     * 解析 alpha 掩码（8 位灰度 PNG，base64 承载）。
     *
     * @param response 响应 JSON
     * @return 掩码列表；缺字段或解码失败时跳过该条目（不伪造空掩码）
     */
    private static List<VlmMask> masks(JsonObject response) {
        List<VlmMask> result = new ArrayList<>();
        for (Object item : collection(response.get("masks"))) {
            if (!(item instanceof Map<?, ?> mask)) {
                continue;
            }
            Integer width = integer(mask.get("width"));
            Integer height = integer(mask.get("height"));
            String png = string(firstNonNull(mask.get("png"), mask.get("png_base64")));
            if (width == null || height == null || width <= 0 || height <= 0 || png.isEmpty()) {
                log.debug("[WeVisOne] 跳过无效掩码条目: {}", mask);
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(png);
            } catch (IllegalArgumentException e) {
                log.debug("[WeVisOne] 掩码 base64 解码失败: {}", e.getMessage());
                continue;
            }
            if (bytes.length == 0) {
                continue;
            }
            result.add(new VlmMask(width, height, bytes));
        }
        return result;
    }

    /**
     * 提取推理耗时。
     *
     * @param response 响应 JSON
     * @return 耗时（秒），缺省或非法为 0
     */
    private static double latency(JsonObject response) {
        Double value = decimal(response.get("latency_s"));
        return value == null ? 0D : value;
    }

    /**
     * 解析归一化框 {@code [x1,y1,x2,y2]}。
     *
     * @param raw 原始值（数组）
     * @return 框；结构不符返回 null
     */
    private static VlmBox box(Object raw) {
        List<Double> values = decimals(raw);
        if (values.size() < 4) {
            return null;
        }
        return new VlmBox(values.get(0), values.get(1), values.get(2), values.get(3));
    }

    /**
     * 解析归一化点 {@code [x,y]}。
     *
     * @param raw 原始值（数组）
     * @return 点；结构不符返回 null
     */
    private static VlmPoint point(Object raw) {
        List<Double> values = decimals(raw);
        if (values.size() < 2) {
            return null;
        }
        return new VlmPoint(values.get(0), values.get(1));
    }

    /**
     * 把数组值解析为数值列表（不足或含非数值项时截断）。
     *
     * @param raw 原始值
     * @return 数值列表
     */
    private static List<Double> decimals(Object raw) {
        List<Double> values = new ArrayList<>();
        for (Object item : collection(raw)) {
            Double value = decimal(item);
            if (value == null) {
                return List.of();
            }
            values.add(value);
        }
        return values;
    }

    /**
     * 集合化：数组/集合转 {@link Collection}，null 转空集合。
     *
     * @param raw 原始值
     * @return 集合
     */
    private static Collection<?> collection(Object raw) {
        if (raw instanceof Collection<?> values) {
            return values;
        }
        if (raw instanceof Object[] values) {
            return List.of(values);
        }
        return List.of();
    }

    /**
     * 取字符串值。
     *
     * @param raw 原始值
     * @return 字符串；null 为空串
     */
    private static String string(Object raw) {
        if (raw == null) {
            return "";
        }
        return String.valueOf(raw);
    }

    /**
     * 取整数值。
     *
     * @param raw 原始值
     * @return 整数；无法解析返回 null
     */
    private static Integer integer(Object raw) {
        Double value = decimal(raw);
        return value == null ? null : (int) Math.round(value);
    }

    /**
     * 取数值。
     *
     * @param raw 原始值
     * @return 数值；无法解析返回 null
     */
    private static Double decimal(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 取第一个非 null 值。
     *
     * @param values 候选值
     * @return 第一个非 null 值，全为 null 时返回 null
     */
    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
