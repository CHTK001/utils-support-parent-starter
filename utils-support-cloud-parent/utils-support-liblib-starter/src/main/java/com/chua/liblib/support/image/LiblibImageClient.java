package com.chua.liblib.support.image;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LiblibAI（哩布哩布 AI / 星流）图像生成客户端
 *
 * <p>基于 LiblibAI 开放平台（{@code https://openapi.liblibai.cloud}）的
 * {@link ImageClient} 实现，走官方「星流 ultra」异步任务协议：
 * 先 {@code POST /api/generate/webui/text2img/ultra} 提交任务拿到
 * {@code generateUuid}，再轮询 {@code POST /api/generate/webui/status}
 * 直至 {@code generateStatus=5}（成功），最后下载 {@code images[0].imageUrl}。</p>
 *
 * <p>鉴权方式为<b>签名 query 参数</b>（不是 Header）：
 * 每次请求在 query 上携带 {@code AccessKey}、{@code Timestamp}（毫秒，5 分钟有效）、
 * {@code SignatureNonce}（随机串）与 {@code Signature}。签名算法：
 * {@code Signature = base64url(HmacSHA1(SecretKey, uri + "&" + timestamp + "&" + nonce))}
 * ，其中 {@code uri} 为不含域名与 query 的接口路径（如
 * {@code /api/generate/webui/text2img/ultra}），base64url 不补 {@code =}。</p>
 *
 * <p>协议约束（官方文档）：
 * <ul>
 *     <li>星流 ultra 的 {@code generateParams} 仅支持 {@code prompt / aspectRatio /
 *     imageSize / imgCount / steps / controlnet}，<b>没有</b> {@code negativePrompt}
 *     与 {@code seed}（这两者仅自定义模型 API 支持），本实现忽略之；</li>
 *     <li>{@code prompt} 必填，官方要求纯英文、不超过 2000 字符；</li>
 *     <li>{@code imageSize} 每边 512–2048，与 {@code aspectRatio} 二选一，
 *     本实现按显式宽高钳制到该区间后传 {@code imageSize}；</li>
 *     <li>{@code percentCompleted} 官方「暂未实现」恒为 0，因此
 *     {@link #queryTask(String)} 的 progress 恒返 {@code null}（返 0 会把上层
 *     任务进度反复打回 0）；</li>
 *     <li>图生图（img2img）要求 {@code sourceImage} 为<b>公网可访问 URL</b>，
 *     而开放平台未提供文件上传接口（官方 java-sdk 0.0.9 亦无），SPI 契约只传
 *     字节，故 {@link #supportsReferenceImage()} 保持默认 {@code false}，
 *     参考图相关方法显式抛不支持；</li>
 *     <li>生图任务默认并发 5、提交接口 QPS 1 秒 1 次（查询无限制）。</li>
 * </ul></p>
 *
 * <p>凭据映射：{@code appKey} = AccessKey，{@code secretKey} = SecretKey。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi("liblib")
public class LiblibImageClient implements ImageClient {

    /**
     * LiblibAI 开放平台默认 API 地址
     */
    private static final String DEFAULT_URL = "https://openapi.liblibai.cloud";

    /**
     * 星流 ultra 文生图提交接口
     */
    private static final String PATH_TEXT2IMG_ULTRA = "/api/generate/webui/text2img/ultra";

    /**
     * 星流 ultra 状态查询接口
     */
    private static final String PATH_STATUS = "/api/generate/webui/status";

    /**
     * 星流 ultra 文生图模板 UUID（官方示例同款）
     */
    private static final String TEMPLATE_TEXT2IMG_ULTRA = "5d7e67009b344550bc1aa6ccbfa1d7f4";

    /**
     * 提交接口连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    /**
     * 提交/查询接口读超时（毫秒）
     */
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    /**
     * 图片下载连接超时（毫秒）
     */
    private static final int IMAGE_CONNECT_TIMEOUT_MILLIS = 30_000;

    /**
     * 图片下载读超时（毫秒）
     */
    private static final int IMAGE_READ_TIMEOUT_MILLIS = 120_000;

    /**
     * 同步 generate 的轮询间隔（毫秒）
     *
     * <p>官方建议 3~5 秒；查询接口 QPS 无限制，取 3 秒平衡时效与开销。</p>
     */
    private static final long POLL_INTERVAL_MILLIS = 3_000L;

    /**
     * 同步 generate 的轮询上限（毫秒）
     *
     * <p>与 Moss 口径一致：出图官方建议超时不低于 900 秒。</p>
     */
    private static final long POLL_TIMEOUT_MILLIS = 900_000L;

    /**
     * imageSize 每边下限（像素）
     */
    private static final int MIN_SIZE = 512;

    /**
     * imageSize 每边上限（像素）
     */
    private static final int MAX_SIZE = 2048;

    /**
     * 默认宽度
     */
    private static final int DEFAULT_WIDTH = 1024;

    /**
     * 默认高度
     */
    private static final int DEFAULT_HEIGHT = 1024;

    /**
     * prompt 最大长度（字符）
     */
    private static final int MAX_PROMPT_LENGTH = 2000;

    /**
     * generateStatus：等待
     */
    private static final int GEN_STATUS_PENDING = 1;

    /**
     * generateStatus：成功
     */
    private static final int GEN_STATUS_SUCCEED = 5;

    /**
     * generateStatus：失败
     */
    private static final int GEN_STATUS_FAILED = 6;

    /**
     * generateStatus：超时
     */
    private static final int GEN_STATUS_TIMEOUT = 7;

    /**
     * 随机数发生器（SignatureNonce 用）
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 客户端配置
     */
    private final ImageClientSetting setting;

    /**
     * 当前使用的模型名称（星流 ultra 为模板化模型，名称仅作日志）
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
     * 当前推理步数
     */
    private Integer steps;

    /**
     * 构造 LiblibAI 图像生成客户端
     *
     * @param setting 客户端配置
     */
    public LiblibImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.prompt = setting.getPrompt();
        this.steps = setting.getSteps();
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
     * 大小
     */
    @Override
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * 提示词
     */
    @Override
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
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
     * negativePrompt（星流 ultra 协议无该参数，忽略并保持链式调用兼容）
     */
    @Override
    public ImageClient negativePrompt(String negativePrompt) {
        return this;
    }

    /**
     * Seed（星流 ultra 协议无该参数，忽略并保持链式调用兼容）
     */
    @Override
    public ImageClient seed(Long seed) {
        return this;
    }

    /**
     * Quality（LiblibAI 平台暂不支持该参数，忽略并保持链式调用兼容）
     */
    @Override
    public ImageClient quality(String quality) {
        return this;
    }

    /**
     * Style（LiblibAI 平台暂不支持该参数，忽略并保持链式调用兼容）
     */
    @Override
    public ImageClient style(String style) {
        return this;
    }

    /**
     * 参考图（星流 img2img 要求公网 URL，开放平台无上传接口，SPI 字节无法落地）
     */
    @Override
    public ImageClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException(
                "LiblibAI 图生图要求公网可访问的图片 URL，开放平台未提供文件上传接口，暂不支持参考图");
    }

    /**
     * 参考图（星流 img2img 要求公网 URL，开放平台无上传接口，SPI 字节无法落地）
     */
    @Override
    public ImageClient referenceImage(BufferedImage image) {
        throw new UnsupportedOperationException(
                "LiblibAI 图生图要求公网可访问的图片 URL，开放平台未提供文件上传接口，暂不支持参考图");
    }

    /**
     * 参考图强度（依赖图生图，暂不支持）
     */
    @Override
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("LiblibAI 暂不支持参考图强度（无文件上传接口）");
    }

    /**
     * ControlNet 类型（controlImage 同样要求公网 URL，暂不支持）
     */
    @Override
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("LiblibAI 暂不支持 ControlNet（无文件上传接口）");
    }

    /**
     * Generate（同步生成：提交 + 轮询 + 下载）
     */
    @Override
    public BufferedImage generate(String prompt) {
        String taskId = createTask(prompt);
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LiblibAI 生图轮询被中断", e);
            }
            ImageResponse resp = queryTask(taskId);
            if (resp.getStatus() == ImageResponse.Status.SUCCESS) {
                return downloadImage(resp.getImageUrl());
            }
            if (resp.getStatus() == ImageResponse.Status.FAILED) {
                throw new RuntimeException("LiblibAI 生图任务失败: " + resp.getErrorMessage());
            }
        }
        throw new RuntimeException("LiblibAI 生图任务超时（超过 " + (POLL_TIMEOUT_MILLIS / 1000) + " 秒）");
    }

    /**
     * Create task（提交星流 ultra 文生图任务）
     */
    @Override
    @SuppressWarnings("unchecked")
    public String createTask(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (StringUtils.isBlank(actualPrompt)) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        if (actualPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("提示词超过 " + MAX_PROMPT_LENGTH + " 字符上限");
        }
        String requestBody = buildText2ImgBody(actualPrompt);
        log.debug("LiblibAI 提交生图任务: model={}, body={}", model, requestBody);
        ClientResponse response = post(PATH_TEXT2IMG_ULTRA, requestBody);
        Map<String, Object> root = parseEnvelope(response, "提交生图任务");
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        if (data == null || StringUtils.isBlank((String) data.get("generateUuid"))) {
            throw new RuntimeException("LiblibAI 提交生图任务未返回 generateUuid: " + response.getBodyString());
        }
        return (String) data.get("generateUuid");
    }

    /**
     * Query task（查询任务状态与结果）
     */
    @Override
    @SuppressWarnings("unchecked")
    public ImageResponse queryTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        ClientResponse response = post(PATH_STATUS, "{\"generateUuid\":\"" + escapeJson(taskId) + "\"}");
        Map<String, Object> root = parseEnvelope(response, "查询生图任务");
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        if (data == null) {
            throw new RuntimeException("LiblibAI 查询生图任务返回数据为空: " + response.getBodyString());
        }
        ImageResponse.Status status = mapStatus(data.get("generateStatus"));
        ImageResponse.ImageResponseBuilder builder = ImageResponse.builder()
                .taskId(taskId)
                .status(status)
                // percentCompleted 官方「暂未实现」恒 0：progress 必须保持 null，
                // 否则上层会把任务进度反复打回 0%
                .progress(null);
        if (status == ImageResponse.Status.SUCCESS) {
            applySuccess(builder, data);
        } else if (status == ImageResponse.Status.FAILED) {
            Object msg = data.get("generateMsg");
            builder.errorMessage(msg != null ? msg.toString() : "生成失败（generateStatus=" + data.get("generateStatus") + "）");
        }
        return builder.build();
    }

    /**
     * Close
     */
    @Override
    public void close() {
    }

    /**
     * 填充成功态结果：取第一张审核通过的图片 URL 与 seed，并把积分消耗映射进用量。
     *
     * @param builder 响应构建器
     * @param data    状态接口 data 节点
     */
    @SuppressWarnings("unchecked")
    private void applySuccess(ImageResponse.ImageResponseBuilder builder, Map<String, Object> data) {
        Object imagesObj = data.get("images");
        if (imagesObj instanceof List<?> images && !images.isEmpty() && images.get(0) instanceof Map) {
            Map<String, Object> image = (Map<String, Object>) images.get(0);
            builder.imageUrl((String) image.get("imageUrl"));
            Object seed = image.get("seed");
            if (seed instanceof Number number) {
                builder.seed(number.longValue());
            }
        }
        Object pointsCost = data.get("pointsCost");
        if (pointsCost instanceof Number number) {
            builder.usage(AiUsage.builder()
                    .totalCost(BigDecimal.valueOf(number.doubleValue()))
                    .build());
        }
    }

    /**
     * 构建星流 ultra 文生图请求体
     *
     * @param actualPrompt 实际使用的提示词
     * @return JSON 请求体
     */
    private String buildText2ImgBody(String actualPrompt) {
        int w = clampSize(width != null ? width : DEFAULT_WIDTH);
        int h = clampSize(height != null ? height : DEFAULT_HEIGHT);
        int actualSteps = steps != null ? steps : 30;
        return "{\"templateUuid\":\"" + TEMPLATE_TEXT2IMG_ULTRA
                + "\",\"generateParams\":{\"prompt\":\"" + escapeJson(actualPrompt)
                + "\",\"imgCount\":1"
                + ",\"steps\":" + actualSteps
                + ",\"imageSize\":{\"width\":" + w + ",\"height\":" + h + "}"
                + "}}";
    }

    /**
     * 发起带签名 query 的 POST 请求
     *
     * @param uri      接口路径（不含域名与 query）
     * @param jsonBody JSON 请求体
     * @return 响应
     */
    private ClientResponse post(String uri, String jsonBody) {
        long timestamp = System.currentTimeMillis();
        String nonce = newNonce();
        String signature = sign(secretKey(), uri + "&" + timestamp + "&" + nonce);
        return HttpClientFactory.of(normalizeBaseUrl() + uri)
                .query("AccessKey", accessKey())
                .query("Timestamp", String.valueOf(timestamp))
                .query("SignatureNonce", nonce)
                .query("Signature", signature)
                .json()
                .connectTimeout(CONNECT_TIMEOUT_MILLIS)
                .readTimeout(READ_TIMEOUT_MILLIS)
                .body(jsonBody)
                .post();
    }

    /**
     * 解析 LiblibAI 统一响应信封：非 200 或 code!=0 一律抛业务异常。
     *
     * @param response HTTP 响应
     * @param action   动作描述（用于错误信息）
     * @return 响应根 JSON 对象
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEnvelope(ClientResponse response, String action) {
        if (response.getStatusCode() != 200) {
            throw new RuntimeException("LiblibAI " + action + "失败: HTTP "
                    + response.getStatusCode() + " - " + response.getBodyString());
        }
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(response.getBodyString(), Map.class);
        if (root == null) {
            throw new RuntimeException("LiblibAI " + action + "返回非 JSON: " + response.getBodyString());
        }
        Object code = root.get("code");
        if (!(code instanceof Number number) || number.intValue() != 0) {
            throw new RuntimeException("LiblibAI " + action + "失败: code=" + code
                    + " msg=" + root.get("msg"));
        }
        return root;
    }

    /**
     * generateStatus 映射到统一任务状态。
     *
     * <p>1=等待；2=执行中、3=已生图、4=审核中均视为进行中；5=成功；6=失败；7=超时。</p>
     *
     * @param generateStatus 上游状态码
     * @return 统一状态
     */
    private static ImageResponse.Status mapStatus(Object generateStatus) {
        int status = generateStatus instanceof Number number ? number.intValue() : -1;
        if (status == GEN_STATUS_SUCCEED) {
            return ImageResponse.Status.SUCCESS;
        }
        if (status == GEN_STATUS_FAILED || status == GEN_STATUS_TIMEOUT) {
            return ImageResponse.Status.FAILED;
        }
        if (status == GEN_STATUS_PENDING) {
            return ImageResponse.Status.PENDING;
        }
        return ImageResponse.Status.RUNNING;
    }

    /**
     * 下载图片并解码
     *
     * @param imageUrl 图片地址（7 天有效）
     * @return 解码后的图片
     */
    private BufferedImage downloadImage(String imageUrl) {
        if (StringUtils.isBlank(imageUrl)) {
            throw new RuntimeException("LiblibAI 生图成功但未返回图片地址");
        }
        ClientResponse imageResponse = HttpClientFactory.of(imageUrl)
                .connectTimeout(IMAGE_CONNECT_TIMEOUT_MILLIS)
                .readTimeout(IMAGE_READ_TIMEOUT_MILLIS)
                .get();
        if (!imageResponse.isSuccess()) {
            throw new RuntimeException("LiblibAI 图片下载失败: HTTP " + imageResponse.getStatusCode());
        }
        byte[] bytes = imageResponse.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new RuntimeException("LiblibAI 图片下载失败: 响应体为空");
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            throw new RuntimeException("LiblibAI 图片解码失败", e);
        }
    }

    /**
     * 计算 HmacSHA1 签名（base64url，不补 =）
     *
     * @param secretKey API 访问密钥
     * @param content   待签串：uri + "&" + timestamp + "&" + nonce
     * @return 签名
     */
    private static String sign(String secretKey, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("LiblibAI 签名计算失败", e);
        }
    }

    /**
     * 生成随机 SignatureNonce（16 位十六进制）
     */
    private static String newNonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(16);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /**
     * 取 AccessKey（appKey）
     */
    private String accessKey() {
        String key = setting.getAppKey();
        if (StringUtils.isBlank(key)) {
            throw new IllegalArgumentException("LiblibAI AccessKey 不能为空（凭据 api_key）");
        }
        return key.trim();
    }

    /**
     * 取 SecretKey
     */
    private String secretKey() {
        String key = setting.getSecretKey();
        if (StringUtils.isBlank(key)) {
            throw new IllegalArgumentException("LiblibAI SecretKey 不能为空（凭据 secret_key）");
        }
        return key.trim();
    }

    /**
     * 规范化 API 基础地址（去结尾斜杠）
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (StringUtils.isBlank(url)) {
            url = DEFAULT_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 把尺寸钳制到星流允许的 512–2048 区间
     *
     * @param size 原始尺寸
     * @return 钳制后的尺寸
     */
    private static int clampSize(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
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
