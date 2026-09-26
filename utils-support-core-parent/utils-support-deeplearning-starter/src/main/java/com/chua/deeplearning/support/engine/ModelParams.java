package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.ai.DetectionConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型级运行参数存储。
 *
 * <p>与 {@link ModelRegistry.Entry} 的静态注册信息分离，用于承载<strong>可被外部改写</strong>的
 * 推理运行参数，例如：</p>
 * <ul>
 *   <li>{@code device} —— 推理设备（cpu / gpu / auto），取代原先只能靠全局系统属性
 *       {@code deeplearning.device} 的做法，使同一进程内不同模型可使用不同设备；</li>
 *   <li>{@code threshold} / {@code iouThreshold} —— 置信度与 NMS 阈值；</li>
 *   <li>模型自有的额外参数 —— 由各 Translator 自行声明读取，例如 inputSize、candidates、
 *       providersParams 等（不同模型的额外参数不同，此处不做白名单限制）。</li>
 * </ul>
 *
 * <p>参数读取优先级（后者覆盖前者）：</p>
 * <ol>
 *   <li>调用方显式传入的 {@code options}（最高优先级，用于单次调用覆盖）；</li>
 *   <li>本类保存的模型级持久化参数；</li>
 *   <li>系统属性 {@code deeplearning.device} 与 {@link DeviceSelector} 的 auto 探测；</li>
 *   <li>各 Translator 自身的硬编码默认值。</li>
 * </ol>
 *
 * <p>本类为进程内存储，不负责持久化到数据库；持久化由应用层（如 Spring 的
 * {@code sys_ai_global_setting} 或 provider 模型表）负责，读取后调用
 * {@link #put(String, Map)} 或 {@link #putAll(String, Map)} 回填。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ModelParams {

    /**
     * 推理设备参数键。
     */
    public static final String KEY_DEVICE = "device";

    /**
     * 线程数参数键（部分模型支持）。
     */
    public static final String KEY_THREADS = "threads";

    /**
     * 模型级参数：modelId -> 参数表。
     */
    private static final Map<String, Map<String, Object>> STORE = new ConcurrentHashMap<>();

    /**
     * 模型参数。
     */
    private ModelParams() {
    }

    /**
     * 读取某模型的全部参数。
     *
     * @param modelId 模型标识
     * @return 参数表（只读视图，无参数时返回空映射）
     */
    public static Map<String, Object> get(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Object> params = STORE.get(modelId);
        return params == null ? Collections.emptyMap() : Collections.unmodifiableMap(params);
    }

    /**
     * 读取某模型的单个参数。
     *
     * @param modelId    模型标识
     * @param key        参数键
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public static Object get(String modelId, String key, Object defaultValue) {
        if (modelId == null || modelId.isBlank() || key == null) {
            return defaultValue;
        }
        Map<String, Object> params = STORE.get(modelId);
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 读取某模型的设备参数。
     *
     * @param modelId 模型标识
     * @return 设备名（cpu / gpu / auto），未设置时返回 空
     */
    public static String deviceOf(String modelId) {
        Object value = get(modelId, KEY_DEVICE, null);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 整体覆盖某模型的参数表。
     *
     * @param modelId 模型标识
     * @param params  参数表；为空 表示清空该模型参数
     */
    public static void put(String modelId, Map<String, Object> params) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        if (params == null || params.isEmpty()) {
            STORE.remove(modelId);
            return;
        }
        STORE.put(modelId, new LinkedHashMap<>(params));
    }

    /**
     * 合并写入某模型的参数（覆盖同名键，保留其它键）。
     *
     * @param modelId 模型标识
     * @param params  待合并参数
     */
    public static void putAll(String modelId, Map<String, Object> params) {
        if (modelId == null || modelId.isBlank() || params == null || params.isEmpty()) {
            return;
        }
        STORE.compute(modelId, (key, old) -> {
            Map<String, Object> merged = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old);
            merged.putAll(params);
            return merged;
        });
    }

    /**
     * 列出所有已配置参数的模型标识。
     *
     * @return 模型标识集合（无配置时返回空集合）
     */
    public static Set<String> getAllKeys() {
        return Set.copyOf(STORE.keySet());
    }

    /**
     * 移除某模型的全部参数。
     *
     * @param modelId 模型标识
     */
    public static void remove(String modelId) {
        if (modelId != null) {
            STORE.remove(modelId);
        }
    }

    /**
     * 清空全部模型参数（主要用于测试与配置全量重载）。
     */
    public static void clear() {
        STORE.clear();
    }

    /**
     * 合并「模型级参数」与「调用方显式参数」。
     *
     * <p>调用方显式传入的键优先级更高。返回的新 Map 一定是可变且非空的
     * （便于后续继续 {@code put}）。</p>
     *
     * @param modelId 模型标识
     * @param options 调用方显式参数，可为 {@code null}
     * @return 合并后的参数表
     */
    public static Map<String, Object> merge(String modelId, Map<String, Object> options) {
        Map<String, Object> merged = new LinkedHashMap<>(get(modelId));
        if (options != null) {
            merged.putAll(options);
        }
        return merged;
    }

    /**
     * 解析合并后参数中的设备设置，供 DJL 与裸 ONNX Runtime 两条链路共用。
     *
     * <p>取值顺序：显式 options 的 device → 模型级参数的 device → 系统属性（{@code auto}）。</p>
     *
     * @param modelId 模型标识
     * @param options 调用方显式参数，可为 {@code null}
     * @return 归一化后的设备名（cpu / gpu）
     */
    public static String resolveDevice(String modelId, Map<String, Object> options) {
        String setting = null;
        if (options != null) {
            Object value = options.get(KEY_DEVICE);
            if (value != null && !String.valueOf(value).isBlank()) {
                setting = String.valueOf(value);
            }
        }
        if (setting == null || setting.isBlank()) {
            setting = deviceOf(modelId);
        }
        return DeviceSelector.resolve(setting);
    }

    /**
     * 归一化阈值参数。
     *
     * @param modelId 模型标识
     * @param options 调用方显式参数，可为 {@code null}
     * @return 置信度阈值；无有效配置时返回 {@code null} 表示由模型使用自身默认值
     */
    public static Float resolveThreshold(String modelId, Map<String, Object> options) {
        Object value = lookup(modelId, options, DetectionConfiguration.KEY_THRESHOLD);
        return toFloat(value);
    }

    /**
     * 归一化 NMS 阈值参数。
     *
     * @param modelId 模型标识
     * @param options 调用方显式参数，可为 {@code null}
     * @return NMS IOU 阈值；无有效配置时返回 {@code null} 表示由模型使用自身默认值
     */
    public static Float resolveNms(String modelId, Map<String, Object> options) {
        Object value = lookup(modelId, options, DetectionConfiguration.KEY_IOU_THRESHOLD);
        return toFloat(value);
    }

    /**
     * 参数键 → 候选实例字段名。
     *
     * <p>各 Translator 自行决定参数落到哪个字段，这里给出常见别名，
     * 使未显式实现 {@link DetectionConfigurable} 的模型也能在运行期接收参数更新。</p>
     */
    private static final Map<String, List<String>> FIELD_ALIASES = Map.of(
            DetectionConfiguration.KEY_THRESHOLD,
            List.of("threshold", "scoreThreshold", "configuredScoreThreshold", "probThreshold",
                    "confThreshold", "defaultThreshold"),
            DetectionConfiguration.KEY_IOU_THRESHOLD,
            List.of("iouThreshold", "nmsThreshold", "nms", "iouThresholdValue"),
            KEY_THREADS,
            List.of("threads", "intraOpNumThreads", "numThreads"));

    /**
     * 将参数应用到 Translator 实例（反射按字段名回写）。
     *
     * <p>用于让未实现 {@link DetectionConfigurable} 的 Translator 也能在运行期接收参数变更：
     * 遍历参数表，对每个键按 {@link #FIELD_ALIASES} 找到实例上同名的数值字段并赋值。
     * 找不到对应字段的键被静默忽略（该模型可能不支持此参数）。</p>
     *
     * <p><b>线程安全提示</b>：本方法会修改被多线程共享的 Translator 实例字段。
     * 与 {@link DetectionConfigurable} 的既有行为一致——若推理与配置变更可能并发，
     * 建议在业务层保证配置变更与推理互斥，或由各 Translator 自行加锁。</p>
     *
     * @param target  目标实例（Translator / Predictor / Factory）
     * @param options 参数键值对
     * @return 实际成功应用的键数量
     */
    public static int applyTo(Object target, Map<String, Object> options) {
        if (target == null || options == null || options.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (Map.Entry<String, Object> item : options.entrySet()) {
            String key = item.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            List<String> candidates = FIELD_ALIASES.get(key);
            if (candidates == null) {
                continue;
            }
            Object value = item.getValue();
            for (String field : candidates) {
                if (assignField(target, field, value)) {
                    applied++;
                    break;
                }
            }
        }
        return applied;
    }

    /**
     * 尝试把值写入实例的指定字段（按名称向上查找类层次）。
     *
     * @param target 目标实例
     * @param name   字段名
     * @param value  值
     * @return true 表示已写入
     */
    private static boolean assignField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                var field = type.getDeclaredField(name);
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    return false;
                }
                Object converted = convert(value, field.getType());
                if (converted == null) {
                    return false;
                }
                field.setAccessible(true);
                field.set(target, converted);
                return true;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException | RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 把参数值转换为字段声明的类型。
     *
     * @param value 值
     * @param type  目标类型
     * @return 转换后的值；类型不兼容时返回 {@code null}
     */
    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        Number number = toNumber(value);
        if (number == null) {
            return null;
        }
        if (type == float.class || type == Float.class) {
            return number.floatValue();
        }
        if (type == double.class || type == Double.class) {
            return number.doubleValue();
        }
        if (type == int.class || type == Integer.class) {
            return number.intValue();
        }
        if (type == long.class || type == Long.class) {
            return number.longValue();
        }
        if (type == short.class || type == Short.class) {
            return number.shortValue();
        }
        return null;
    }

    /**
     * 将参数值转为数字。
     *
     * @param value 参数值
     * @return 数字；不可解析时返回 {@code null}
     */
    private static Number toNumber(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        try {
            String text = String.valueOf(value).trim();
            if (text.indexOf('.') >= 0) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按「显式参数优先」顺序查找参数值。
     *
     * @param modelId 模型标识
     * @param options 调用方显式参数，可为 {@code null}
     * @param key     参数键
     * @return 参数值；不存在时返回 {@code null}
     */
    private static Object lookup(String modelId, Map<String, Object> options, String key) {
        if (options != null) {
            Object value = options.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return get(modelId, key, null);
    }

    /**
     * 将参数值转为浮点数。
     *
     * @param value 参数值
     * @return 浮点值；不可解析时返回 {@code null}
     */
    private static Float toFloat(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
