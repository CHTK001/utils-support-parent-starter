package com.chua.deeplearning.support.recognition;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com\.chua\.deeplearning\.support\.engine\.ModelRegistry;
import com.chua.deeplearning.support.pose.PoseKeypoint;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 姿态关键点识别管线。
 *
 * <p>调度已注册的姿态估计模型（如 vit-pose、yolov8n-pose 等），
 * 将模型输出的关键点数据统一转换为 {@link PoseKeypoint} 列表
 * （name / x / y / confidence）。支持多人姿态（输出嵌套列表）。</p>
 *
 * <pre>{@code
 * PosePipeline pipeline = PosePipeline.builder()
 *         .model("vit-pose")
 *         .build();
 * List&lt;PoseKeypoint&gt; keypoints = pipeline.estimate(imageBytes);
 * List&lt;List&lt;PoseKeypoint&gt;&gt; multi = pipeline.estimateMulti(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PosePipeline {

    /**
     * 节点：识别
     */
    private static final String NODE_RECOGNIZE = "recognize";

    /**
     * 节点：收集
     */
    private static final String NODE_COLLECT = "collect";

    /**
     * 节点：终止
     */
    private static final String NODE_END = "end";

    /**
     * 标准 COCO 17 关键点名称。
     */
    private static final String[] COCO_KEYPOINT_NAMES = {
            "nose", "left_eye", "right_eye", "left_ear", "right_ear",
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_hip", "right_hip",
            "left_knee", "right_knee", "left_ankle", "right_ankle"
    };

    /**
     * 识别引擎。
     */
    private final IdentificationEngine engine;

    /**
     * 姿态模型名称。
     */
    private final String model;

    /**
     * 识别管线实例。
     */
    private final Pipeline pipeline;

    /**
     * 构造识别管线。
     *
     * @param model 模型名称
     */
    public PosePipeline(String model) {
        this.engine = AbstractIdentificationEngine.getInstance();
        this.model = Objects.requireNonNull(model, "model");
        this.pipeline = buildPipeline();
    }

    /**
     * 构建器。
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式构建器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 模型名称。
         */
        private String model;

        /**
         * 设置模型名称。
         *
         * @param model 模型
         * @return this
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 构建。
         *
         * @return PosePipeline
         */
        public PosePipeline build() {
            return new PosePipeline(model);
        }
    }

    /**
     * 编排识别管线（识别 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildPipeline() {
        return PipelineBuilder.newBuilder("pose-estimate")
                .task(NODE_RECOGNIZE, ctx -> {
                    PoseContext pc = current(ctx);
                    if (pc.currentImage() == null) {
                        return null;
                    }
                    pc.addResult(estimateSingle(pc.currentImage()));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> null).end().taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 估计单人姿态关键点。
     *
     * @param imageData 图像
     * @return 关键点列表
     */
    public List<PoseKeypoint> estimateSingle(byte[] imageData) {
        if (imageData == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) engine.get(model, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + model);
        }
        Object result = translator.translate(imageData);
        return toKeypoints(result);
    }

    /**
     * 估计单人姿态（管线形式，结果为列表）。
     *
     * @param imageData 图像
     * @return 关键点列表
     */
    public List<List<PoseKeypoint>> estimate(byte[] imageData) {
        PoseContext pc = new PoseContext(imageData);
        PipelineContext<PoseContext> ctx = new PipelineContext<>(pipeline.getId(), pc);
        ctx.setAttribute("pose", pc);
        ctx.setNextNodeId(NODE_RECOGNIZE);
        pipeline.resume(ctx);
        return pc.results();
    }

    /**
     * 估计多人姿态。
     *
     * @param imageData 图像
     * @return 多人关键点列表
     */
    public List<List<PoseKeypoint>> estimateMulti(byte[] imageData) {
        return estimate(imageData);
    }

    /**
     * 从管线上下文提取姿态上下文。
     *
     * @param ctx 管线上下文
     * @return 上下文
     */
    @SuppressWarnings("unchecked")
    private static PoseContext current(PipelineContext<?> ctx) {
        return (PoseContext) ctx.getAttribute("pose");
    }

    /**
     * 将模型输出转换为关键点列表。
     *
     * <p>支持以下输出形态：</p>
     * <ul>
     *   <li>{@code float[][3]}（vit-pose 风格，每个关键点 x/y/confidence）</li>
     *   <li>{@code List<PoseKeypoint>} 直接返回</li>
     *   <li>{@code float[][][3]} 多人姿态，取第一人</li>
     * </ul>
     *
     * @param result 模型输出
     * @return 关键点列表
     */
    private static List<PoseKeypoint> toKeypoints(Object result) {
        if (result == null) {
            return List.of();
        }
        if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof PoseKeypoint) {
            @SuppressWarnings("unchecked")
            List<PoseKeypoint> keypoints = (List<PoseKeypoint>) list;
            return keypoints;
        }
        if (result instanceof float[][] keypoints2d) {
            return convert2d(keypoints2d);
        }
        if (result instanceof float[][][] keypoints3d && keypoints3d.length > 0) {
            return convert2d(keypoints3d[0]);
        }
        if (result instanceof double[][] keypoints2d) {
            float[][] floats = new float[keypoints2d.length][];
            for (int i = 0; i < keypoints2d.length; i++) {
                float[] row = new float[keypoints2d[i].length];
                for (int j = 0; j < keypoints2d[i].length; j++) {
                    row[j] = (float) keypoints2d[i][j];
                }
                floats[i] = row;
            }
            return convert2d(floats);
        }
        return List.of();
    }

    /**
     * 转换 float[x][3] 为关键点列表。
     *
     * @param keypoints 关键点数组
     * @return 关键点列表
     */
    private static List<PoseKeypoint> convert2d(float[][] keypoints) {
        List<PoseKeypoint> result = new ArrayList<>();
        for (int i = 0; i < keypoints.length; i++) {
            float[] kp = keypoints[i];
            if (kp == null || kp.length < 3) {
                continue;
            }
            String name = i < COCO_KEYPOINT_NAMES.length ? COCO_KEYPOINT_NAMES[i] : "kp_" + i;
            result.add(new PoseKeypoint(name, kp[0], kp[1], kp[2]));
        }
        return List.copyOf(result);
    }

    /**
     * 枚举可用姿态模型。
     *
     * @return 能力分组 → 模型 ID 列表
     */
    public Map<String, List<String>> listModels() {
        try {
            ModelRegistry.discoverAll();
        } catch (Exception e) {
            log.warn("模型注册表发现失败: {}", e.getMessage());
        }
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (ModelRegistry.Entry entry : ModelRegistry.getAll()) {
            String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
            // 排除手部姿态（hand-pose），仅人体姿态模型
            boolean pose = !RecognitionSupport.contains(name, "hand-pose", "hand_pose", "handpose")
                    && RecognitionSupport.contains(name, "pose", "vit-pose", "yolov8n-pose");
            if (pose) {
                grouped.computeIfAbsent("pose", k -> new LinkedHashSet<>()).add(entry.modelId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    /**
     * 创建标注管线，支持一键绘制检测结果。
     *
     * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(0.5f);
    }
\}
