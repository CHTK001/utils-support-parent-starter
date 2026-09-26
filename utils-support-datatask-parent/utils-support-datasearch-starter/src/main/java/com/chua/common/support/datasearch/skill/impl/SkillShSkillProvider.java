package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.skill.model.SkillMarketListing;
import com.chua.common.support.datasearch.skill.spi.SkillOnlineProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * skills.sh 在线技能市场提供器。
 *
 * <p>通过 skills.sh 公开 API 搜索技能市场，以 Skill 形式暴露给 AI 技能系统。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("skills-sh")
public class SkillShSkillProvider extends SkillShProvider implements SkillOnlineProvider {

    @Override
    /**
     * 名称。
     *
     * @return 提供者名称
     */
    public String name() {
        return NAME;
    }

    @Override
    /**
     * 获取该市场源注册的全部技能定义。
     *
     * @return 技能定义列表
     */
    public List<SkillDefinition> getSkills() {
        return List.of(searchSkill());
    }

    @Override
    /**
     * 按关键词搜索在线技能。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果技能定义列表
     */
    public List<SkillDefinition> search(String keyword) {
        return List.of(searchSkill());
    }

    @Override
    /**
     * 搜索 skills.sh 技能清单。
     *
     * <p>该源只回传仓库与技能末级目录名，不回传完整目录路径，故 {@code path} 为末级名，
     * 由安装端按需解析；排序字段该源不支持，忽略。</p>
     *
     * @param keyword 搜索关键词
     * @param page    页码
     * @param limit   每页条数
     * @param sortBy  排序方式，该源忽略
     * @return 市场技能条目列表
     */
    @SuppressWarnings("unchecked")
    public List<SkillMarketListing> searchMarket(String keyword, int page, int limit, String sortBy) {
        int size = Math.max(1, limit);
        int offset = (Math.max(1, page) - 1) * size;
        try {
            Map<String, Object> apiResult = callApi(keyword == null ? "" : keyword, size, offset);
            List<Map<String, Object>> items = (List<Map<String, Object>>) apiResult.get("items");
            if (items == null || items.isEmpty()) {
                return Collections.emptyList();
            }
            List<SkillMarketListing> listings = new ArrayList<>(items.size());
            int rank = offset + 1;
            for (Map<String, Object> item : items) {
                listings.add(SkillMarketListing.builder()
                        .id(NAME + ":" + text(item.get("key")))
                        .source(NAME)
                        .rank(rank++)
                        .name(text(item.get("name")))
                        .author(text(item.get("repoOwner")))
                        .url(text(item.get("readmeUrl")))
                        .path(text(item.get("skillId")))
                        .installs(number(item.get("installs")))
                        .build());
            }
            return listings;
        } catch (Exception e) {
            log.warn("skills.sh 市场清单搜索失败: keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }

    @Override
    /**
     * 安装技能。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否安装成功
     */
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    /**
     * 卸载技能。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否卸载成功
     */
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }

    /**
     * 安全取文本。
     *
     * @param value 原始值
     * @return 文本值，入参为 null 时返回 null
     */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 安全取整数指标。
     *
     * @param value 原始值
     * @return 长整型值，非数字或 null 时返回 0
     */
    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
