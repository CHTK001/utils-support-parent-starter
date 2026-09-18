package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 打开AI 嵌入向量客户端。
*
* <p>基于 OpenAI Java SDK 的 {@link EmbeddingClient} 实现，支持 OpenAI 兼容接口的
* 所有服务商（如 打开AI、silicon流、sense时间 等）。
*
* <p>通过 SPI 机制注册以下别名：
* <ul>
*   <li>openai — OpenAI 官方</li>
*   <li>siliconflow — 硅基流动</li>
*   <li>sensetime — 商汤科技</li>
*   <li>github — GitHub Models</li>
*   <li>gitee — Gitee AI</li>
* </ul>
*
* <p>调用示例：
* <pre>{@code
*   float[] vector = EmbeddingClient.create("openai", "sk-xxx")
*       .model("text-embedding-3-small")
*       .dimensions(256)
*       .embedding("要向量化的文本");
* }</pre>imensions(256)
*       .embedding("要向量化的文本");
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"openai", "siliconflow", "sensetime", "github", "gitee"})
public class OpenAiEmbeddingClient implements EmbeddingClient {

    /**
    * 打开AI 默认 API 地址
    */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
    * 默认模型名称
    */
    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    /**
    * 默认向量维度
    */
    private static final int DEFAULT_DIMENSIONS = 1536;

    /**
    * 客户端配置
    */
    private final EmbeddingClientSetting setting;

    /**
    * 当前使用的模型名称
    */
    private String model;

    /**
    * 输出向量维度
    */
    private Integer dimensions;

    /**
    * 构造 打开AI 嵌入向量客户端。
    *
    * @param setting 客户端配置
    */
    public OpenAiEmbeddingClient(EmbeddingClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.dimensions = setting.getDimensions();
    }

