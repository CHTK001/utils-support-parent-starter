package com.chua.common.support.datasearch.skill.spi;

import com.chua.common.support.ai.skill.SkillDefinition;

import java.util.Collections;
import java.util.List;

/**
 * Skills 技能提供者接口
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SkillProvider {

    /**
     * 获取提供者名称
     *
     * @return 提供者名称
     */
    String name();

    /**
     * 获取该提供者注册的全部技能定义
     *
     * @return 技能定义列表
     */
    List<SkillDefinition> getSkills();

    /**
     * 安装技能到指定客户端
     *
     * @param clientId 客户端标识（如编辑器名称）
     * @param skillId  技能标识
     * @return 是否安装成功
     */
    default boolean install(String clientId, String skillId) {
        return false;
    }

    /**
     * 从指定客户端卸载技能
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否卸载成功
     */
    default boolean uninstall(String clientId, String skillId) {
        return false;
    }

    /**
     * 列出当前机器上已安装（配置目录存在）的可用客户端。
     *
     * @return 配置目录实际存在的客户端名称列表
     */
    default List<String> listAvailable() {
        return Collections.emptyList();
    }

    /**
     * 扫描本地 AI 编辑器/Agent 的技能目录，返回可导入的 Agent 技能定义。
     *
     * <p>离线 SkillProvider 通过此方法暴露本机已安装编辑器（Cursor、Claude Code、
     * CodeBuddy 等）的 skills/rules 目录中的技能，供「从 Agent 导入」统一收集。</p>
     *
     * @return Agent 技能定义列表；不提供离线扫描的实现返回空列表
     */
    default List<SkillDefinition> listAgentSkills() {
        return Collections.emptyList();
    }
}
