package com.chua.common.support.collection;

import java.util.Map;
import java.util.function.Function;

/**
 * 层级映射函数接口，继承 {@link Function}{@code <Map<String, Object>, Map<String, Object>>}。
 * <p>
 * 该接口定义了一种将 {@code Map<String, Object>} 数据按照特定层级规则进行转换的契约。
 * 目前有两个核心实现：
 * </p>
 * <ul>
 *   <li>{@link LevelsClose} — 将多层嵌套的 Map 结构展平为单层键值对</li>
 *   <li>{@link LevelsOpen} — 将扁平化的键值对恢复为多层级嵌套结构</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 */
@FunctionalInterface
public interface Levels extends Function<Map<String, Object>, Map<String, Object>> {
}
