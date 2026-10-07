package com.chua.common.support.ai.skill;
import com.chua.common.support.collection.wrap.Wraps;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认技能管理器（进程内注册表）。
 *
 * @author CH
 * @since 2026/07/20
 */
public class DefaultSkillManager implements SkillManager {

    /**
     * 技能注册表，键为技能名称
     */
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    /**
     * 注册
     */
    @Override
    public SkillManager register(SkillDefinition skillDefinition) {
        if (skillDefinition == null || skillDefinition.getName() == null || skillDefinition.getName().isBlank()) {
            throw new IllegalArgumentException("skillDefinition name 不能为空");
        }
        skills.put(skillDefinition.getName(), skillDefinition);
        return this;
    }

    /**
     * 获取
     */
    @Override
    public SkillDefinition get(String name) {
        return name == null ? null : skills.get(name);
    }

    /**
     * 获取All
     */
    @Override
    public Map<String, SkillDefinition> getAll() {
        return Collections.unmodifiableMap(Wraps.linkedMap(skills));
    }

    /**
     * ListAllToolDescriptors
     */
    @Override
    public List<Map<String, Object>> listAllToolDescriptors() {
        List<Map<String, Object>> list = Wraps.list();
        for (SkillDefinition skill : skills.values()) {
            Map<String, Object> item = Wraps.linkedMap();
            item.put("name", skill.getName());
            item.put("description", skill.getDescription());
            List<Map<String, Object>> args = Wraps.list();
            if (skill.getArguments() != null) {
                for (SkillArgumentSchema schema : skill.getArguments()) {
                    args.add(schema.toMap());
                }
            }
            item.put("arguments", args);
            list.add(item);
        }
        return list;
    }

    /**
     * 执行
     */
    @Override
    public SkillResult execute(String name, Map<String, Object> args) {
        SkillDefinition skill = get(name);
        if (skill == null) {
            return SkillResult.error("未注册技能: " + name);
        }
        try {
            return skill.execute(args == null ? Map.of() : args);
        } catch (Exception e) {
            return SkillResult.error("技能执行异常: " + e.getMessage());
        }
    }

    /**
     * Contains
     */
    @Override
    public boolean contains(String name) {
        return name != null && skills.containsKey(name);
    }
}