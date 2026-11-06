package com.chua.common.support.ai.generation;

import java.util.List;
import java.util.Objects;

/**
 * 3D 模型生成结果。
 *
 * <p>统一各服务商的「文生 3D / 图生 3D」返回结果，模型文件以 URL 形式给出，
 * 由调用方自行下载或转存。</p>
 *
 * @param models 生成的模型列表
 * @param prompt 生成提示词（纯图生 3D 时可为 {@code null}）
 * @author CH
 * @since 4.0.0.52
 */
public record Model3DGenerationResult(List<GeneratedModel3D> models, String prompt) {

    /**
     * 规范构造器：对模型列表做防御性拷贝。
     *
     * @param models 生成的模型列表
     * @param prompt 生成提示词
     */
    public Model3DGenerationResult {
        models = List.copyOf(Objects.requireNonNull(models, "models 不能为 null"));
    }

    /**
     * 单个生成的 3D 模型。
     *
     * @param key        模型标识
     * @param modelUrl   模型文件 URL
     * @param previewUrl 预览图 URL（可空）
     * @param format     模型格式（glb / gltf / obj / usdz 等）
     * @param taskId     上游异步任务标识（同步返回时为空）
     */
    public record GeneratedModel3D(String key, String modelUrl, String previewUrl,
                                   String format, String taskId) {
    }
}
