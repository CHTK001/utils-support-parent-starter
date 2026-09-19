package com.chua.ueba.support.feature;

import com.chua.ueba.support.config.FeatureDefinition;
import com.chua.ueba.support.config.UebaConfig;
import com.chua.ueba.support.dto.TrafficEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 特征提取器。
 * <p>
 * 负责将原始流量事件窗口转换为模型输入：auto编码器 所需的 IP 聚合特征向量，
 * 以及 LSTM/GRU 所需的行为序列（类别 标识 序列 + 数值序列）。所有原始指标的计算
 * 集中在本类（{@link #rawIpFeature}），规则评分 {@code RuleBasedScorer} 复用同一
 * 入口，避免重复实现。特征顺序严格遵循配置中 {@code features} 的定义。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FeatureExtractor {

    /** 一天的小时数，用于 时间_的_day 归一化 */
    private static final double HOURS_PER_DAY = 24.0d;

    /** HTTP 4xx/5xx 状态码下限 */
    private static final int CLIENT_ERROR_STATUS = 400;

    /** 默认时区 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
    * 特征配置
    */
    private final UebaConfig config;

    /**
     * 特征定义列表（配置顺序即维度顺序）
     */
    private final List<FeatureDefinition> features;

    /**
     * 构造特征提取器。
     *
     * @param config UEBA 配置，不能为 空，且 特征 不能为空
     * @throws IllegalArgumentException 当 配置 为 空 或 特征 为空时
     */
    public FeatureExtractor(UebaConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.getFeatures() == null || config.getFeatures().isEmpty()) {
            throw new IllegalArgumentException("config.features 不能为空");
        }
        this.config = config;
        this.features = config.getFeatures();
    }

    /**
     * 返回当前配置，供规则评分等协作组件读取模型参数。
     *
     * @return UEBA 配置对象，绝不为 空
     */
    public UebaConfig config() {
        return config;
    }

    /**
     * 提取 IP 聚合特征向量（auto编码器 输入）。
     *
     * @param window 窗口内流量事件列表，允许为空
     * @return 特征向量，长度等于配置 特征 数量，顺序与配置一致
     */
    public float[] extractIpFeatures(List<TrafficEvent> window) {
        float[] vector = new float[features.size()];
        for (int i = 0; i < features.size(); i++) {
            FeatureDefinition def = features.get(i);
            double raw = rawIpFeature(def, window);
            vector[i] = (float) normalize(def, raw);
        }
        return vector;
    }

    /**
     * 提取行为序列的类别 标识 序列（LSTM/GRU 输入之一）。
     *
     * @param window 窗口内流量事件列表，允许为空
     * @param seqLen 序列长度，必须大于 0
     * @return 长度等于 seqlen 的 标识 数组，不足 seqlen 时左端补 0
     * @throws IllegalArgumentException 当 seqlen 小于等于 0 时
     */
    public int[] extractSequenceIds(List<TrafficEvent> window, int seqLen) {
        if (seqLen <= 0) {
            throw new IllegalArgumentException("seqLen 必须大于 0, 实际: " + seqLen);
        }
        int[] ids = new int[seqLen];
        if (window == null || window.isEmpty()) {
            return ids;
        }
        int count = Math.min(window.size(), seqLen);
        int start = seqLen - count;
        for (int i = 0; i < count; i++) {
            TrafficEvent event = window.get(window.size() - count + i);
            ids[start + i] = encodePath(event);
        }
        return ids;
    }

    /**
     * 提取行为序列的数值特征序列（LSTM/GRU 输入之二）。
     * <p>
     * 每个时间步的数值特征顺序固定为：
     * 是否错误(0/1) → 小时相位(0~1) → 响应耗时(归一化) → 方法编码(归一化)，
     * 不足 seqlen 时左端补 0。与训练端特征顺序必须一致。
     * </p>
     *
     * @param window     窗口内流量事件列表，允许为空
     * @param seqLen     序列长度，必须大于 0
     * @param numNumeric 每步数值特征数量，必须大于 0
     * @return 长度等于 seqlen 的数值特征数组
     * @throws IllegalArgumentException 当 seqlen 或 numnumeric 小于等于 0 时
     */
    public float[][] extractSequenceNumeric(List<TrafficEvent> window, int seqLen, int numNumeric) {
        if (seqLen <= 0) {
            throw new IllegalArgumentException("seqLen 必须大于 0, 实际: " + seqLen);
        }
        if (numNumeric <= 0) {
            throw new IllegalArgumentException("numNumeric 必须大于 0, 实际: " + numNumeric);
        }
        float[][] numeric = new float[seqLen][numNumeric];
        if (window == null || window.isEmpty()) {
            return numeric;
        }
        int count = Math.min(window.size(), seqLen);
        int start = seqLen - count;
        for (int i = 0; i < count; i++) {
            TrafficEvent event = window.get(window.size() - count + i);
            float[] step = numeric[start + i];
            step[0] = event.getStatusCode() >= CLIENT_ERROR_STATUS ? 1.0f : 0.0f;
            step[1] = (float) hourPhase(event.getTimestamp());
            step[2] = (float) normalizeByName("avg_response_time", event.getResponseTimeMs());
            step[3] = methodCode(event.getMethod()) / (float) METHOD_CODE_MAX;
        }
        return numeric;
    }

    /** 获取 方法编码 */
    private static final int METHOD_CODE_GET = 0;
    /** POST 方法编码 */
    private static final int METHOD_CODE_POST = 1;
    /** 放入 方法编码 */
    private static final int METHOD_CODE_PUT = 2;
    /** 删除 方法编码 */
    private static final int METHOD_CODE_DELETE = 3;
    /** 其它方法编码 */
    private static final int METHOD_CODE_OTHER = 4;
    /** 方法编码最大值（归一化除数） */
    private static final float METHOD_CODE_MAX = 4.0f;

    /**
     * 计算单个原始 IP 指标（未归一化）。
     *
     * @param def    特征定义，不能为 空
     * @param window 窗口内流量事件列表，允许为空
     * @return 原始指标值
     */
    public double rawIpFeature(FeatureDefinition def, List<TrafficEvent> window) {
        Objects.requireNonNull(def, "def must not be null");
        return switch (def.getName()) {
            case "request_rate" -> requestRate(window, def.getWindow());
            case "path_entropy" -> pathEntropy(window);
            case "unique_paths_count" -> uniquePathsCount(window);
            case "error_rate" -> errorRate(window);
            case "method_diversity" -> methodDiversity(window);
            case "ua_diversity" -> uaDiversity(window);
            case "avg_response_time" -> avgResponseTime(window);
            case "max_response_time" -> maxResponseTime(window);
            case "avg_body_size" -> avgBodySize(window);
            case "time_of_day" -> window == null || window.isEmpty() ? 0.0d : hourPhase(window.get(window.size() - 1).getTimestamp());
            default -> {
                log.debug("[UEBA-Feature] 未知特征名: {}, 按 0 处理", def.getName());
                yield 0.0d;
            }
        };
    }

    /**
     * 请求频率：窗口事件数 / 窗口秒数。
     *
     * @param window 事件列表
     * @param windowSeconds 聚合窗口（秒）
     * @return 每秒请求数，窗口为空时返回 0
     */
    private double requestRate(List<TrafficEvent> window, long windowSeconds) {
        if (window == null || window.isEmpty() || windowSeconds <= 0L) {
            return 0.0d;
        }
        return (double) window.size() / windowSeconds;
    }

    /**
     * 路径熵：访问路径分布的 Shannon 熵。
     *
     * @param window 事件列表
     * @return 熵值（比特），范围 [0, 日志2(不同路径数)]，窗口为空时返回 0
     */
    private double pathEntropy(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        Map<String, Integer> counts = new HashMap<>(Math.max(16, window.size()));
        for (TrafficEvent event : window) {
            String path = event.getPath() == null ? "" : event.getPath();
            counts.merge(path, 1, Integer::sum);
        }
        double total = window.size();
        double entropy = 0.0d;
        for (int count : counts.values()) {
            double p = count / total;
            entropy -= p * (Math.log(p) / Math.log(2.0d));
        }
        return entropy;
    }

    /**
     * 不同访问路径的数量。
     *
     * @param window 事件列表
     * @return 去重路径数，窗口为空时返回 0
     */
    private double uniquePathsCount(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        Map<String, Integer> counts = new HashMap<>(Math.max(16, window.size()));
        for (TrafficEvent event : window) {
            String path = event.getPath() == null ? "" : event.getPath();
            counts.put(path, 1);
        }
        return counts.size();
    }

    /**
     * 错误率：4xx/5xx 请求占比。
     *
     * @param window 事件列表
     * @return 错误率，范围 [0, 1]，窗口为空时返回 0
     */
    private double errorRate(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        long errors = 0L;
        for (TrafficEvent event : window) {
            if (event.getStatusCode() >= CLIENT_ERROR_STATUS) {
                errors++;
            }
        }
        return (double) errors / window.size();
    }

    /**
     * 方法多样性：不同 HTTP 方法数占比。
     *
     * @param window 事件列表
     * @return 多样性值，范围 [0, 1]，窗口为空时返回 0
     */
    private double methodDiversity(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        Map<String, Integer> counts = new HashMap<>(8);
        for (TrafficEvent event : window) {
            counts.put(event.getMethod() == null ? "UNKNOWN" : event.getMethod(), 1);
        }
        return (double) counts.size() / window.size();
    }

    /**
     * UA 多样性：不同 用户-Agent 数占比。
     *
     * @param window 事件列表
     * @return 多样性值，范围 [0, 1]，窗口为空时返回 0
     */
    private double uaDiversity(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        Map<String, Integer> counts = new HashMap<>(Math.max(8, window.size()));
        for (TrafficEvent event : window) {
            String ua = event.getUserAgent() == null || event.getUserAgent().isBlank() ? "UNKNOWN" : event.getUserAgent();
            counts.put(ua, 1);
        }
        return (double) counts.size() / window.size();
    }

    /**
     * 平均响应耗时（毫秒）。
     *
     * @param window 事件列表
     * @return 平均响应耗时，窗口为空时返回 0
     */
    private double avgResponseTime(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        long sum = 0L;
        for (TrafficEvent event : window) {
            sum += event.getResponseTimeMs();
        }
        return (double) sum / window.size();
    }

    /**
     * 最大响应耗时（毫秒）。
     *
     * @param window 事件列表
     * @return 最大响应耗时，窗口为空时返回 0
     */
    private double maxResponseTime(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        int max = 0;
        for (TrafficEvent event : window) {
            max = Math.max(max, event.getResponseTimeMs());
        }
        return max;
    }

    /**
     * 平均响应体大小（字节）。
     *
     * @param window 事件列表
     * @return 平均响应体大小，窗口为空时返回 0
     */
    private double avgBodySize(List<TrafficEvent> window) {
        if (window == null || window.isEmpty()) {
            return 0.0d;
        }
        long sum = 0L;
        for (TrafficEvent event : window) {
            sum += event.getResponseSizeBytes();
        }
        return (double) sum / window.size();
    }

    /**
     * 时间戳对应的小时相位。
     *
     * @param timestamp 毫秒时间戳
     * @return 小时相位，范围 [0, 1)
     */
    private static double hourPhase(long timestamp) {
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZONE);
        return dt.getHour() / HOURS_PER_DAY;
    }

    /**
     * HTTP 方法编码。
     *
     * @param method 方法字符串，允许为 空
     * @return 方法编码
     */
    private static int methodCode(String method) {
        if (method == null) {
            return METHOD_CODE_OTHER;
        }
        return switch (method.toUpperCase()) {
            case "GET" -> METHOD_CODE_GET;
            case "POST" -> METHOD_CODE_POST;
            case "PUT" -> METHOD_CODE_PUT;
            case "DELETE" -> METHOD_CODE_DELETE;
            default -> METHOD_CODE_OTHER;
        };
    }

    /**
     * 路径到类别 标识 的编码，优先查配置词表，未命中时退化为稳定哈希。
     * <p>哈希回退的上界取自训练回写的词表（preprocessing.vocab 最大 ID + 1），
     * 确保索引不超出 ONNX 嵌入 表大小；词表为空时才用配置的类别特征 vocab大小。</p>
     *
     * @param event 流量事件，不能为 空
     * @return 类别 标识，范围 [1, vocabbound)，0 保留给填充位
     */
    private int encodePath(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String path = event.getPath() == null ? "" : event.getPath();
        Map<String, Integer> vocab = config.getPreprocessing() == null
                ? Map.of() : config.getPreprocessing().getVocab();
        Integer id = vocab.get(path);
        if (id != null && id > 0) {
            return id;
        }
        int bound = vocabBound(vocab);
        return (path.hashCode() & 0x7fffffff) % (bound - 1) + 1;
    }

    /**
     * 计算类别 标识 的取值上界。
     *
     * @param vocab 配置词表，允许为 空 或空
     * @return 上界，至少为 2
     */
    private int vocabBound(Map<String, Integer> vocab) {
        if (vocab != null && !vocab.isEmpty()) {
            int maxId = 1;
            for (int value : vocab.values()) {
                maxId = Math.max(maxId, value);
            }
            return maxId + 1;
        }
        for (FeatureDefinition def : features) {
            if (def.getType() == FeatureDefinition.FeatureType.CATEGORICAL) {
                return Math.max(2, def.getVocabSize());
            }
        }
        return FeatureDefinition.DEFAULT_VOCAB_SIZE;
    }

    /**
     * 按特征配置归一化原始值。
     *
     * @param def 特征定义，不能为 空
     * @param raw 原始值
     * @return 归一化后的值
     */
    private double normalize(FeatureDefinition def, double raw) {
        Objects.requireNonNull(def, "def must not be null");
        if (def.getType() == FeatureDefinition.FeatureType.CATEGORICAL) {
            return raw <= 0.0d ? 0.0d : Math.min(1.0d, raw / Math.max(1, def.getVocabSize()));
        }
        return normalizeByName(def.getName(), raw);
    }

    /**
     * 按特征名从配置 scaler 归一化原始值。
     *
     * @param featureName 特征名，不能为 空
     * @param raw         原始值
     * @return 归一化后的值
     */
    private double normalizeByName(String featureName, double raw) {
        Objects.requireNonNull(featureName, "featureName must not be null");
        UebaConfig.Preprocessing preprocessing = config.getPreprocessing();
        if (preprocessing == null || preprocessing.getScalers() == null) {
            return raw;
        }
        UebaConfig.Scaler scaler = preprocessing.getScalers().get(featureName);
        if (scaler == null) {
            return raw;
        }
        if (scaler.getMin() != null && scaler.getMax() != null) {
            double range = scaler.getMax() - scaler.getMin();
            if (range > 0.0d) {
                return (raw - scaler.getMin()) / range;
            }
            return 0.0d;
        }
        if (scaler.getMean() != null && scaler.getStd() != null && scaler.getStd() > 0.0d) {
            return (raw - scaler.getMean()) / scaler.getStd();
        }
        return raw;
    }
}
