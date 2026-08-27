package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 深度学习模型列表工具。
 * <p>
 * 统一从 {@link IdentificationEngine} / {@link ModelRegistry} 按引擎名称（provider）与能力过滤模型，
 * 为各本地 {@code XxxClient} 的 {@code models()} 提供一致的模型列表来源。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DeeplearningModels {

    /** 创建 DeeplearningModels 实例 */
    private DeeplearningModels() {
    }

    /**
     * 获取指定引擎（provider）注册的全部模型。
     *
     * @param engine 引擎名称，如 "onnx"、"pytorch"、"paddle"、"tensorflow"、"llama"
     * @return 模型定义列表
     */
    public static List<ModelDefinition> models(String engine) {
        if (engine == null || engine.isBlank()) {
            return List.of();
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (ModelDefinition def : AbstractIdentificationEngine.getInstance().getModels()) {
            if (def != null && engine.equalsIgnoreCase(def.getProvider())) {
                result.add(def);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取全部已注册模型。
     *
     * @return 模型定义列表
     */
    public static List<ModelDefinition> all() {
        return AbstractIdentificationEngine.getInstance().getModels();
    }

    /**
     * 获取指定引擎中按输入/输出类型匹配的模型。
     * <p>从 ModelRegistry 条目中匹配输入输出类型，返回满足条件的模型定义列表。</p>
     *
     * @param engine     引擎名称，如 "onnx"、"pytorch"
     * @param inputType  输入类型（可为 null 表示不限制）
     * @param outputType 输出类型（可为 null 表示不限制）
     * @return 模型定义列表
     */
    public static List<ModelDefinition> models(String engine, Class<?> inputType, Class<?> outputType) {
        List<ModelDefinition> result = new ArrayList<>();
        for (ModelDefinition def : AbstractIdentificationEngine.getInstance().getModels()) {
            if (def == null || !engine.equalsIgnoreCase(def.getProvider())) {
                continue;
            }
            ModelRegistry.Entry entry = ModelRegistry.get(def.getId());
            if (entry == null) {
                continue;
            }
            boolean inputOk = inputType == null || entry.inputType() == null || inputType.isAssignableFrom(entry.inputType());
            boolean outputOk = outputType == null || entry.outputType() == null || outputType.isAssignableFrom(entry.outputType());
            if (inputOk && outputOk) {
                result.add(def);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按模型 ID 精确获取模型定义。
     *
     * @param modelId 模型标识
     * @return 模型定义，不存在返回 null
     */
    public static ModelDefinition byId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        for (ModelDefinition def : AbstractIdentificationEngine.getInstance().getModels()) {
            if (def != null && modelId.equals(def.getId())) {
                return def;
            }
        }
        return null;
    }

    /**
     * 获取引擎注册表中的模型数量。
     *
     * @return 模型数量
     */
    public static int count() {
        return AbstractIdentificationEngine.getInstance().getModels().size();
    }

    /**
     * 获取指定引擎的模型 ID 列表。
     *
     * @param engine 引擎名称
     * @return 模型 ID 列表
     */
    public static List<String> modelIds(String engine) {
        List<String> ids = new ArrayList<>();
        for (ModelDefinition def : models(engine)) {
            if (def.getId() != null) {
                ids.add(def.getId());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * 按当前服务器硬件配置挑选指定引擎的推荐模型。
     *
     * <p>规则：优先取该引擎内 {@code recommended=true} 且硬件配置（显存）满足当前设备的模型；
     * 无推荐条目时退化为列表第一个；{@code auto} 设备策略下自动探测本机 GPU。</p>
     *
     * @param engine        引擎名称（如 "onnx"）
     * @param deviceSetting 设备设置：auto / cpu / gpu / cuda，可为 null（走系统属性，缺省 auto）
     * @return 推荐模型 ID；无可用模型返回 null
     */
    public static String recommended(String engine, String deviceSetting) {
        if (engine == null || engine.isBlank()) {
            return null;
        }
        List<String> ids = modelIds(engine);
        if (ids.isEmpty()) {
            return null;
        }
        String selected = com.chua.deeplearning.support.engine.ModelSelector.selectRecommended(ids, deviceSetting);
        return selected != null ? selected : ids.get(0);
    }
}
