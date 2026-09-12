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
   * 编排 IP 异常流量检测（auto编码器）、用户操作行为序列分析（LSTM/GRU + Attention）
   * 与语义解释（minimind），并输出综合风险分数与等级。流程：</p>
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

    /** 类路径 默认配置文件 */
    private static final String DEFAULT_CONFIG_RESOURCE = "ueba-config.yaml";

    /** LOW 风险阈值 */
    private static final double LOW_RISK_THRESHOLD = 0.15d;

    /** auto编码器 自适应阈值：标准差倍数（mean + k * std） */
    private static final double AE_STD_MULTIPLIER = 3.0d;

    /** auto编码器 自适应阈值：学习所需最少样本数（Warm-up 期间不告警） */
    private static final long AE_MIN_SAMPLES = 30L;

    /** auto编码器 等级映射：MEDIUM 最小误差比 */
    private static final double AE_MEDIUM_RATIO = 1.2d;

    /** auto编码器 等级映射：HIGH 最小误差比 */
    private static final double AE_HIGH_RATIO = 2.0d;

    /** auto编码器 等级映射：CRITICAL 最小误差比 */
    private static final double AE_CRITICAL_RATIO = 3.0d;

    /** attack 类别的风险分值 */
    private static final double ATTACK_RISK = 0.9d;

    /** suspicious 类别的风险分值 */
    private static final double SUSPICIOUS_RISK = 0.5d;

    /** normal 类别的风险分值 */
    private static final double NORMAL_RISK = 0.1d;

    /** 实体键：IP 与 会话 均缺失时使用 */
    private static final String UNKNOWN_ENTITY = "unknown";

    /** UEBA 配置 */
    private final UebaConfig config;

    /** 特征提取器 */
    private final FeatureExtractor featureExtractor;

    /** 实体窗口跟踪器 */
    private final IpBehaviorTracker tracker;

    /** auto编码器 推理器 */
    private final AutoEncoderIpTranslator autoEncoder;

    /** LSTM/GRU 序列推理器 */
    private final LstmAttentionBehaviorTranslator lstm;

    /** minimind 解释器（enablellm=false 时为 空） */
    private final MiniMindUebaAnalyzer llm;

    /** 规则评分器 */
    private final RuleBasedScorer ruleScorer;

    /** auto编码器 重建误差的在线统计（自适应阈值） */
    private final OnlineStats aeStats;

    /** 行为序列长度 */
    private final int seqLen;

    /** 每步数值特征数量 */
    private final int numNumeric;

    /** 类别数量 */
    private final int numClasses;

    /**
      * 构造引擎，启用 minimind。
     *
     * @param config UEBA 配置，不能为 空，且必须包含 特征/auto编码器/lstm/risk
     * @throws IllegalArgumentException 当配置缺失必要段落时
     */
    public UebaEngine(UebaConfig config) {
        this(config, true);
    }

    /**
     * 构造引擎。
     *
     * @param config      UEBA 配置，不能为 空，且必须包含 特征/auto编码器/lstm/risk
     * @param enableLlm   是否启用 minimind 语义解释
     * @throws IllegalArgumentException 当配置缺失必要段落时
     */
    public UebaEngine(UebaConfig config, boolean enableLlm) {
        Objects.requireNonNull(config, "config must not be null");
        validateConfig(config);
        this.config = config;
        this.featureExtractor = new FeatureExtractor(config);
        this.ruleScorer = new RuleBasedScorer(featureExtractor);
        this.aeStats = new OnlineStats();
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
     * @param config 配置对象，不能为 空
     * @throws IllegalArgumentException 当 特征/auto编码器/lstm/risk 缺失时
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
      * 从 类路径 加载默认配置并构造引擎。
     *
     * @return 使用默认配置的引擎实例
     * @throws IllegalStateException 当 类路径 缺少 ueba-配置.yaml 时
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
     * @param event 流量事件，不能为 空
     * @return 综合分析结果，绝不为 空
     * @throws IllegalArgumentException 当 事件 为 空 时
     */
    public UebaResult analyze(TrafficEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
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
      * IP 异常检测：优先 auto编码器，失败回退规则评分。
     *
     * @param entityId 实体标识
     * @param window   窗口事件列表
     * @return IP 异常结果，绝不为 空
     */
    private IpAnomalyResult detectIp(String entityId, List<TrafficEvent> window) {
        if (autoEncoder.isAvailable()) {
            try {
                float[] features = featureExtractor.extractIpFeatures(window);
                double err = autoEncoder.reconstructionError(features);
                aeStats.update(err);
                double threshold = resolveAeThreshold();
                boolean anomalous = aeStats.isWarmedUp() && err > threshold;
                String reason = buildAeReason(err, threshold, anomalous);
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
      * 解析 auto编码器 异常阈值。
     * <p>配置阈值大于 0 时使用配置值；否则使用在线学习阈值
     * {@code mean + k * std}。Warm-up（样本不足）期间返回无穷大，不误报。</p>
     *
     * @return 当前异常阈值
     */
    private double resolveAeThreshold() {
        double threshold = config.getAutoEncoder().getThreshold();
        if (threshold > 0.0d) {
            return threshold;
        }
        if (!aeStats.isWarmedUp()) {
            return Double.MAX_VALUE;
        }
        return aeStats.mean() + AE_STD_MULTIPLIER * aeStats.std();
    }

    /**
      * 构造 auto编码器 判定原因。
     *
     * @param err       重建误差
     * @param threshold 异常阈值
     * @param anomalous 是否异常
     * @return 判定原因文本
     */
    private static String buildAeReason(double err, double threshold, boolean anomalous) {
        if (anomalous) {
            return String.format("AutoEncoder 重建误差 %.4f 超过阈值 %.4f", err, threshold);
        }
        if (threshold == Double.MAX_VALUE) {
            return "阈值学习阶段（样本不足 " + AE_MIN_SAMPLES + "，暂不告警）";
        }
        return "访问模式正常";
    }

    /**
     * 在线统计（Welford 算法），用于自适应异常阈值。
     */
    private static final class OnlineStats {

        /** 样本数 */
        private long count;

        /** 运行均值 */
        private double mean;

        /** 运行平方差累积 */
        private double m2;

        /**
         * 更新一个样本。
         *
         * @param value 样本值
         */
        void update(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        /**
         * 是否已过学习期。
         *
         * @return true 表示样本数达到 {@link UebaEngine#AE_MIN_SAMPLES}
         */
        boolean isWarmedUp() {
            return count >= AE_MIN_SAMPLES;
        }

        /**
         * 当前均值。
         *
         * @return 均值，无样本时返回 0
         */
        double mean() {
            return count == 0L ? 0.0d : mean;
        }

        /**
         * 当前样本标准差（样本标准差，除以 n-1）。
         *
         * @return 标准差，样本数小于 2 时返回 0
         */
        double std() {
            if (count < 2L) {
                return 0.0d;
            }
            return Math.sqrt(m2 / (count - 1));
        }
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
     * @return 行为画像，绝不为 空
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
     * @return 类别标签，越界或标签列表为空时返回 "normal"
     */
    private String classLabel(int classIndex) {
        List<String> labels = config.getLstm().getClassLabels();
        if (labels != null && !labels.isEmpty() && classIndex >= 0 && classIndex < labels.size()) {
            return labels.get(classIndex);
        }
        return "normal";
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
     * @param ipAnomaly IP 异常结果，不能为 空
     * @param behavior  行为画像，不能为 空
     * @return 综合风险分数，范围 [0, 1]
     * @throws IllegalArgumentException 当任一参数为 空 时
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
      * 未启用 minimind 时的模板解释。
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
     * @param event 流量事件，不能为 空
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
      * 将值限制在 [最小, 最大] 区间。
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
