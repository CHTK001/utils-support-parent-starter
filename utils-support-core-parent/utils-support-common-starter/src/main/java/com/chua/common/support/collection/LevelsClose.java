package com.chua.common.support.collection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.chua.common.support.constant.NumberConstant.DEFAULT_BUFFER_SIZE;

/**
 * 层级结构展平处理类，将多层级嵌套的 Map 结构展平为单层级的键值对结构。
 * <p>
 * 转换示例：
 * <pre>{@code
 * // 输入（嵌套）
 * {"user": {"name": "Alice", "age": 30}, "tags": ["dev", "admin"]}
 *
 * // 输出（展平）
 * {"user.name": "Alice", "user.age": 30, "tags[0]": "dev", "tags[1]": "admin"}
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public class LevelsClose implements Levels {
    private static final String DEFAULT_SEPARATOR = ".";

    private String sp = DEFAULT_SEPARATOR;

    /**
     * 构造一个使用默认分隔符（"."）的展平处理器。
     */
    public LevelsClose() {
    }

    /**
     * 构造一个使用指定分隔符的展平处理器。
     *
     * @param sp 层级分隔符，默认值为 "."
     */
    public LevelsClose(String sp) {
        this.sp = sp;
    }

    /**
     * 递归遍历并分析 Map 结构，将多层级的键值对展平为单层结构。
     *
     * @param map    原始嵌套 Map
     * @param result 展平后的结果 Map
     */
    private void analysisHierarchicalAnalysis(Map<String, Object> map, final Map<String, Object> result) {
        if (null == map) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            dataFormatProfileHierarchicalAnalysis(key, value, result);
        }
    }

    /**
     * 根据值类型进行分层展平处理。
     * <ul>
     *   <li>{@link Map} 类型：递归展平子级，键名以分隔符拼接</li>
     *   <li>{@link List} 类型：按索引展平为 {@code key[index]} 格式</li>
     *   <li>其他类型：直接存入结果 Map</li>
     * </ul>
     *
     * @param parentName  父级键名
     * @param valueObject 值对象
     * @param result      结果 Map
     */
    private void dataFormatProfileHierarchicalAnalysis(String parentName, Object valueObject, Map<String, Object> result) {
        if (valueObject instanceof Map) {
@SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) valueObject;
            doAnalysisMapValueHierarchicalAnalysis(parentName, mapValue, result);
        } else if (valueObject instanceof List) {
            List<Object> listValue = (List<Object>) valueObject;
            doAnalysisListValueHierarchicalAnalysis(parentName, listValue, result);
        } else {
            result.put(parentName, valueObject);
        }
    }

    /**
     * 处理 Map 类型的值，将其每个条目递归展平到结果 Map 中。
     * <p>子键名以 "父级键名 + 分隔符 + 子键名" 的形式拼接。</p>
     *
     * @param parentName 父级键名
     * @param map        Map 类型的值
     * @param result     结果 Map
     */
    private void doAnalysisMapValueHierarchicalAnalysis(String parentName, Map<String, Object> map, Map<String, Object> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            dataFormatProfileHierarchicalAnalysis(parentName + sp + key, value, result);
        }
    }

    /**
     * 处理 List 类型的值，将每个元素按索引展平到结果 Map 中。
     * <p>元素键名以 "父级键名 + [索引]" 的形式拼接。</p>
     *
     * @param parentName 父级键名
     * @param source     List 类型的值
     * @param result     结果 Map
     */
    private void doAnalysisListValueHierarchicalAnalysis(String parentName, List<Object> source, Map<String, Object> result) {
        for (int i = 0; i < source.size(); i++) {
            dataFormatProfileHierarchicalAnalysis(parentName + "[" + i + "]", source.get(i), result);
        }
    }

    /**
     * 将输入的嵌套 Map 展平为单层键值对结构。
     *
     * @param stringObjectMap 原始嵌套 Map
     * @return 展平后的单层 Map
     */
    @Override
    public Map<String, Object> apply(Map<String, Object> stringObjectMap) {
        Map<String, Object> properties1 = new HashMap<>(DEFAULT_BUFFER_SIZE);
        analysisHierarchicalAnalysis(stringObjectMap, properties1);
        return properties1;
    }
}
