package com.chua.ueba.support.engine;

import com.chua.ueba.support.config.UebaConfig;
import com.chua.ueba.support.dto.BehaviorProfile;
import com.chua.ueba.support.dto.IpAnomalyResult;
import com.chua.ueba.support.dto.TrafficEvent;
import com.chua.ueba.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 规则评分器。
 * <p>
 * 当 ONNX 模型不可用时，UEBA 引擎回退到基于安全规则的经验评分，
 * 保证分析流程不中断。所有原始指标复用 {@link FeatureExtractor} 计算，
 * 避免与特征提取逻辑重复。规则阈值以常量形式集中定义，便于调优。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RuleBasedScorer {

    /** 规则评分归一化基数（阈值 恒为 1.0，分数即归一化后的风险度） */
    private static final double RULE_THRESHOLD = 1.0d;

    /** IP 高异常阈值 */
    private static final double IP_HIGH_THRESHOLD = 0.7d;

    /** IP 中异常阈值 */
    private static final double IP_MEDIUM_THRESHOLD = 0.45d;

    /** IP 低异常阈值 */
    private static final double IP_LOW_THRESHOLD = 0.25d;

    /** 高错误率判定阈值 */
    private static final double ERROR_RATE_HIGH = 0.3d;

    /** 高请求频率判定阈值（次/秒） */
    private static final double REQUEST_RATE_HIGH = 50.0d;

    /** 高路径熵判定阈值（比特） */
    private static final double PATH_ENTROPY_HIGH = 4.0d;

    /** 高 UA 多样性判定阈值 */
    private static final double UA_DIVERSITY_HIGH = 0.8d;

    /** 攻击类别判定阈值 */
    private static final double BEHAVIOR_ATTACK_THRESHOLD = 0.6d;

    /** 可疑类别判定阈值 */
    private static final double BEHAVIOR_SUSPICIOUS_THRESHOLD = 0.3d;

    /** 敏感路径命中权重 */
    private static final double SENSITIVE_PATH_WEIGHT = 0.3d;

    /** 高错误率权重 */
    private static final double ERROR_RATE_WEIGHT = 0.2d;

    /** 高请求频率权重 */
    private static final double REQUEST_RATE_WEIGHT = 0.2d;

    /** 高路径熵权重 */
    private static final double PATH_ENTROPY_WEIGHT = 0.2d;

    /** 高 UA 多样性权重 */
    private static final double UA_DIVERSITY_WEIGHT = 0.2d;

    /** 夜间访问权重 */
    private static final double NIGHT_ACCESS_WEIGHT = 0.1d;

    /** 夜间时段起始小时 */
    private static final int NIGHT_HOUR_START = 22;

    /** 夜间时段结束小时 */
    private static final int NIGHT_HOUR_END = 6;

    /** 事件数阈值：少于该数量不做行为评分 */
    private static final int MIN_EVENTS_FOR_BEHAVIOR = 1;

    /** 敏感路径关键字 */
    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "/admin", "/api/internal", "/config", "/backup", "/.env");

    /** 默认类别标签 */
    private static final List<String> DEFAULT_CLASS_LABELS = List.of("normal", "suspicious", "attack");

    /** 特征提取器 */
    private final FeatureExtractor featureExtractor;

    /** 时区 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * 构造规则评分器。
     *
     * @param featureExtractor 特征提取器，不能为 空
     * @throws IllegalArgumentException 当 特征extractor 为 空 时
     */
    public RuleBasedScorer(FeatureExtractor featureExtractor) {
        Objects.requireNonNull(featureExtractor, "featureExtractor must not be null");
        this.featureExtractor = featureExtractor;
    }

    /**
     * 基于规则的 IP 异常评分。
     * <p>
     * 约定：{@code reconstructionError} 字段承载规则分数（范围 [0,1]），
     * {@code threshold} 恒为 {@code RULE_THRESHOLD}，使引擎的风险加权逻辑对
     * 模型模式与规则模式完全一致。
     * </p>
     *
     * @param entityId 实体标识，不能为 空 或空白
     * @param window   窗口事件列表，允许为空
     * @return IP 异常评分结果
     * @throws IllegalArgumentException 当 实体标识 为 空 或空白时
     */
    public IpAnomalyResult scoreIp(String entityId, List<TrafficEvent> window) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId 不能为 null 或空白");
        }
        List<TrafficEvent> events = window == null ? List.of() : window;
        double score = 0.0d;
        List<String> reasons = new ArrayList<>(4);

        double errorRate = featureExtractor.rawIpFeature(featureOf("error_rate"), events);
        if (errorRate >= ERROR_RATE_HIGH) {
            score += ERROR_RATE_WEIGHT;
            reasons.add(String.format("错误率过高: %.2f", errorRate));
        }

        double requestRate = featureExtractor.rawIpFeature(featureOf("request_rate"), events);
        if (requestRate >= REQUEST_RATE_HIGH) {
            score += REQUEST_RATE_WEIGHT;
            reasons.add(String.format("请求频率过高: %.1f/s", requestRate));
        }

        double pathEntropy = featureExtractor.rawIpFeature(featureOf("path_entropy"), events);
        if (pathEntropy >= PATH_ENTROPY_HIGH) {
            score += PATH_ENTROPY_WEIGHT;
            reasons.add(String.format("路径熵过高: %.2f", pathEntropy));
        }

        double uaDiversity = featureExtractor.rawIpFeature(featureOf("ua_diversity"), events);
        if (uaDiversity >= UA_DIVERSITY_HIGH) {
            score += UA_DIVERSITY_WEIGHT;
            reasons.add(String.format("UA 多样性过高: %.2f", uaDiversity));
        }

        score = Math.min(1.0d, score);
        boolean anomalous = score >= IP_LOW_THRESHOLD;
        return IpAnomalyResult.builder()
                .ip(entityId)
                .reconstructionError(score)
                .anomalous(anomalous)
                .level(mapLevel(score))
                .reason(String.join("; ", reasons))
                .threshold(RULE_THRESHOLD)
                .build();
    }

    /**
     * 基于规则的行为画像评分。
     * <p>
     * 综合敏感路径访问、高错误率、高请求频率与夜间访问四个信号，
     * 输出类别标签（normal / suspicious / attack）与规则风险分数。
     * </p>
     *
     * @param entityId 实体标识，不能为 空 或空白
     * @param window   窗口事件列表，允许为空
     * @return 行为画像结果
     * @throws IllegalArgumentException 当 实体标识 为 空 或空白时
     */
    public BehaviorProfile scoreBehavior(String entityId, List<TrafficEvent> window) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId 不能为 null 或空白");
        }
        List<TrafficEvent> events = window == null ? List.of() : window;
        double score = 0.0d;

        if (events.size() < MIN_EVENTS_FOR_BEHAVIOR) {
            return BehaviorProfile.builder()
                    .entityId(entityId)
                    .predictedClass(0)
                    .label(configClassLabels().get(0))
                    .confidence(0.0d)
                    .recentPaths(List.of())
                    .riskScore(0.0d)
                    .analysisMode("RULE")
                    .build();
        }

        boolean sensitiveHit = events.stream().anyMatch(RuleBasedScorer::isSensitivePath);
        if (sensitiveHit) {
            score += SENSITIVE_PATH_WEIGHT;
        }

        double errorRate = featureExtractor.rawIpFeature(featureOf("error_rate"), events);
        if (errorRate >= ERROR_RATE_HIGH) {
            score += ERROR_RATE_WEIGHT;
        }

        double requestRate = featureExtractor.rawIpFeature(featureOf("request_rate"), events);
        if (requestRate >= REQUEST_RATE_HIGH) {
            score += REQUEST_RATE_WEIGHT;
        }

        boolean nightAccess = events.stream().anyMatch(RuleBasedScorer::isNightAccess);
        if (nightAccess) {
            score += NIGHT_ACCESS_WEIGHT;
        }

        score = Math.min(1.0d, score);
        String label = labelOf(score);
        int classIndex = classIndexOf(label);
        List<String> recentPaths = recentPaths(events);
        return BehaviorProfile.builder()
                .entityId(entityId)
                .predictedClass(classIndex)
                .label(label)
                .confidence(score)
                .recentPaths(recentPaths)
                .riskScore(score)
                .analysisMode("RULE")
                .build();
    }

    /**
     * 将分数映射为异常等级。
     *
     * @param score 规则分数，范围 [0, 1]
     * @return 异常等级
     */
    private static IpAnomalyResult.AnomalyLevel mapLevel(double score) {
        if (score >= IP_HIGH_THRESHOLD) {
            return IpAnomalyResult.AnomalyLevel.HIGH;
        }
        if (score >= IP_MEDIUM_THRESHOLD) {
            return IpAnomalyResult.AnomalyLevel.MEDIUM;
        }
        if (score >= IP_LOW_THRESHOLD) {
            return IpAnomalyResult.AnomalyLevel.LOW;
        }
        return IpAnomalyResult.AnomalyLevel.NORMAL;
    }

    /**
     * 将行为分数映射为类别标签。
     *
     * @param score 行为分数，范围 [0, 1]
     * @return 类别标签
     */
    private static String labelOf(double score) {
        if (score >= BEHAVIOR_ATTACK_THRESHOLD) {
            return "attack";
        }
        if (score >= BEHAVIOR_SUSPICIOUS_THRESHOLD) {
            return "suspicious";
        }
        return "normal";
    }

    /**
     * 获取类别标签对应的下标。
     *
     * @param label 类别标签
     * @return 类别下标，未命中时返回默认值
     */
    private int classIndexOf(String label) {
        List<String> labels = configClassLabels();
        int idx = labels.indexOf(label);
        return idx >= 0 ? idx : labels.indexOf("suspicious");
    }

    /**
     * 获取配置或默认类别标签。
     *
     * @return 类别标签列表
     */
    private List<String> configClassLabels() {
        UebaConfig.Lstm lstmConfig = featureExtractor.config().getLstm();
        if (lstmConfig != null && lstmConfig.getClassLabels() != null && !lstmConfig.getClassLabels().isEmpty()) {
            return lstmConfig.getClassLabels();
        }
        return DEFAULT_CLASS_LABELS;
    }

    /**
     * 获取窗口内最近访问路径，供引擎与规则评分共用。
     *
     * @param events 事件列表
     * @return 路径列表（最多 5 条），绝不为 空
     */
    static List<String> recentPaths(List<TrafficEvent> events) {
        List<String> paths = new ArrayList<>(5);
        int size = events.size();
        for (int i = size - 1; i >= 0 && paths.size() < 5; i--) {
            String path = events.get(i).getPath();
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * 判断路径是否命中敏感关键字。
     *
     * @param event 流量事件，不能为 空
     * @return true 表示命中敏感路径
     */
    private static boolean isSensitivePath(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String path = event.getPath();
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断事件是否发生在夜间时段（22:00 至次日 06:00）。
     *
     * @param event 流量事件，不能为 空
     * @return true 表示夜间访问
     */
    private static boolean isNightAccess(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimestamp()), ZONE);
        int hour = dt.getHour();
        return hour >= NIGHT_HOUR_START || hour < NIGHT_HOUR_END;
    }

    /**
     * 按名称构造一个最小特征定义（复用特征提取器的原始指标计算）。
     *
     * @param name 特征名
     * @return 特征定义，窗口与归一化均不生效
     */
    private com.chua.ueba.support.config.FeatureDefinition featureOf(String name) {
        return com.chua.ueba.support.config.FeatureDefinition.builder()
                .name(name)
                .type(com.chua.ueba.support.config.FeatureDefinition.FeatureType.NUMERIC)
                .normalize(com.chua.ueba.support.config.FeatureDefinition.NormalizeType.NONE)
                .build();
    }
}
