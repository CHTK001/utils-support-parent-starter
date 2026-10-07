package com.chua.deeplearning.support.laya;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.decision.Decision;
import com.chua.common.support.ai.decision.DecisionBatch;
import com.chua.common.support.ai.decision.DecisionClient;
import com.chua.common.support.ai.decision.DecisionException;
import com.chua.common.support.ai.decision.DecisionQuery;
import com.chua.common.support.ai.decision.DecisionSetting;
import com.chua.common.support.ai.decision.DecisionType;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pig4cloud.laya.LayaEngine;
import com.pig4cloud.laya.Question;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laya 本地概率决策客户端（SPI provider="laya"）。
 *
 * <p>基于 laya-java（JVM 内 ONNX Runtime 前向推理）实现 {@link DecisionClient}：
 * 把 {@link DecisionQuery} 的 NOUL / CHOICE / SCORE 三型映射为 Laya 的
 * {@code noul} / {@code choice} / {@code score} 问题，一次
 * {@link LayaEngine#predict(Object, Map)} 前向回答全部问题，
 * 再把答案转回 {@link DecisionBatch}。线程安全：{@link LayaEngine} 自身
 * 方法已同步，本类另用实例锁串行化「懒加载引擎 + 推理」的组合操作。</p>
 *
 * <p>模型资产目录须包含 {@code config.json}、{@code model.onnx}、
 * {@code tokenizer.json}（见 laya-java-model-v0.1.0）。调用示例：</p>
 * <pre>{@code
 *   DecisionBatch batch = DecisionClient.create("laya", "/models/laya-java-model")
 *       .decide("重复扣款了，请退款。", List.of(
 *           DecisionQuery.choice("department", "Which department should handle this message?",
 *               Map.of("billing", "invoices, payments, refunds",
 *                      "support", "bugs, outages, system errors")),
 *           DecisionQuery.score("urgency", "How urgent is this request?",
 *               List.of("not urgent", "soon", "critical")),
 *           DecisionQuery.noul("refund", "Does the customer ask for money back?")));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("laya")
public class LayaDecisionClient implements DecisionClient {

    /**
     * 本地 JSON 映射器，只做 laya-java 答案节点的字段读取
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 引擎缓存：同一模型目录复用同一引擎实例，避免重复加载权重
     */
    private static final ConcurrentHashMap<String, LayaEngine> ENGINES = new ConcurrentHashMap<>();

    /**
     * 客户端配置
     */
    private final DecisionSetting setting;

    /**
     * 模型资产目录
     */
    private final String modelPath;

    /**
     * 推理线程数
     */
    private final int threads;

    /**
     * 校准文件路径，可为空
     */
    private final String calibrationPath;

    /**
     * 已加载的引擎（懒加载），关闭后置空
     */
    private volatile LayaEngine engine;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 创建 Laya 决策客户端。
     *
     * @param setting 客户端配置，modelPath 必填
     */
    public LayaDecisionClient(DecisionSetting setting) {
        if (setting == null || setting.getModelPath() == null || setting.getModelPath().isBlank()) {
            throw new IllegalArgumentException("Laya 决策需要 modelPath（包含 config.json / model.onnx / tokenizer.json 的目录）");
        }
        if (setting.getThreads() != null && setting.getThreads() < 1) {
            throw new IllegalArgumentException("threads 必须为正数，实际 " + setting.getThreads());
        }
        this.setting = setting;
        this.modelPath = setting.getModelPath().trim();
        this.threads = setting.getThreads() != null ? setting.getThreads() : 4;
        this.calibrationPath = setting.getCalibrationPath() == null || setting.getCalibrationPath().isBlank()
                ? null : setting.getCalibrationPath().trim();
    }

    @Override
    /**
     * 决策求值
     */
    public DecisionBatch decide(Object state, List<DecisionQuery> queries) {
        Objects.requireNonNull(queries, "queries 不能为 null");
        if (queries.isEmpty()) {
            throw new DecisionException("queries 不能为空");
        }
        if (closed) {
            throw new DecisionException("Laya 决策客户端已关闭");
        }
        Map<String, Question> questions = Wraps.linkedMap();
        for (DecisionQuery query : queries) {
            if (query == null) {
                throw new DecisionException("queries 中存在 null 问题");
            }
            if (questions.put(query.id(), toQuestion(query)) != null) {
                throw new DecisionException(query.id(), "queries 中存在重复的问题标识");
            }
        }
        ObjectNode result;
        synchronized (this) {
            LayaEngine current = engine();
            try {
                result = current.predict(state, questions);
            } catch (Exception e) {
                throw new DecisionException("Laya 推理失败: " + e.getMessage(), e);
            }
        }
        return toBatch(queries, result);
    }

    /**
     * 把 SPI 问题映射为 Laya 问题。
     *
     * <p>映射约定：NOUL → {@code noul}（问题措辞取 {@code question}，
     * criteria 透传结论说明）；CHOICE → {@code choice}（options 保持声明顺序）；
     * SCORE → {@code score}（levels 即有序档位）。CHOICE 的选项顺序有位置意义，
     * 因此调用方应传入有序 Map（如 {@link LinkedHashMap}）。</p>
     *
     * @param query SPI 问题
     * @return Laya 问题
     */
    static Question toQuestion(DecisionQuery query) {
        if (query.type().isNoul()) {
            if (query.criteria() == null) {
                return Question.noul(query.question());
            }
            Map<String, String> criteria = Wraps.linkedMap(query.criteria());
            return new Question("noul", query.question(), MAPPER.valueToTree(criteria));
        }
        if (query.type().isChoice()) {
            return Question.choice(query.question(), Wraps.linkedMap(query.options()));
        }
        return Question.score(query.question(), Wraps.list(query.levels()));
    }

    /**
     * 把 Laya 答案转回 SPI 批次。
     *
     * <p>映射约定：{@code choice} 取答案标签与完整分布；{@code score}
     * 的答案是期望值下标，取 argmax 档位的标签作结论（与 jevlaya 文档
     * 「想要标签就对 probabilities 取 argmax」一致），分布键按下标
     * 映射回 levels 元素；{@code noul} 取 P(true)。分布只做钳位不归一，
     * 保证落在 {@link Decision} 的构造约束内。</p>
     *
     * @param queries 原问题列表（提供候选集与顺序）
     * @param result  Laya predict 返回的答案 envelope
     * @return 决策结果批次
     */
    static DecisionBatch toBatch(List<DecisionQuery> queries, ObjectNode result) {
        if (result == null || !result.path("answers").isObject()) {
            throw new DecisionException("Laya 返回体缺失 answers 对象");
        }
        JsonNode answers = result.get("answers");
        List<Decision> decisions = Wraps.list(queries.size());
        for (DecisionQuery query : queries) {
            JsonNode answer = answers.get(query.id());
            if (answer == null || !answer.isObject()) {
                throw new DecisionException(query.id(), "Laya 返回体缺失该问题的答案");
            }
            decisions.add(toDecision(query, answer));
        }
        return new DecisionBatch(decisions);
    }

    /**
     * 把单个 Laya 答案转为 SPI 答案。
     *
     * <p>分布只做钳位不归一：{@link Decision} 刻意不校验分布求和为 1，
     * Laya 输出已是 softmax 分布，原样透传最能保留模型真实置信度。</p>
     *
     * @param query  原问题
     * @param answer Laya 答案节点
     * @return SPI 答案
     */
    private static Decision toDecision(DecisionQuery query, JsonNode answer) {
        String type = answer.path("type").asText(null);
        if (query.type().isNoul()) {
            requireType(query, answer, "noul", type);
            double p = answer.path("noul").asDouble(Double.NaN);
            if (Double.isNaN(p)) {
                throw new DecisionException(query.id(), "noul 答案缺失概率字段");
            }
            return Decision.noul(query.id(), clamp(p));
        }
        if (query.type().isChoice()) {
            requireType(query, answer, "choice", type);
            String label = answer.path("choice").asText(null);
            JsonNode probabilities = answer.path("probabilities");
            if (label == null || !probabilities.isObject()) {
                throw new DecisionException(query.id(), "choice 答案缺失 choice / probabilities 字段");
            }
            Map<String, Double> distribution = readDistribution(query, probabilities);
            if (!distribution.containsKey(label)) {
                throw new DecisionException(query.id(), "答案标签 " + label + " 不在概率分布中");
            }
            return Decision.of(query.id(), DecisionType.CHOICE, label, distribution);
        }
        requireType(query, answer, "score", type);
        JsonNode probabilities = answer.path("probabilities");
        if (!probabilities.isObject() || probabilities.isEmpty()) {
            throw new DecisionException(query.id(), "score 答案缺失 probabilities 字段");
        }
        // Laya 的 score 答案是期望值（浮点下标）；取 argmax 档位的标签作结论，
        // 与 jevlaya 文档「想要标签就对 probabilities 取 argmax」一致。
        List<String> levels = query.levels();
        Map<String, Double> distribution = Wraps.linkedMap();
        String best = null;
        double bestP = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < levels.size(); i++) {
            double p = probabilities.path(String.valueOf(i)).asDouble(Double.NaN);
            if (Double.isNaN(p)) {
                throw new DecisionException(query.id(), "score 分布缺失下标 " + i + " 的概率");
            }
            double clamped = clamp(p);
            distribution.put(levels.get(i), clamped);
            if (clamped > bestP) {
                bestP = clamped;
                best = levels.get(i);
            }
        }
        ensurePositive(query.id(), distribution);
        return Decision.of(query.id(), DecisionType.SCORE, best, distribution);
    }

    /**
     * 校验答案类型与问题类型一致。
     */
    private static void requireType(DecisionQuery query, JsonNode answer, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new DecisionException(query.id(), "答案类型为 " + actual + "，问题类型要求 " + expected);
        }
    }

    /**
     * 读取 choice 分布并做钳位（只钳位不归一，保留模型原始置信度）。
     */
    private static Map<String, Double> readDistribution(DecisionQuery query, JsonNode probabilities) {
        Map<String, Double> distribution = Wraps.linkedMap();
        probabilities.fields().forEachRemaining(e -> distribution.put(e.getKey(), clamp(e.getValue().asDouble(0.0D))));
        if (distribution.isEmpty()) {
            throw new DecisionException(query.id(), "probabilities 为空");
        }
        ensurePositive(query.id(), distribution);
        return distribution;
    }

    /**
     * 把概率钳位到 [0, 1]。
     */
    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /**
     * 校验分布之和大于 0（满足 {@link Decision} 构造约束），不做重缩放：
     * Laya 输出已是 softmax 分布，原样透传最能保留模型真实置信度。
     */
    private static void ensurePositive(String queryId, Map<String, Double> distribution) {
        double sum = 0.0D;
        for (double value : distribution.values()) {
            sum += value;
        }
        if (sum <= 0.0D) {
            throw new DecisionException(queryId, "概率分布之和必须大于 0");
        }
    }

    /**
     * 获取或懒加载引擎。
     *
     * <p>同一模型目录全局复用同一引擎：首次加载后放入 {@link #ENGINES}，
     * 后续同目录客户端复用；调用方 {@link #close()} 只释放自己持有的引用，
     * 不关闭共享引擎（进程级单例）。</p>
     *
     * @return 已加载的引擎
     */
    private LayaEngine engine() {
        LayaEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (engine != null) {
                return engine;
            }
            if (closed) {
                throw new DecisionException("Laya 决策客户端已关闭");
            }
            Path directory = Path.of(modelPath);
            if (!Files.isDirectory(directory)) {
                throw new DecisionException("模型目录不存在: " + modelPath);
            }
            String key = directory.toAbsolutePath().normalize() + "|threads=" + threads
                    + "|calibration=" + (calibrationPath == null ? "" : calibrationPath);
            LayaEngine cached = ENGINES.get(key);
            if (cached != null) {
                engine = cached;
                return engine;
            }
            try {
                LayaEngine loaded = LayaEngine.load(directory, threads);
                if (calibrationPath != null) {
                    loaded.loadCalibration(Path.of(calibrationPath));
                }
                LayaEngine raced = ENGINES.putIfAbsent(key, loaded);
                if (raced != null) {
                    try {
                        loaded.close();
                    } catch (Exception ignored) {
                        log.debug("关闭竞态中多余的 Laya 引擎失败", ignored);
                    }
                    loaded = raced;
                }
                engine = loaded;
                log.info("Laya 引擎已加载: {}", directory.toAbsolutePath());
                return engine;
            } catch (DecisionException e) {
                throw e;
            } catch (Exception e) {
                throw new DecisionException("Laya 引擎加载失败(" + modelPath + "): " + e.getMessage(), e);
            }
        }
    }

    @Override
    /**
     * 关闭客户端
     */
    public void close() {
        closed = true;
        engine = null;
    }
}
