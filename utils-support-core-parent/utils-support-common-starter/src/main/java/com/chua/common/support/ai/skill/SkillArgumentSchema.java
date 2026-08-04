package com.chua.common.support.ai.skill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 技能参数 schema
 *
 * <p>描述技能参数的名称、类型、是否必填等信息。
 *
 * @author CH
 * @since 2026/07/15
 */
@NullUnmarked
public class SkillArgumentSchema {

    /** 参数名称 */
    /**
     * 名称
     */
    private final String name;

    /** 参数描述 */
    /**
     * 描述
     */
    private final String description;

    /** 参数类型（string、number、boolean、enum 等） */
    /**
     * 类型
     */
    private final String type;

    /** 是否必填 */
    /**
     * 是否必填
     */
    private final boolean required;

    /** 枚举值列表（仅 type=enum 时有效） */
    private final List<String> enumValues;

    public SkillArgumentSchema(String name, String description, String type, boolean required, List<String> enumValues) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.required = required;
        this.enumValues = enumValues;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("type", type);
        map.put("required", required);
        if (enumValues != null) {
            map.put("enum", enumValues);
        }
        return map;
    }
}