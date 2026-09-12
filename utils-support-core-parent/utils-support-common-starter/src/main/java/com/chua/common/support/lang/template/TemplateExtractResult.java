package com.chua.common.support.lang.template;

import com.chua.common.support.lang.json.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 模板提取结果，承载从输入文本中按模板抽取出的变量集合。
*
* <p>结果区分“已成功提取的变量”与“模板声明但输入中缺失的变量”，
* 便于调用方在不确定的输入格式下进行缺字段校验与降级处理。</p>
*
* <p>已提取变量同时以两种形态提供：</p>
* <ul>
*     <li>{@link #toMap()} / {@link #get(String)} —— 变量名到值的映射，适合按名取值；</li>
*     <li>{@link #vars()} —— 有序的 {@link TemplateVar} 列表，每项携带变量名、值及在输入中的路径，
*     适合需要顺序或溯源的场景。</li>
* </ul>
*
* @param extracted 已提取的变量名到值的映射，值保留原始类型（字符串/数字/布尔/对象/数组）
* @param missing   模板中声明但在输入中未能定位的变量名列表，可能为空
* @param vars      已成功提取的变量有序列表（按模板占位符出现顺序），可能为空
* @author CH
* @since 4.0.0.42
 */
public record TemplateExtractResult(
        Map<String, Object> extracted,
        List<String> missing,
        List<TemplateVar> vars
) {

    /**
    * 根据已提取的映射构造全部成功的结果（无变量明细）。
    *
    * @param extracted 已提取的变量映射，不可为 null
    * @return 提取结果实例
     */
    public static TemplateExtractResult success(Map<String, Object> extracted) {
        return success(extracted, Collections.emptyList());
    }

    /**
    * 根据已提取的映射与变量明细构造全部成功的结果。
    *
    * @param extracted 已提取的变量映射，不可为 null
    * @param vars      已成功提取的变量列表，可为空
    * @return 提取结果实例
     */
    public static TemplateExtractResult success(Map<String, Object> extracted, List<TemplateVar> vars) {
        return new TemplateExtractResult(
                extracted == null ? Collections.emptyMap() : extracted,
                Collections.emptyList(),
                vars == null ? Collections.emptyList() : vars
        );
    }

    /**
    * 根据已提取映射与缺失变量构造部分成功的结果（无变量明细）。
    *
    * @param extracted 已提取的变量映射，不可为 null
    * @param missing   缺失的变量名集合，不可为 null
    * @return 提取结果实例
     */
    public static TemplateExtractResult partial(Map<String, Object> extracted, List<String> missing) {
        return partial(extracted, missing, Collections.emptyList());
    }

    /**
    * 根据已提取映射、缺失变量与变量明细构造部分成功的结果。
    *
    * @param extracted 已提取的变量映射，不可为 null
    * @param missing   缺失的变量名集合，不可为 null
    * @param vars      已成功提取的变量列表，可为空
    * @return 提取结果实例
     */
    public static TemplateExtractResult partial(Map<String, Object> extracted, List<String> missing, List<TemplateVar> vars) {
        return new TemplateExtractResult(
                extracted == null ? Collections.emptyMap() : new LinkedHashMap<>(extracted),
                missing == null ? Collections.emptyList() : new java.util.ArrayList<>(missing),
                vars == null ? Collections.emptyList() : new java.util.ArrayList<>(vars)
        );
    }

    /**
    * 获取指定变量名对应的提取值。
    *
    * @param name 变量名（来自模板中的 {name} 占位符）
    * @return 提取值；若未提取到则返回 null
     */
    public Object get(String name) {
        return extracted.get(name);
    }

    /**
    * 判断指定变量是否已被成功提取。
    *
    * @param name 变量名
    * @return 已提取返回 true，否则返回 false
     */
    public boolean has(String name) {
        return extracted.containsKey(name);
    }

    /**
    * 判断本次提取是否完整（即不存在缺失变量）。
    *
    * @return 全部命中返回 true，存在缺失返回 false
     */
    public boolean isSuccess() {
        return missing.isEmpty();
    }

    /**
    * 返回已提取变量的不可变视图。
    *
    * @return 变量名到值的映射
     */
    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(extracted);
    }

    /**
    * 返回已成功提取的变量有序列表（按模板占位符出现顺序）。
    *
    * <p>列表中的每个 {@link TemplateVar} 携带变量名、值及在输入中的路径，
    * 适合需要顺序或溯源的消费场景。</p>
    *
    * @return 不可变的 TemplateVar 列表
     */
    public List<TemplateVar> vars() {
        return Collections.unmodifiableList(vars);
    }

    /**
    * 将已提取的变量集合序列化为 JSON 字符串。
    *
    * @return JSON 格式字符串，便于日志输出与下游传输
     */
    public String toJson() {
        return Json.toJson(extracted);
    }
}
