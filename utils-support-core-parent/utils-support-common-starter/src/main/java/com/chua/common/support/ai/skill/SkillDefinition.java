package com.chua.common.support.ai.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 技能定义
 *
 * <p>注册到技能管理器中的技能元信息，包含名称、描述、参数 schema 和执行处理器。
 *
 * @author CH
 * @since 2026/07/15
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class SkillDefinition {

    /** 技能名称 */
    /**
     * 名称
     */
    private final String name;

    /** 技能描述 */
    /**
     * 描述
     */
    private final String description;

    /** 参数 schema */
    private final List<SkillArgumentSchema> arguments;

    /** 技能处理器 */
    private final SkillHandler handler;

    public SkillDefinition(String name, String description, List<SkillArgumentSchema> arguments, SkillHandler handler) {
        this.name = name;
        this.description = description;
        this.arguments = arguments != null ? arguments : Collections.emptyList();
        this.handler = handler;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<SkillArgumentSchema> getArguments() {
        return arguments;
    }

    public SkillHandler getHandler() {
        return handler;
    }

    /**
     * 执行技能
     *
     * @param args 调用参数
     * @return 执行结果
     */
    public SkillResult execute(Map<String, Object> args) {
        if (handler == null) {
            return SkillResult.error("技能处理器未设置");
        }
        return handler.handle(args);
    }

    /**
     * 从 SKILL.md 文件路径创建技能定义
     *
     * @param name        技能名称
     * @param description 技能描述
     * @param path        技能文件路径
     * @return 技能定义
     */
    public static SkillDefinition skill(String name, String description, String path) {
        SkillHandler handler = args -> {
            try {
                String content = Files.readString(Path.of(path));
                return SkillResult.success(content);
            } catch (Exception e) {
                return SkillResult.error("读取技能文件失败: " + e.getMessage());
            }
        };
        return new SkillDefinition(name, description, null, handler);
    }
}