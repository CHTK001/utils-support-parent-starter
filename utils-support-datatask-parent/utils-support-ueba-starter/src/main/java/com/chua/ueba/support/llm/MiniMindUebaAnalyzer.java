package com.chua.ueba.support.llm;

import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.chua.deeplearning.support.onnx.text.minimind.MiniMindTranslator;
import com.chua.ueba.support.dto.BehaviorProfile;
import com.chua.ueba.support.dto.IpAnomalyResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * minimind 语义解释器。
 * <p>
 * 复用 deeplearning-onnx-启动 中的 {@link MiniMindTranslator}，将结构化分析结果
 * （IP 异常 + 行为画像 + 风险分数）组装为提示词，生成人可读的风险解释。
 * 模型通过系统属性 {@code ueba.minimind.model.dir} 指定目录（需包含
 * {@code model.onnx} 与 {@code tokenizer.json}）；模型缺失或推理失败时
 * 自动回退到模板解释，不阻塞主流程。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniMindUebaAnalyzer implements AutoCloseable {

    /**
     * 系统属性名：minimind 模型目录
    */
    public static final String MODEL_DIR_PROPERTY = "ueba.minimind.model.dir";

    /**
     * 提示词中最近路径的最大展示条数
    */
    private static final int MAX_RECENT_PATHS = 5;

    /**
     * 异常等级最大展示条数（正常等级不生成解释）
    */
    private static final String NORMAL_LEVEL = "NORMAL";

    /**
     * minimind 模型（懒加载，可能为 空）
    */
    private final ZooModel<String, String> model;

    /**
     * 模型是否可用
     */
    private final boolean available;

    /**
     * 构造解释器并尝试加载 minimind 模型。
     * <p>
     * 模型加载失败仅记录警告，实例仍可使用模板解释能力。
     * </p>
     */
    public MiniMindUebaAnalyzer() {
        ZooModel<String, String> loaded = null;
        boolean ok = false;
        String dir = System.getProperty(MODEL_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            log.warn("[UEBA-MiniMind] 未设置 " + MODEL_DIR_PROPERTY + "，使用模板解释");
        } else {
            try {
                loaded = buildModel(Paths.get(dir));
                ok = true;
                log.info("[UEBA-MiniMind] 模型加载成功: {}", dir);
            } catch (Exception e) {
                log.warn("[UEBA-MiniMind] 模型加载失败，使用模板解释: {}", e.getMessage());
            }
        }
        this.model = loaded;
        this.available = ok;
    }

    /**
     * 构建 DJL 模型实例。
     *
     * @param dir 模型目录，需包含 模型.onnx 与 tokenizer.json
     * @return 加载完成的模型
     * @throws IOException    当模型文件缺失时
     * @throws ModelException 当 DJL 模型加载失败时
     */
    private static ZooModel<String, String> buildModel(Path dir) throws IOException, ModelException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("MiniMind 模型目录不存在: " + dir);
        }
        Criteria<String, String> criteria = Criteria.builder()
                .setTypes(String.class, String.class)
                .optModelPath(dir)
                .optEngine("OnnxRuntime")
                .optTranslator(new MiniMindTranslator())
                .build();
        return criteria.loadModel();
    }

    /**
     * 是否可用（模型已加载）。
     *
     * @return true 表示 minimind 可用
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 生成风险语义解释。
     *
     * @param ipAnomaly IP 异常检测结果，不能为 空
     * @param behavior  行为画像，不能为 空
     * @param riskScore 综合风险分数，范围 [0, 1]
     * @return 解释文本；模型不可用时返回模板解释，绝不为 空
     * @throws IllegalArgumentException 当 ipanomaly 或 行为 为 空 时
     */
    public String explain(IpAnomalyResult ipAnomaly, BehaviorProfile behavior, double riskScore) {
        Objects.requireNonNull(ipAnomaly, "ipAnomaly must not be null");
        Objects.requireNonNull(behavior, "behavior must not be null");
        if (!available || model == null) {
            return buildTemplate(ipAnomaly, behavior, riskScore);
        }
        try (Predictor<String, String> predictor = model.newPredictor()) {
            String result = predictor.predict(buildPrompt(ipAnomaly, behavior, riskScore));
            if (result == null || result.isBlank()) {
                log.debug("[UEBA-MiniMind] 输出为空，回退模板解释");
                return buildTemplate(ipAnomaly, behavior, riskScore);
            }
            return result.trim();
        } catch (Exception e) {
            log.warn("[UEBA-MiniMind] 推理失败，回退模板解释: {}", e.getMessage());
            return buildTemplate(ipAnomaly, behavior, riskScore);
        }
    }

    /**
     * 组装 minimind 提示词。
     *
     * @param ipAnomaly IP 异常检测结果
     * @param behavior  行为画像
     * @param riskScore 综合风险分数
     * @return 提示词文本
     */
    private static String buildPrompt(IpAnomalyResult ipAnomaly, BehaviorProfile behavior, double riskScore) {
        String level = ipAnomaly.getLevel() == null ? NORMAL_LEVEL : ipAnomaly.getLevel().name();
        String paths = String.join(", ", truncate(behavior.getRecentPaths()));
        return """
                你是安全运营专家。请根据以下用户实体行为分析结果，用中文输出一段不超过 100 字的风险解释，并给出处置建议。

                实体: %s
                IP异常等级: %s
                IP异常原因: %s
                行为类别: %s (置信度 %.2f)
                最近访问路径: %s
                综合风险分数: %.2f

                请以"风险判断：...，处置建议：..."格式输出。
                """.formatted(
                ipAnomaly.getIp(), level,
                ipAnomaly.getReason() == null ? "无" : ipAnomaly.getReason(),
                behavior.getLabel(), behavior.getConfidence(),
                paths, riskScore);
    }

    /**
     * 生成模板解释（模型不可用时的回退）。
     *
     * @param ipAnomaly IP 异常检测结果
     * @param behavior  行为画像
     * @param riskScore 综合风险分数
     * @return 模板解释文本
     */
    private static String buildTemplate(IpAnomalyResult ipAnomaly, BehaviorProfile behavior, double riskScore) {
        String level = ipAnomaly.getLevel() == null ? NORMAL_LEVEL : ipAnomaly.getLevel().name();
        return String.format("实体 %s：IP 异常等级 %s（原因：%s），行为类别 %s（置信度 %.2f），综合风险 %.2f。",
                ipAnomaly.getIp(), level,
                ipAnomaly.getReason() == null ? "无" : ipAnomaly.getReason(),
                behavior.getLabel(), behavior.getConfidence(), riskScore);
    }

    /**
     * 截取路径列表，最多保留 {@link #MAX_RECENT_PATHS} 条。
     *
     * @param paths 路径列表，允许为 空
     * @return 截取后的列表，绝不为 空
     */
    private static List<String> truncate(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.size() <= MAX_RECENT_PATHS ? paths : paths.subList(0, MAX_RECENT_PATHS);
    }

    /**
     * 释放模型资源。
     */
    @Override
    public void close() {
        if (model != null) {
            model.close();
        }
        log.debug("[UEBA-MiniMind] 模型已关闭");
    }
}
