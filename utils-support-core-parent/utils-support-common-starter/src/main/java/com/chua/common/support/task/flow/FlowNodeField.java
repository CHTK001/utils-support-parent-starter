package com.chua.common.support.task.flow;

import java.util.List;

/**
* 流程节点配置字段元信息。
*
* <p>描述一个节点配置表单中的一个字段，供前端 ReFlow 属性面板
* 按字段类型动态渲染表单控件。节点可通过 {@link FlowNode#configSchema()}
* 声明自身的配置结构，引擎在节点类型清单中透出，前端据此生成配置表单。</p>
*
* <p>字段类型（{@code type}）约定：</p>
* <ul>
*   <li>{@code input} — 单行文本输入框</li>
*   <li>{@code textarea} — 多行文本域</li>
*   <li>{@code select} — 下拉选择（配合 {@link #options()}）</li>
*   <li>{@code switch} — 开关（布尔）</li>
*   <li>{@code number} — 数字输入</li>
*   <li>{@code kv} — 键值对编辑器（如请求头、映射）</li>
* </ul>
*
* @param key          字段键，对应节点配置 JSON 中的属性名
* @param label        字段展示名
* @param type         字段类型（输入/textarea/选择/switch/数字/kv）
* @param required     是否必填
* @param placeholder  占位提示
* @param defaultValue 默认值
* @param options      选择 类型的候选选项
* @author CH
* @since 4.0.0.42
 */
public record FlowNodeField(
        String key,
        String label,
        String type,
        boolean required,
        String placeholder,
        Object defaultValue,
        List<FlowNodeOption> options
) {

    /**
    * 创建文本输入字段。
    *
    * @param key   字段键
    * @param label 字段展示名
    * @return 字段元信息
     */
    public static FlowNodeField input(String key, String label) {
        return new FlowNodeField(key, label, "input", false, null, null, null);
    }

    /**
    * 创建文本输入字段（带必填标记）。
    *
    * @param key      字段键
    * @param label    字段展示名
    * @param required 是否必填
    * @return 字段元信息
     */
    public static FlowNodeField input(String key, String label, boolean required) {
        return new FlowNodeField(key, label, "input", required, null, null, null);
    }

    /**
    * 创建多行文本域字段。
    *
    * @param key         字段键
    * @param label       字段展示名
    * @param placeholder 占位提示
    * @return 字段元信息
     */
    public static FlowNodeField textarea(String key, String label, String placeholder) {
        return new FlowNodeField(key, label, "textarea", false, placeholder, null, null);
    }

    /**
    * 创建下拉选择字段。
    *
    * @param key     字段键
    * @param label   字段展示名
    * @param options 候选选项
    * @return 字段元信息
     */
    public static FlowNodeField select(String key, String label, List<FlowNodeOption> options) {
        return new FlowNodeField(key, label, "select", false, null, null, options);
    }

    /**
    * 创建开关字段。
    *
    * @param key   字段键
    * @param label 字段展示名
    * @return 字段元信息
     */
    public static FlowNodeField bool(String key, String label) {
        return new FlowNodeField(key, label, "switch", false, null, false, null);
    }

    /**
    * 创建数字字段。
    *
    * @param key   字段键
    * @param label 字段展示名
    * @return 字段元信息
     */
    public static FlowNodeField number(String key, String label) {
        return new FlowNodeField(key, label, "number", false, null, null, null);
    }

    /**
    * 创建键值对编辑器字段。
    *
    * @param key   字段键
    * @param label 字段展示名
    * @return 字段元信息
     */
    public static FlowNodeField kv(String key, String label) {
        return new FlowNodeField(key, label, "kv", false, null, null, null);
    }
}
