package com.chua.common.support.datasearch.skill.spi;

import com.chua.common.support.ai.skill.SkillDefinition;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 离线技能提供者接口。
 *
 * <p>负责扫描本机已安装的 AI 编辑器/Agent 的 skills/rules 目录，将本地技能
   * 暴露给「从 智能体 导入」统一收集。每种 智能体（Cursor、Claude 编码、Codex 等）
 * 一个实现，各自扫描自己的配置目录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SkillOfflineProvider {

    /**
      * 获取提供者名称（智能体 标识，如 Cursor/claude/codex）。
     *
     * @return 提供者名称
     */
    String name();

    /**
      * 获取该 智能体 注册的全部技能定义。
     *
     * @return 技能定义列表
     */
    default List<SkillDefinition> getSkills() {
        return Collections.emptyList();
    }

    /**
      * 扫描本机该 智能体 的 skills/rules 目录，返回可导入的本地技能定义。
     *
     * @return 本地技能定义列表
     */
    default List<SkillDefinition> listAgentSkills() {
        return Collections.emptyList();
    }

    /**
      * 该 智能体 是否已安装（配置目录存在）。
     *
     * @return true 表示已安装
     */
    default boolean isInstalled() {
        return false;
    }

    /**
      * 安装技能到该 智能体 客户端。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否安装成功
     */
    default boolean install(String clientId, String skillId) {
        return false;
    }

    /**
      * 从该 智能体 客户端卸载技能。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否卸载成功
     */
    default boolean uninstall(String clientId, String skillId) {
        return false;
    }

    /**
     * 解析指定技能在本机的真实落盘位置（目录或单文件 {@code .md}/{@code .mdc}）。
     *
     * <p>供后端将 {@code agent://PROVIDER/SKILL} 形式的虚拟地址解析为可导入的文件路径。
      * 未实现或未找到时返回 空。</p>
     *
     * @param skillName 技能名（目录名或去掉扩展名的 .md 文件名）
     * @return 落盘路径；找不到返回 空
     */
    default Path resolveSkillPath(String skillName) {
        return null;
    }
}
