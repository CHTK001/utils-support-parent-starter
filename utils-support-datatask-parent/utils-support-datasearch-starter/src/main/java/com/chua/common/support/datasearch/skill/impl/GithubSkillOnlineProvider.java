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
 * GitHub 技能仓库在线技能提供器。
 *
 * <p>通过 GitHub Git Trees API 从默认技能仓库发现 AI 技能，
 * 以 Skill 形式暴露给 AI 技能系统。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("github")
public class GithubSkillOnlineProvider extends GithubSkillProvider implements SkillOnlineProvider {

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
        return List.of(discoverSkill());
    }

    @Override
    /**
     * 按关键词搜索在线技能。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果技能定义列表
     */
    public List<SkillDefinition> search(String keyword) {
        return List.of(discoverSkill());
    }

    @Override
    /**
     * 搜索 GitHub 技能仓库清单。
     *
     * <p>该源一次性枚举默认仓库树，故分页与排序在本地切片完成；排序字段不支持，忽略。</p>
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
        int skip = (Math.max(1, page) - 1) * size;
        try {
            Map<String, Object> apiResult = discoverSkills(keyword);
            List<Map<String, Object>> items = (List<Map<String, Object>>) apiResult.get("items");
            if (items == null || items.isEmpty()) {
                return Collections.emptyList();
            }
            List<SkillMarketListing> listings = new ArrayList<>(size);
            int rank = skip + 1;
            for (int i = skip; i < items.size() && listings.size() < size; i++) {
                Map<String, Object> item = items.get(i);
                listings.add(SkillMarketListing.builder()
                        .id(NAME + ":" + text(item.get("key")))
                        .source(NAME)
                        .rank(rank++)
                        .name(text(item.get("name")))
                        .author(text(item.get("repoOwner")))
                        .url(text(item.get("readmeUrl")))
                        .path(text(item.get("directory")))
                        .build());
            }
            return listings;
        } catch (Exception e) {
            log.warn("GitHub 市场清单搜索失败: keyword={}", keyword, e);
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
}
