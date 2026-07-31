package com.chua.common.support.collection;


import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.StringUtils;

import java.util.*;

import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.utils.MapUtils.DEFAULT_INITIAL_CAPACITY;


/**
 * 层级结构展开与合并工具类
 * <p>
 * 用于将扁平化的键值对（如 "user.name": "test", "user.hobbies[0]": "reading"）
 * 转换为多层级嵌套的 Map 和 List 结构，并支持多个此类结构的深度合并。
 * </p>
 *
 * @author CH
 * @version 1.0.0
 */
public class LevelsOpen implements Levels {

    /**
     * 将扁平化的 Map 转换为多层级嵌套的 Map 结构。
     * <p>
     * 支持以下格式的扁平键：
     * <ul>
     *   <li>"user.name" → {"user": {"name": value}}</li>
     *   <li>"hobbies[0]" → {"hobbies": [value]}</li>
     *   <li>"users[0].name" → {"users": [{"name": value}]}</li>
     * </ul>
     * </p>
     *
     * @param stringObjectMap 扁平化的键值对 Map
     * @return 嵌套后的 Map 结构，如果输入为空则返回 null
     */
    @Override
    public Map<String, Object> apply(Map<String, Object> stringObjectMap) {
        if (MapUtils.isEmpty(stringObjectMap)) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> keySet = stringObjectMap.keySet();
        List<String> list = new ArrayList<>(keySet);
        Collections.sort(list);
        for (String key : list) {
            Map<String, Object> map = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
            String tempKey = key;
            int index = tempKey.indexOf(".");
            if (index > -1) {
                tempKey = tempKey.substring(0, index);
            }
            int newIndex = tempKey.indexOf(SYMBOL_LEFT_SQUARE_BRACKET);
            int newEndIndex = tempKey.indexOf(SYMBOL_RIGHT_SQUARE_BRACKET);
            Object value = stringObjectMap.get(key);

            if (index == -1) {
                if (newIndex == -1) {
                    map.put(key, value);
                } else {
                    map.put(key.substring(0, newIndex), levelOpenList(key.substring(newEndIndex + 2), value));
                }
            }
            if (newIndex == -1) {
                String newKey = key;
                String splitKey = "";
                if (index != -1) {
                    newKey = key.substring(0, index);
                    splitKey = key.substring(index + 1);
                }
                if (!StringUtils.isNullOrEmpty(splitKey)) {
                    map.put(newKey, levelOpenMap(splitKey, value));
                } else {
                    map.put(newKey, value);
                }
            } else {
                map.put(key.substring(0, newIndex), levelOpenListMap(key.substring(newEndIndex + 2), value));
            }
            result.add(map);
        }
        return this.merge(result);
    }

    /**
     * 合并多个分片 Map 为一个统一的嵌套结构 Map。
     *
     * @param toArray 包含多个 Map 分片的列表
     * @return 合并后的最终 Map
     */
    private Map<String, Object> merge(List<Map<String, Object>> toArray) {
        Map<String, Object> result = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
        for (Map<String, Object> objectMap : toArray) {
            merge(result, objectMap);
        }
        return result;
    }

