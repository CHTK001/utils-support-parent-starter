package com.chua.common.support.ai.skill;

import com.chua.common.support.ai.agent.AgentSystemPromptBuilder;

import java.util.Map;

/**
* 技能描述注入 system 的轻量工具（不依赖 Agent 执行环）。
* <p>
* 只负责把 {@link SkillManager} 中的技能写成 Markdown，拼进 system prompt。
* 不执行工具调用、不跑规划 —— ChatClient / Aggregate 均可使用。
* </p>
*
* <pre>{@code
* String system = SkillPrompt.inject(baseSystem, skillManager);
* client.system(system).chatSync(prompt);
* }</pre>
*
* @author CH
 */
public final class SkillPrompt {

    /** 创建 SkillPrompt 实例 */
    private SkillPrompt() {
    }

    /**
    * 生成技能 Markdown 段。
    */
    public static String section(Map<String, SkillDefinition> skills) {
        return AgentSystemPromptBuilder.buildSkillsSection(skills);
    }

    /**
    * 生成技能 Markdown 段。
    */
    public static String section(SkillManager skillManager) {
        if (skillManager == null) {
            return "";
        }
        return section(skillManager.getAll());
    }

    /**
    * 将技能说明追加到已有 system（空 skills 则原样返回）。
    */
    public static String inject(String baseSystem, SkillManager skillManager) {
        String sec = section(skillManager);
        if (sec == null || sec.isBlank()) {
            return baseSystem != null ? baseSystem : "";
        }
        if (baseSystem == null || baseSystem.isBlank()) {
            return sec.trim();
        }
        return baseSystem.trim() + "\n\n---\n" + sec.trim();
    }

    /**
    * 将技能说明追加到已有 system。
    */
    public static String inject(String baseSystem, Map<String, SkillDefinition> skills) {
        String sec = section(skills);
        if (sec == null || sec.isBlank()) {
            return baseSystem != null ? baseSystem : "";
        }
        if (baseSystem == null || baseSystem.isBlank()) {
            return sec.trim();
        }
        return baseSystem.trim() + "\n\n---\n" + sec.trim();
    }
}