    @Override
    /** 模型 */
    public EmbeddingClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 维度 */
    public EmbeddingClient dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    @Override
    /** 嵌入 */
    public float[] embedding(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimension()];
        }
        // 委托给批量方法，取第一个结果
        float[][] vectors = embeddingBatch(new String[]{text});
        return vectors.length > 0 ? vectors[0] : new float[dimension()];
    }

    @Override
    /** 嵌入batch */
    public float[][] embeddingBatch(String[] texts) {
        if (texts == null || texts.length == 0) {
            return new float[0][];
        }
        EmbeddingCreateParams params = buildEmbeddingParams(texts);
        OpenAIClient client = null;
        try {
            client = createClient();
            CreateEmbeddingResponse response = client.embeddings().create(params);
            return parseEmbeddings(response).toArray(new float[0][]);
        } catch (Exception e) {
            log.error("OpenAI 嵌入向量请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 嵌入向量请求失败: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) { }
            }
        }
    }

    @Override
    /** 嵌入with响应 */
    public EmbeddingResponse embeddingWithResponse(String text) {
        if (text == null || text.isBlank()) {
            return buildEmptyResponse();
        }
        // 委托给批量方法，取第一个结果
        return embeddingBatchWithResponse(new String[]{text});
    }

    @Override
    /** 嵌入batchwith响应 */
    public EmbeddingResponse embeddingBatchWithResponse(String[] texts) {
        if (texts == null || texts.length == 0) {
            return EmbeddingResponse.builder()
                    .embeddings(List.of())
                    .build();
        }
        EmbeddingCreateParams params = buildEmbeddingParams(texts);

        long startTime = System.currentTimeMillis();
        OpenAIClient client = null;
        try {
            client = createClient();
            CreateEmbeddingResponse response = client.embeddings().create(params);
            long duration = System.currentTimeMillis() - startTime;

            List<float[]> vectors = parseEmbeddings(response);
            List<EmbeddingResponse.Embedding> embeddings = buildEmbeddingList(vectors);
            AiUsage usage = buildUsage(response, duration, startTime);

            return EmbeddingResponse.builder()
                    .embeddings(embeddings)
                    .usage(usage)
                    .build();
        } catch (Exception e) {
            log.error("OpenAI 嵌入向量请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 嵌入向量请求失败: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) { }
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
 // 打开AI SDK 客户端在每次请求中通过 尝试-with-resources 自动创建和关闭，无需额外清理
        log.debug("OpenAiEmbeddingClient 已关闭");
    }

    // ==================== 内部方法 ====================

    /**
    * 创建 打开AI HTTP 客户端。
    *
    * @return OpenAI 客户端实例
    */
    private OpenAIClient createClient() {
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(setting.getAppKey())
                .baseUrl(normalizeBaseUrl())
                .timeout(Duration.ofSeconds(60));

        String proxyStr = setting.getProxy();
        if (proxyStr != null && !proxyStr.isBlank()) {
            clientBuilder.proxy(resolveProxy(proxyStr));
        }
        return clientBuilder.build();
    }

    /**
    * 构建嵌入向量请求参数。
    *
    * @param texts 待向量化的文本数组
    * @return 请求参数
    */
    private EmbeddingCreateParams buildEmbeddingParams(String[] texts) {
        String actualModel = model != null ? model : DEFAULT_MODEL;
        EmbeddingCreateParams.Builder paramsBuilder = EmbeddingCreateParams.builder()
                .model(actualModel);

        if (texts.length == 1) {
            paramsBuilder.input(texts[0] != null ? texts[0] : "");
        } else {
            List<String> sanitized = new ArrayList<>(texts.length);
            for (String t : texts) {
                sanitized.add(t != null ? t : "");
            }
            paramsBuilder.inputOfArrayOfStrings(sanitized);
        }

 // 支持 维度 参数（仅 文本-嵌入-3 及以上模型支持）
        if (dimensions != null && dimensions > 0) {
            paramsBuilder.dimensions((long) dimensions);
        }

        return paramsBuilder.build();
    }

    /**
    * 将向量列表转换为 嵌入响应.嵌入 列表。
    *
    * @param vectors 浮点数向量列表
    * @return Embedding 列表
    */
    private static List<EmbeddingResponse.Embedding> buildEmbeddingList(List<float[]> vectors) {
        AtomicInteger idx = new AtomicInteger(0);
        List<EmbeddingResponse.Embedding> embeddings = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            embeddings.add(EmbeddingResponse.Embedding.builder()
                    .vector(v)
                    .index(idx.getAndIncrement())
                    .dimensions(v != null ? v.length : 0)
                    .build());
        }
        return embeddings;
    }

    /**
    * 构建用量信息。
    *
    * @param response  打开AI 原始响应
    * @param duration  请求耗时（毫秒）
    * @param startTime 请求开始时间戳
    * @return 用量信息
    */
    private AiUsage buildUsage(CreateEmbeddingResponse response, long duration, long startTime) {
        String actualModel = model != null ? model : DEFAULT_MODEL;
        AiUsage.AiUsageBuilder usageBuilder = AiUsage.builder()
                .model(actualModel)
                .provider("openai")
                .startTime(startTime)
                .durationMillis(duration);

        CreateEmbeddingResponse.Usage u = response.usage();
        if (u != null) {
            usageBuilder
                    .inputTokens((int) u.promptTokens())
                    .totalTokens((int) u.totalTokens());
        }
        return usageBuilder.build();
    }

    /**
    * 构建空文本的默认响应。
    *
    * @return 空向量响应
    */
    private EmbeddingResponse buildEmptyResponse() {
        return EmbeddingResponse.builder()
                .embeddings(List.of(EmbeddingResponse.Embedding.builder()
                        .vector(new float[dimension()])
                        .index(0)
                        .dimensions(dimension())
                        .build()))
                .build();
    }

    /**
    * 解析 打开AI 嵌入向量响应。
    *
    * @param response 打开AI 原始响应
    * @return 浮点数向量列表
    */
    private static List<float[]> parseEmbeddings(CreateEmbeddingResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            return List.of();
        }

        List<float[]> results = new ArrayList<>(response.data().size());
        for (Embedding item : response.data()) {
            if (item == null || item.embedding() == null || item.embedding().isEmpty()) {
                continue;
            }
            List<Float> embedding = item.embedding();
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                Float value = embedding.get(i);
                vector[i] = value == null ? 0f : value;
            }
            results.add(vector);
        }
        return results;
    }

    /**
    * 规范化 API 基础地址。
    *
    * <p>移除末尾多余的斜杠，若未配置则使用默认地址。
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
    * 获取当前维度（有配置值则用配置值，否则使用默认值）。
    * @return 维度的结果
    */
    private int dimension() {
        return dimensions != null ? dimensions : DEFAULT_DIMENSIONS;
    }

    /**
    * 解析代理地址字符串。
    *
    * @param proxyStr 代理地址字符串，支持 http://、socks5:// 格式
    * @return Proxy 对象，解析失败时返回 空
    */
    private static Proxy resolveProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return null;
        }
        Proxy.Type proxyType;
        String hostPort;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            proxyType = Proxy.Type.SOCKS;
            hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
        } else if (proxyStr.startsWith("http://")) {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr.substring(7);
        } else {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        return new Proxy(proxyType, new InetSocketAddress(host, port));
    }
}