    /**
     * 深度合并两个 Map。
     * <p>
     * 如果存在相同的 Key 且 Value 均为 Map 类型，则递归合并子级；
     * 如果 Value 为 List 类型，则合并 List 元素。
     * </p>
     *
     * @param mapLeft  目标 Map（方法内部会修改此 Map）
     * @param mapRight 源 Map
     */
    public void merge(Map<String, Object> mapLeft, Map<String, Object> mapRight) {
        for (Map.Entry<String, Object> entry : mapRight.entrySet()) {
            if (mapLeft.containsKey(entry.getKey())) {
                Object o = mapLeft.get(entry.getKey());
                Object o1 = entry.getValue();
                if (isAllMap(o, o1)) {
                    Map<String, Object> asMap = BeanUtils.objectToMap(o);
                    merge(asMap, BeanUtils.objectToMap(o1));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> targetMap = (Map<String, Object>) o;
                    targetMap.putAll(asMap);
                } else if (CollectionUtils.isList(o)) {
                    mergeList(o, o1);
                }
            } else {
                mapLeft.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 合并两个列表对象，支持 List 元素的深度合并。
     *
     * @param leftList  左侧列表对象
     * @param rightList 右侧列表对象
     */
    private void mergeList(Object leftList, Object rightList) {
        List<Object> temp = CollectionUtils.ifList(leftList);
        if (temp.isEmpty()) {
            if (CollectionUtils.isList(rightList)) {
                temp.addAll(CollectionUtils.ifList(rightList));
            } else {
                temp.add(rightList);
            }
            return;
        }

        if (!CollectionUtils.isList(rightList)) {
            temp.add(rightList);
            return;
        }
        List<Object> list = CollectionUtils.ifList(rightList);
        intoTemp(temp.size() - 1, temp, list);
    }

    /**
     * 判断两个对象是否均为 Map 类型。
     *
     * @param left  左侧对象
     * @param right 右侧对象
     * @return 如果均为 Map 返回 true，否则返回 false
     */
    private boolean isAllMap(Object left, Object right) {
        return left instanceof Map && right instanceof Map;
    }

    /**
     * 处理列表元素的深度合并逻辑，递归合并 Map 类型的列表元素。
     *
     * @param offset 当前处理的列表索引
     * @param temp   目标列表
     * @param list   源列表
     */
    private void intoTemp(int offset, List<Object> temp, List<Object> list) {
        Object o3 = list.get(0);
        Object o2 = temp.get(offset);
        if (isAllMap(o2, o3)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> o2Temp = (Map<String, Object>) o2;
            @SuppressWarnings("unchecked")
            Map<String, Object> o3Temp = (Map<String, Object>) o3;
            boolean isAll = allIn(o2Temp, o3Temp);
            if (!isAll) {
                o2Temp.putAll(o3Temp);
                temp.remove(offset);
                temp.add(o2Temp);
            } else {
                if (offset > 0 && (offset - 1) < 0) {
                    intoTemp(--offset, temp, list);
                    return;
                }
                temp.add(o3Temp);
            }
        } else {
            temp.addAll(list);
        }
    }

    /**
     * 判断右侧 Map 的所有 Key 是否都包含在左侧 Map 中。
     *
     * @param leftMap  左侧 Map
     * @param rightMap 右侧 Map
     * @return 如果右侧 Map 的所有 Key 都在左侧 Map 中存在，返回 true
     */
    private boolean allIn(Map<String, Object> leftMap, Map<String, Object> rightMap) {
        boolean isAll = true;
        for (String s : rightMap.keySet()) {
            if (!leftMap.containsKey(s)) {
                isAll = false;
                break;
            }
        }
        return isAll;
    }

    /**
     * 递归解析带有 "." 的键，构建嵌套 Map。
     * <p>例如：key="user.name", value="test" → {"user": {"name": "test"}}</p>
     *
     * @param key   当前处理的键（可能包含 "." 分隔符）
     * @param value 对应的值
     * @return 构建后的嵌套 Map
     */
    private Map<String, Object> levelOpenMap(String key, Object value) {
        String tempKey = key;
        int index = tempKey.indexOf(".");
        if (index > -1) {
            tempKey = tempKey.substring(0, index);
        }
        int newIndex = tempKey.indexOf(SYMBOL_LEFT_SQUARE_BRACKET);
        Map<String, Object> item = new HashMap<>(DEFAULT_INITIAL_CAPACITY);

        if (index == -1) {
            if (newIndex == -1) {
                item.put(key, value);
            } else {
                item.put(key.substring(0, newIndex), levelOpenList(key.substring(newIndex), value));
            }
        } else {
            if (newIndex == -1) {
                item.put(key.substring(0, index), levelOpenMap(key.substring(index + 1), value));
            } else {
                item.put(key.substring(0, newIndex), levelOpenListMap(key.substring(index + 1), value));
            }
        }
        return item;
    }

    /**
     * 递归解析带有 "[]" 和 "." 的键，构建包含 Map 元素的 List。
     * <p>例如：key="[0].name", value="test" → [{"name": "test"}]</p>
     *
     * @param key   当前处理的键
     * @param value 对应的值
     * @return 构建后的 List，包含解析后的 Map 元素
     */
    private List<Map<String, Object>> levelOpenListMap(String key, Object value) {
        String tempKey = key;
        int index = tempKey.indexOf(".");
        if (index > -1) {
            tempKey = tempKey.substring(0, index);
        }
        int newIndex = tempKey.indexOf(SYMBOL_LEFT_SQUARE_BRACKET);
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> item = new HashMap<>(DEFAULT_INITIAL_CAPACITY);

        if (index == -1) {
            if (newIndex == -1) {
                item.put(key, value);
            } else {
                item.put(key.substring(0, newIndex), levelOpenList(key.substring(newIndex + 1), value));
            }
        } else {
            if (newIndex == -1) {
                item.put(key.substring(0, index), levelOpenMap(key.substring(index + 1), value));
            } else {
                item.put(key.substring(0, newIndex), levelOpenListMap(key.substring(index + 1), value));
            }
        }
        result.add(item);
        return result;
    }

    /**
     * 将对象或集合统一转换为 List。
     * <p>如果对象已经是 {@link Collection} 类型，则直接包装为 ArrayList；
     * 否则将单个对象包装为单元素 List。</p>
     *
     * @param substring 未使用的参数（历史遗留）
     * @param o         需要转换的对象
     * @return 转换后的 List
     */
    private List<Object> levelOpenList(String substring, Object o) {
        if (o instanceof Collection) {
            return new ArrayList<Object>((Collection<?>) o);
        }
        return new ArrayList<>(Collections.singletonList(o));
    }

}
