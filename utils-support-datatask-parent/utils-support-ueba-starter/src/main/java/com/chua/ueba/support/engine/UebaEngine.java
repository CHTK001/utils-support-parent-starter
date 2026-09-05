package com.chua.ueba.support.engine;

import com.chua.deeplearning.support.onnx.ueba.AutoEncoderIpTranslator;
import com.chua.deeplearning.support.onnx.ueba.LstmAttentionBehaviorTranslator;
import com.chua.ueba.support.config.UebaConfig;
import com.chua.ueba.support.dto.BehaviorProfile;
import com.chua.ueba.support.dto.IpAnomalyResult;
import com.chua.ueba.support.dto.TrafficEvent;
import com.chua.ueba.support.dto.UebaResult;
import com.chua.ueba.support.feature.FeatureExtractor;
import com.chua.ueba.support.llm.MiniMindUebaAnalyzer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * UEBA 综合分析引擎。
 * <p>
 * 编排 IP 异常流量检测（AutoEncoder）、用户操作行为序列分析（LSTM/GRU + Attention）
 * 与语义解释（MiniMind），并输出综合风险分数与等级。流程：</p>
 * <ol>
 *   <li>将流量事件加入实体滑动窗口</li>
 *   <li>AutoEncoder 计算 IP 聚合特征重建误差，得到 IP 异常结果</li>
 *   <li>LSTM/GRU 对行为序列分类，得到行为画像</li>
 *   <li>加权合并两个分数得到综合风险，映射风险等级</li>
 *   <li>MiniMind（或模板）生成人可读解释</li>
 * </ol>
 * <p>任一 ONNX 模型缺失或推理失败时自动回退到规则评分，分析流程不中断。
 * 线程安全：状态（窗口、模型会话）内部同步，可多线程并发调用 {@link #analyze}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UebaEngine implements AutoCloseable {

    /** classpath 默认配置文件 */
    private static final String DEFAULT_CONFIG_RESOURCE = "ueba-config.yaml";

    /** LOW 风险阈值 */
    private static final double LOW_RISK_THRESHOLD = 0.15d;

    /** AutoEncoder 自适应阈值系数（配置阈值为 0 时使用 err * 系数） */
    private static final double ADAPTIVE_THRESHOLD_FACTOR = 1.2d;

    /** AutoEncoder 等级映射：MEDIUM 最小误差比 */
    private static final double AE_MEDIUM_RATIO = 1.2d;

    /** AutoEncoder 等级映射：HIGH 最小误差比 */
    private static final double AE_HIGH_RATIO = 2.0d;

    /** AutoEncoder 等级映射：CRITICAL 最小误差比 */
    private static final double AE_CRITICAL_RATIO = 3.0d;

    /** attack 类别的风险分值 */
    private static final double ATTACK_RISK = 0.9d;

    /** suspicious 类别的风险分值 */
    private static final double SUSPICIOUS_RISK = 0.5d;

    /** normal 类别的风险分值 */
    private static final double NORMAL_RISK = 0.1d;

    /** 实体键：IP 与 Session 均缺失时使用 */
    private static final String UNKNOWN_ENTITY = "unknown";

    /** UEBA 配置 */
    private final UebaConfig config;

    /** 特征提取器 */
    private final FeatureExtractor featureExtractor;

    /** 实体窗口跟踪器 */
    private final IpBehaviorTracker tracker;

    /** AutoEncoder 推理器 */
    private final AutoEncoderIpTranslator autoEncoder;

    /** LSTM/GRU 序列推理器 */
    private final LstmAttentionBehaviorTranslator lstm;

    /** MiniMind 解释器（enableLlm=false 时为 null） */
    private final MiniMindUebaAnalyzer llm;

    /** 规则评分器 */
    private final RuleBasedScorer ruleScorer;

    /** 行为序列长度 */
    private final int seqLen;

    /** 每步数值特征数量 */
    private final int numNumeric;

    /** 类别数量 */
    private final int numClasses;

    /**
     * 构造引擎，启用 MiniMind。
     *
     * @param config UEBA 配置，不能为 null，且必须包含 features/autoEncoder/lstm/risk
     * @throws IllegalArgumentException 当配置缺失必要段落时
     */
    public UebaEngine(UebaConfig config) {
        this(config, true);
    }

    /**
     * 构造引擎。
     *
     * @param config      UEBA 配置，不能为 null，且必须包含 features/autoEncoder/lstm/risk
     * @param enableLlm   是否启用 MiniMind 语义解释
     * @throws IllegalArgumentException 当配置缺失必要段落时
     */
    public UebaEngine(UebaConfig config, boolean enableLlm) {
        Objects.requireNonNull(config, "config must not be null");
        validateConfig(config);
        this.config = config;
        this.featureExtractor = new FeatureExtractor(config);
        this.ruleScorer = new RuleBasedScorer(featureExtractor);
        this.tracker = new IpBehaviorTracker(config.getLstm().getWindowSize() * 4);
        int inputDim = config.getFeatures().size();
        this.autoEncoder = new AutoEncoderIpTranslator(inputDim,
                config.getAutoEncoder().getModelFile(), config.getAutoEncoder().getModelPath());
        this.seqLen = config.getLstm().getWindowSize();
        this.numNumeric = config.getLstm().getNumNumericFeatures();
        this.numClasses = config.getLstm().getNumClasses();
        this.lstm = new LstmAttentionBehaviorTranslator(seqLen, numNumeric, numClasses,
                config.getLstm().getModelFile(), config.getLstm().getModelPath());
        this.llm = enableLlm ? new MiniMindUebaAnalyzer() : null;
        log.info("[UEBA-Engine] 初始化完成: inputDim={}, seqLen={}, numClasses={}, llm={}",
                inputDim, seqLen, numClasses, llm != null && llm.isAvailable());
    }

    /**
     * 校验配置必要段落。
     *
     * @param config 配置对象，不能为 null
     * @throws IllegalArgumentException 当 features/autoEncoder/lstm/risk 缺失时
     */
    private static void validateConfig(UebaConfig config) {
        if (config.getFeatures() == null || config.getFeatures().isEmpty()) {
            throw new IllegalArgumentException("config.features 不能为空");
        }
        if (config.getAutoEncoder() == null) {
            throw new IllegalArgumentException("config.autoEncoder 不能为空");
        }
        if (config.getLstm() == null) {
            throw new IllegalArgumentException("config.lstm 不能为空");
        }
        if (config.getRisk() == null) {
            throw new IllegalArgumentException("config.risk 不能为空");
        }
    }

    /**
     * 从 classpath 加载默认配置并构造引擎。
     *
     * @return 使用默认配置的引擎实例
     * @throws IllegalStateException 当 classpath 缺少 ueba-config.yaml 时
     * @throws UncheckedIOException 当配置文件读取失败时
     */
    public static UebaEngine loadDefault() {
        try (InputStream in = UebaEngine.class.getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 " + DEFAULT_CONFIG_RESOURCE);
            }
            return new UebaEngine(UebaConfig.load(in));
        } catch (IOException e) {
            throw new UncheckedIOException("加载默认 UEBA 配置失败", e);
        }
    }

    /**
     * 对单条流量事件执行综合分析。
     *
     * @param event 流量事件，不能为 null
     * @return 综合分析结果，绝不为 null
     * @throws IllegalArgumentException 当 event 为 null 时
     */
    public UebaResult analyze(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        tracker.add(event);
        String entity = entityKey(event);
        List<TrafficEvent> window = tracker.snapshot(entity);

        IpAnomalyResult ipAnomaly = detectIp(entity, window);
        BehaviorProfile behavior = analyzeBehavior(entity, window);
        double risk = computeRisk(ipAnomaly, behavior);
        UebaResult.RiskLevel level = classifyRisk(risk);
        String explanation = llm != null
                ? llm.explain(ipAnomaly, behavior, risk)
                : templateExplain(ipAnomaly, behavior, risk);

        return UebaResult.builder()
                .entityId(entity)
                .ipAnomaly(ipAnomaly)
                .behavior(behavior)
                .riskScore(risk)
                .riskLevel(level)
                .explanation(explanation)
                .analyzedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * IP 异常检测：优先 AutoEncoder，失败回退规则评分。
     *
     * @param entityId 实体标识
     * @param window   窗口事件列表
     * @return IP 异常结果，绝不为 null
     */
    private IpAnomalyResult detectIp(String entityId, List<TrafficEvent> window) {
        if (autoEncoder.isAvailable()) {
            try {
                float[] features = featureExtractor.extractIpFeatures(window);
                double err = autoEncoder.reconstructionError(features);
                double threshold = resolveAeThreshold(err);
                boolean anomalous = err > threshold;
                String reason = anomalous
                        ? String.format("AutoEncoder 重建误差 %.4f 超过阈值 %.4f", err, threshold)
                        : "访问模式正常";
                return IpAnomalyResult.builder()
                        .ip(entityId)
                        .reconstructionError(err)
                        .anomalous(anomalous)
                        .level(mapAeLevel(err, threshold, anomalous))
                        .reason(reason)
                        .threshold(threshold)
                        .build();
            } catch (Exception e) {
                log.warn("[UEBA-Engine] AutoEncoder 推理失败，回退规则评分: {}", e.getMessage());
            }
        }
        return ruleScorer.scoreIp(entityId, window);
    }

    /**
     * 解析 AutoEncoder 异常阈值。
     *
     * @param err 当前重建误差
     * @return 配置阈值大于 0 时返回配置值，否则返回 err * 自适应系数
     */
    private double resolveAeThreshold(double err) {
        double threshold = config.getAutoEncoder().getThreshold();
        if (threshold > 0.0d) {
            return threshold;
        }
        return err * ADAPTIVE_THRESHOLD_FACTOR;
    }

    /**
     * 根据误差与阈值比值映射异常等级。
     *
     * @param err       重建误差
     * @param threshold 异常阈值
     * @param anomalous 是否异常
     * @return 异常等级
     */
    private static IpAnomalyResult.AnomalyLevel mapAeLevel(double err, double threshold, boolean anomalous) {
        if (!anomalous) {
            return IpAnomalyResult.AnomalyLevel.NORMAL;
        }
        double ratio = err / threshold;
        if (ratio >= AE_CRITICAL_RATIO) {
            return IpAnomalyResult.AnomalyLevel.CRITICAL;
        }
        if (ratio >= AE_HIGH_RATIO) {
            return IpAnomalyResult.AnomalyLevel.HIGH;
        }
        if (ratio >= AE_MEDIUM_RATIO) {
            return IpAnomalyResult.AnomalyLevel.MEDIUM;
        }
        return IpAnomalyResult.AnomalyLevel.LOW;
    }

    /**
     * 行为分析：优先 LSTM/GRU 序列模型，失败回退规则评分。
     *
     * @param entityId 实体标识
     * @param window   窗口事件列表
     * @return 行为画像，绝不为 null
     */
    private BehaviorProfile analyzeBehavior(String entityId, List<TrafficEvent> window) {
        if (lstm.isAvailable()) {
            try {
                int[] ids = featureExtractor.extractSequenceIds(window, seqLen);
                float[][] numeric = featureExtractor.extractSequenceNumeric(window, seqLen, numNumeric);
                LstmAttentionBehaviorTranslator.Prediction prediction = lstm.predict(ids, numeric);
                int cls = prediction.classIndex();
                float[] probabilities = prediction.probabilities();
                String label = classLabel(cls);
                double confidence = probabilities[cls];
                double riskScore = classRisk(label) * confidence;
                return BehaviorProfile.builder()
                        .entityId(entityId)
                        .predictedClass(cls)
                        .label(label)
                        .confidence(confidence)
                        .classProbabilities(probabilities)
                        .recentPaths(RuleBasedScorer.recentPaths(window))
                        .riskScore(riskScore)
                        .analysisMode("ML")
                        .build();
            } catch (Exception e) {
                log.warn("[UEBA-Engine] LSTM/GRU 推理失败，回退规则评分: {}", e.getMessage());
            }
        }
        return ruleScorer.scoreBehavior(entityId, window);
    }

    /**
     * 获取类别下标对应的标签。
     *
     * @param classIndex 类别下标
     * @return 类别标签，越界时返回配置的第一个标签
     */
    private String classLabel(int classIndex) {
        List<String> labels = config.getLstm().getClassLabels();
        if (classIndex >= 0 && classIndex < labels.size()) {
            return labels.get(classIndex);
        }
        return labels.get(0);
    }

    /**
     * 类别标签到风险分值的映射。
     *
     * @param label 类别标签
     * @return 风险分值，范围 [0, 1]
     */
    private static double classRisk(String label) {
        return switch (label) {
            case "attack" -> ATTACK_RISK;
            case "suspicious" -> SUSPICIOUS_RISK;
            default -> NORMAL_RISK;
        };
    }

    /**
     * 合并 IP 异常分数与行为分数。
     *
     * @param ipAnomaly IP 异常结果，不能为 null
     * @param behavior  行为画像，不能为 null
     * @return 综合风险分数，范围 [0, 1]
     * @throws IllegalArgumentException 当任一参数为 null 时
     */
    private double computeRisk(IpAnomalyResult ipAnomaly, BehaviorProfile behavior) {
        Objects.requireNonNull(ipAnomaly, "ipAnomaly must not be null");
        Objects.requireNonNull(behavior, "behavior must not be null");
        double ipScore = ipAnomaly.getThreshold() > 0.0d
                ? clamp(ipAnomaly.getReconstructionError() / ipAnomaly.getThreshold(), 0.0d, 1.0d)
                : 0.0d;
        double ipWeight = config.getRisk().getIpWeight();
        double behaviorWeight = config.getRisk().getBehaviorWeight();
        double risk = ipWeight * ipScore + behaviorWeight * behavior.getRiskScore();
        return clamp(risk, 0.0d, 1.0d);
    }

    /**
     * 将风险分数映射为风险等级。
     *
     * @param score 风险分数，范围 [0, 1]
     * @return 风险等级
     */
    private UebaResult.RiskLevel classifyRisk(double score) {
        if (score >= config.getRisk().getHighThreshold()) {
            return UebaResult.RiskLevel.HIGH;
        }
        if (score >= config.getRisk().getMediumThreshold()) {
            return UebaResult.RiskLevel.MEDIUM;
        }
        if (score > LOW_RISK_THRESHOLD) {
            return UebaResult.RiskLevel.LOW;
        }
        return UebaResult.RiskLevel.NORMAL;
    }

    /**
     * 未启用 MiniMind 时的模板解释。
     *
     * @param ipAnomaly IP 异常结果
     * @param behavior  行为画像
     * @param riskScore 综合风险分数
     * @return 模板解释文本
     */
    private static String templateExplain(IpAnomalyResult ipAnomaly, BehaviorProfile behavior, double riskScore) {
        String level = ipAnomaly.getLevel() == null ? "NORMAL" : ipAnomaly.getLevel().name();
        return String.format("实体 %s：IP 异常等级 %s，行为类别 %s，综合风险 %.2f。",
                ipAnomaly.getIp(), level, behavior.getLabel(), riskScore);
    }

    /**
     * 提取实体的窗口键。
     *
     * @param event 流量事件，不能为 null
     * @return 实体标识
     */
    private static String entityKey(TrafficEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.getIp() != null && !event.getIp().isBlank()) {
            return event.getIp();
        }
        if (event.getSessionId() != null && !event.getSessionId().isBlank()) {
            return event.getSessionId();
        }
        return UNKNOWN_ENTITY;
    }

    /**
     * 将值限制在 [min, max] 区间。
     *
     * @param value 原值
     * @param min   下界
     * @param max   上界
     * @return 钳制后的值
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 释放全部模型与跟踪器资源。
     */
    @Override
    public void close() {
        autoEncoder.close();
        lstm.close();
        if (llm != null) {
            llm.close();
        }
        tracker.close();
        log.info("[UEBA-Engine] 资源已释放");
    }
}
